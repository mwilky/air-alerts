package com.mwilky.airalerts.band

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.tls.DTLSTransport
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.random.Random

enum class BuzzError {
    BLUETOOTH_OFF, NO_PERMISSION, NOT_PAIRED, OUT_OF_REACH, NO_ANSWER, SECURE, REJECTED, UNEXPECTED
}

class BandException(val error: BuzzError, val detail: String? = null) : Exception(detail ?: error.name)

data class BuzzFailure(val error: BuzzError, val detail: String? = null)

enum class BuzzStep { FOUND, LINK, SECURE, BUZZ, HANDBACK }

sealed interface StepState {
    data object Waiting : StepState
    data object Running : StepState
    data class Done(val millis: Long) : StepState
    data class Failed(val failure: BuzzFailure) : StepState
}

/** One buzz attempt, step by step, for the Last test list. */
data class BuzzRun(
    val startedAtMillis: Long,
    val steps: Map<BuzzStep, StepState> = BuzzStep.entries.associateWith { StepState.Waiting }
)

sealed interface BuzzResult {
    /** [millis] runs from the start of the attempt to the band confirming the buzz. */
    data class Buzzed(val millis: Long) : BuzzResult
    data class Missed(val failure: BuzzFailure) : BuzzResult
}

/**
 * Makes a Fitbit Air buzz once: Bluetooth link → Gattlink → IPv4/UDP → DTLS (bootstrap key) →
 * CoAP `/settings`, then hands the channel back to Google Health. Each session also reads the band's
 * battery, and [checkBattery] borrows the channel just for that.
 *
 * The band has a single channel shared by every app on the link, so whatever happens (success,
 * failure, timeout or cancellation) the channel is handed back and our client closed before
 * [buzz] returns. One buzz runs at a time.
 *
 * For a ringing call the session stays open and buzzes again every ring gap until the call
 * stops ringing, so there's one handshake and one hand-back however long it rings.
 */
class AirBand(private val context: Context) {
    private val mutex = Mutex()
    private val thread = HandlerThread("air-link").apply { start() }
    private val handler = Handler(thread.looper)

    private val _run = MutableStateFlow<BuzzRun?>(null)
    val lastRun: StateFlow<BuzzRun?> = _run.asStateFlow()

    private val _battery = MutableStateFlow<BandBattery?>(null)

    /** The battery as last read, by a buzz or [checkBattery]. */
    val battery: StateFlow<BandBattery?> = _battery.asStateFlow()

    private val _checkingBattery = MutableStateFlow(false)
    val checkingBattery: StateFlow<Boolean> = _checkingBattery.asStateFlow()

    @Volatile
    private var lastBatteryCheck = 0L

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Buzzes once with [pattern], then again [ringGapMillis] after each buzz while [repeatWhile]
     * stays true (at most [RING_MAX_MS]).
     */
    suspend fun buzz(
        pattern: BuzzPattern = BuzzPattern.SINGLE,
        repeatWhile: StateFlow<Boolean>? = null,
        ringGapMillis: Long = RING_GAP_MS
    ): BuzzResult = mutex.withLock {
        _busy.value = true
        try {
            Session(repeatWhile, pattern = pattern, ringGapMillis = ringGapMillis).run()
        } finally {
            _busy.value = false
        }
    }

    /**
     * Borrows the channel just to read the battery: Google Health has it the rest of the time, and
     * the band has no Battery Service anyone can read. Skipped while a buzz runs (it reads the
     * battery too) and within [BATTERY_CHECK_GAP_MS] of the last check, so switching screens doesn't
     * keep taking the band.
     */
    suspend fun checkBattery() {
        if (mutex.isLocked) return
        val now = SystemClock.elapsedRealtime()
        if (lastBatteryCheck != 0L && now - lastBatteryCheck < BATTERY_CHECK_GAP_MS) return
        lastBatteryCheck = now
        mutex.withLock {
            _checkingBattery.value = true
            try {
                Session(repeatWhile = null, buzz = false).run()
            } finally {
                _checkingBattery.value = false
            }
        }
    }

    /** The paired Fitbit Air, if any. Null without Nearby devices, too. */
    @SuppressLint("MissingPermission")
    fun pairedBand(): BluetoothDevice? {
        if (!hasPermission()) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return adapter.bondedDevices.orEmpty().firstOrNull { it.name?.contains(BAND_NAME, ignoreCase = true) == true }
    }

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun bluetoothOn() = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /**
     * One attempt. All link-side state lives on [handler]'s thread. Without [buzz] it only reads the
     * battery, and its steps stay out of the Last buzz list.
     */
    private inner class Session(
        private val repeatWhile: StateFlow<Boolean>?,
        private val buzz: Boolean = true,
        private val pattern: BuzzPattern = BuzzPattern.SINGLE,
        private val ringGapMillis: Long = RING_GAP_MS
    ) {
        private val start = SystemClock.elapsedRealtime()
        private var stepStart = start
        private var link: AirLink? = null
        private var gattlink: Gattlink? = null
        private val assembler = PacketAssembler()
        private val inbox = LinkedBlockingQueue<ByteArray>()
        private var phoneAddress = IpFraming.PHONE_ADDRESS
        private var bandAddress = IpFraming.BAND_ADDRESS
        private var ipId = Random.nextInt(0x10000)

        /** Counts Gattlink sessions, so a wait can tell a new one from an old one. */
        private val sessions = MutableStateFlow(0)

        @Volatile
        private var handingBack = false

        @Volatile
        private var lastBandReset = 0L

        @Volatile
        private var lastSessionReady = 0L
        private val bandReset = CompletableDeferred<Unit>()
        private val disconnected = CompletableDeferred<Unit>()
        private var run = BuzzRun(startedAtMillis = System.currentTimeMillis())

        suspend fun run(): BuzzResult {
            if (buzz) _run.value = run
            var buzzedAt: Long? = null
            var tookChannel = false
            try {
                begin(BuzzStep.FOUND)
                val device = findBand()
                openLink(device)
                done(BuzzStep.FOUND)

                begin(BuzzStep.LINK)
                tookChannel = true
                startGattlink()
                done(BuzzStep.LINK)

                begin(BuzzStep.SECURE)
                val dtls = secureSession()
                done(BuzzStep.SECURE)
                onLink { link?.requestFast() }

                val coap = CoapChannel(dtls)
                if (buzz) {
                    begin(BuzzStep.BUZZ)
                    val strength = blocking { readStrength(coap) }
                    sendBuzz(coap, strength)
                    buzzedAt = SystemClock.elapsedRealtime()
                    done(BuzzStep.BUZZ)
                    repeatWhile?.let { ringBuzz(coap, strength, it) }
                    keepStrength(coap, strength)
                }
                readBattery(coap)
            } catch (e: BandException) {
                Log.w(TAG, "buzz failed: ${e.error} ${e.detail.orEmpty()}")
                fail(BuzzFailure(e.error, e.detail))
            } catch (e: IOException) {
                Log.w(TAG, "buzz failed", e)
                fail(BuzzFailure(BuzzError.NO_ANSWER, e.message))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "buzz failed", e)
                fail(BuzzFailure(BuzzError.UNEXPECTED, e.javaClass.simpleName))
            } finally {
                withContext(NonCancellable) {
                    if (tookChannel) handBack()
                    onLink {
                        gattlink?.mute()
                        link?.close()
                    }
                }
            }
            val took = buzzedAt?.let { it - start }
            return if (took != null) {
                BuzzResult.Buzzed(took)
            } else {
                val failure = run.steps.values.filterIsInstance<StepState.Failed>().firstOrNull()?.failure
                BuzzResult.Missed(failure ?: BuzzFailure(BuzzError.UNEXPECTED))
            }
        }

        private fun findBand(): BluetoothDevice {
            if (!hasPermission()) throw BandException(BuzzError.NO_PERMISSION)
            if (!bluetoothOn()) throw BandException(BuzzError.BLUETOOTH_OFF)
            return pairedBand() ?: throw BandException(BuzzError.NOT_PAIRED)
        }

        private suspend fun openLink(device: BluetoothDevice) {
            val l = AirLink(
                context = context,
                device = device,
                handler = handler,
                onPacket = { packet -> gattlink?.handlePacket(packet) },
                onDisconnected = { disconnected.complete(Unit) }
            )
            link = l
            l.open()
        }

        private suspend fun startGattlink() {
            onLink {
                val l = link ?: return@onLink
                gattlink = Gattlink(
                    transport = object : Gattlink.Transport {
                        override val maxPacketSize get() = l.maxPacketSize
                        override fun send(packet: ByteArray) = l.write(packet)
                    },
                    scheduler = { delay, action ->
                        val r = Runnable(action)
                        handler.postDelayed(r, delay)
                        Gattlink.Cancellable { handler.removeCallbacks(r) }
                    },
                    listener = object : Gattlink.Listener {
                        override fun onSessionReady() {
                            Log.d(TAG, "gattlink session ready")
                            lastSessionReady = SystemClock.elapsedRealtime()
                            assembler.reset()
                            inbox.clear()
                            sessions.update { it + 1 }
                        }

                        override fun onResetRequested() {
                            Log.d(TAG, "band reset the link")
                            lastBandReset = SystemClock.elapsedRealtime()
                            if (handingBack) {
                                // The band reset after our refusal: Google Health takes it from here.
                                gattlink?.mute()
                                bandReset.complete(Unit)
                            }
                        }

                        override fun onData(data: ByteArray) = onStream(data)
                    }
                ).also { it.start() }
            }
            awaitSession(after = 0, timeoutMs = LINK_TIMEOUT_MS) ?: throw BandException(BuzzError.NO_ANSWER, "no gattlink reset")
        }

        /**
         * Waits for the reset the band sends about half a second after a refusal or failure to
         * finish (its request and the session that follows), then [QUIET_MS] more, so our own
         * reset isn't answered by the tail of the band's. On the Pixel a reset sent in between
         * took the band's late reply as its own and the next handshake timed out.
         */
        private suspend fun awaitQuiet() {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val settled = lastBandReset > start && lastSessionReady > lastBandReset
                if (settled && now - lastSessionReady >= QUIET_MS) return
                if (now - start >= QUIET_MAX_MS) return
                kotlinx.coroutines.delay(50)
            }
        }

        /** Waits for a Gattlink session newer than [after]; null on timeout or a dropped link. */
        private suspend fun awaitSession(after: Int, timeoutMs: Long): Int? = withTimeoutOrNull(timeoutMs) {
            sessions.first { it > after || disconnected.isCompleted }
        }?.takeIf { !disconnected.isCompleted }

        private fun onStream(data: ByteArray) {
            for (packet in assembler.feed(data)) {
                val datagram = IpFraming.parse(packet) ?: continue
                if (datagram.dstPort != IpFraming.DTLS_PORT) continue
                // Answer from whatever addresses the band uses.
                bandAddress = datagram.srcAddress
                phoneAddress = datagram.dstAddress
                inbox.offer(datagram.payload)
            }
        }

        private fun sendDatagram(payload: ByteArray) = handler.post {
            val datagram = Datagram(phoneAddress, bandAddress, IpFraming.DTLS_PORT, IpFraming.DTLS_PORT, payload)
            ipId = (ipId + 1) and 0xFFFF
            gattlink?.write(IpFraming.build(datagram, ipId))
        }

        /**
         * The band offers Google Health's key first. Refusing it makes the band reset the link and
         * offer the bootstrap key, which opens our session. A plain failure usually means Google
         * Health answered the same handshake; a fresh link reset gets another try.
         */
        private suspend fun secureSession(): DTLSTransport {
            val deadline = SystemClock.elapsedRealtime() + SECURE_TIMEOUT_MS
            var attempts = 0
            var lastProblem = "no handshake"
            while (attempts < MAX_HANDSHAKES && SystemClock.elapsedRealtime() < deadline) {
                attempts++
                val outcome = withContext(Dispatchers.IO) {
                    runInterruptible { BandDtls.accept(BandDatagrams(inbox, ::sendDatagram)) }
                }
                when (outcome) {
                    is BandDtls.Outcome.Session -> {
                        Log.d(TAG, "secure session after $attempts handshake(s)")
                        return outcome.transport
                    }
                    is BandDtls.Outcome.WrongKey -> {
                        Log.d(TAG, "band offered key '${outcome.identity}', refused")
                        lastProblem = "wrong key ${outcome.identity}"
                    }
                    is BandDtls.Outcome.Failed -> {
                        Log.d(TAG, "handshake failed: ${outcome.reason}")
                        lastProblem = outcome.reason
                    }
                }
                if (disconnected.isCompleted) throw BandException(BuzzError.OUT_OF_REACH, "link dropped")
                // After a refusal or failure the band resets the link itself. Google Health answers
                // that reset too and then races us for the next handshake (seen on the Pixel: every
                // handshake after a band-started reset timed out). A reset we start ourselves is one
                // Google Health ignores, so let the band's settle, then start our own.
                awaitQuiet()
                val now = sessions.value
                onLink { gattlink?.reset() }
                awaitSession(after = now, timeoutMs = LINK_TIMEOUT_MS)
                    ?: throw BandException(BuzzError.NO_ANSWER, "no gattlink reset")
            }
            throw BandException(BuzzError.SECURE, lastProblem)
        }

        /** The wearer's buzz strength. Blocking. */
        private fun readStrength(coap: CoapChannel): Int {
            val read = coap.exchange(Coap.PUT, AirHaptics.READ_REQUEST)
            return AirHaptics.parseStrength(read.payload)
                ?: throw BandException(BuzzError.REJECTED, "settings ${read.codeText} ${read.payload.hex()}")
        }

        /**
         * One buzz in [pattern] that leaves [strength] in place. Each write waits its gap from the band
         * confirming the one before, so a slow first write doesn't shorten the gap after it.
         */
        private suspend fun sendBuzz(coap: CoapChannel, strength: Int) {
            AirHaptics.writes(pattern, strength).forEachIndexed { i, haptic ->
                if (haptic.afterMillis > 0) delay(haptic.afterMillis)
                val sent = SystemClock.elapsedRealtime()
                val write = blocking { coap.exchange(Coap.POST, haptic.body) }
                Log.d(TAG, "$pattern write ${i + 1}: ${write.codeText} in ${SystemClock.elapsedRealtime() - sent} ms")
                if (!write.isSuccess) throw BandException(BuzzError.REJECTED, "buzz ${write.codeText}")
            }
        }

        /**
         * Buzzes again [ringGapMillis] after each buzz over the same secure session while [ringing] stays true,
         * for at most [RING_MAX_MS] from the first buzz. A repeat that fails ends the repeats but not
         * the attempt: the first buzz already landed.
         */
        private suspend fun ringBuzz(coap: CoapChannel, strength: Int, ringing: StateFlow<Boolean>) {
            val until = SystemClock.elapsedRealtime() + RING_MAX_MS
            var count = 1
            while (SystemClock.elapsedRealtime() < until) {
                // Wakes early the moment the call is answered, declined or missed.
                val stopped = withTimeoutOrNull(ringGapMillis) { ringing.first { !it } } != null
                if (stopped || disconnected.isCompleted) break
                try {
                    sendBuzz(coap, strength)
                    count++
                } catch (e: BandException) {
                    Log.w(TAG, "repeat buzz refused: ${e.detail.orEmpty()}")
                    break
                } catch (e: IOException) {
                    Log.w(TAG, "repeat buzz failed", e)
                    break
                }
            }
            Log.d(TAG, "buzzed $count time(s) while ringing")
        }

        /** Puts the wearer's strength back if the band kept the other one. Never fails the attempt. */
        private suspend fun keepStrength(coap: CoapChannel, strength: Int) {
            try {
                val check = blocking { AirHaptics.parseStrength(coap.exchange(Coap.PUT, AirHaptics.READ_REQUEST).payload) }
                if (check != strength) {
                    Log.w(TAG, "strength $check after buzz, restoring $strength")
                    blocking { coap.exchange(Coap.POST, AirHaptics.entry(strength)) }
                }
            } catch (e: BandException) {
                Log.w(TAG, "couldn't check strength: ${e.detail.orEmpty()}")
            } catch (e: IOException) {
                Log.w(TAG, "couldn't check strength", e)
            }
        }

        /** Reads the battery, after any buzz so it never delays one. Never fails the attempt. */
        private suspend fun readBattery(coap: CoapChannel) {
            try {
                val reply = blocking { coap.exchange(Coap.GET, ByteArray(0), AirBattery.PATH) }
                val battery = AirBattery.parse(reply.payload, System.currentTimeMillis())
                if (battery == null) {
                    Log.w(TAG, "battery ${reply.codeText} ${reply.payload.hex()}")
                    return
                }
                Log.d(TAG, "battery ${battery.percent}%${if (battery.charging) ", charging" else ""}")
                _battery.value = battery
            } catch (e: BandException) {
                Log.w(TAG, "couldn't read battery: ${e.detail.orEmpty()}")
            } catch (e: IOException) {
                Log.w(TAG, "couldn't read battery", e)
            }
        }

        private suspend fun <T> blocking(block: () -> T): T = withContext(Dispatchers.IO) { runInterruptible { block() } }

        /**
         * Gives the channel back: a new link reset makes the band offer Google Health's key again,
         * we refuse it, the band resets the link for everyone, and from then on we stay silent so
         * Google Health finishes the handshake alone.
         */
        private suspend fun handBack() {
            begin(BuzzStep.HANDBACK)
            if (disconnected.isCompleted) {
                fail(BuzzFailure(BuzzError.OUT_OF_REACH))
                return
            }
            handingBack = true
            onLink { gattlink?.reset() }
            val result = withTimeoutOrNull(HANDBACK_TIMEOUT_MS) {
                val refusal = withContext(Dispatchers.IO) {
                    runInterruptible { BandDtls.accept(BandDatagrams(inbox, ::sendDatagram), refuseAll = true) }
                }
                Log.d(TAG, "hand-back handshake: $refusal")
                bandReset.await()
            }
            if (result != null) done(BuzzStep.HANDBACK) else fail(BuzzFailure(BuzzError.NO_ANSWER, "hand-back"))
        }

        private suspend fun onLink(block: () -> Unit) {
            val finished = CompletableDeferred<Unit>()
            handler.post {
                try {
                    block()
                } finally {
                    finished.complete(Unit)
                }
            }
            finished.await()
        }

        private fun begin(step: BuzzStep) {
            stepStart = SystemClock.elapsedRealtime()
            set(step, StepState.Running)
        }

        private fun done(step: BuzzStep) {
            val took = SystemClock.elapsedRealtime() - stepStart
            Log.d(TAG, "$step done in $took ms")
            set(step, StepState.Done(took))
        }

        /** Marks the step that was running as failed. */
        private fun fail(failure: BuzzFailure) {
            val running = run.steps.entries.firstOrNull { it.value == StepState.Running }?.key ?: return
            set(running, StepState.Failed(failure))
        }

        private fun set(step: BuzzStep, state: StepState) {
            run = run.copy(steps = run.steps + (step to state))
            if (buzz) _run.value = run
        }
    }

    /** Confirmable CoAP requests to the band over the secure session, one at a time. */
    private class CoapChannel(private val dtls: DTLSTransport) {
        private var messageId = Random.nextInt(0x10000)

        fun exchange(method: Int, body: ByteArray, path: String = AirHaptics.PATH): Coap.Message {
            messageId = (messageId + 1) and 0xFFFF
            val token = Random.nextBytes(4)
            val request = Coap.request(method, path, messageId, token, body)
            val buffer = ByteArray(dtls.receiveLimit)
            repeat(COAP_TRIES) {
                dtls.send(request, 0, request.size)
                val deadline = SystemClock.elapsedRealtime() + COAP_WAIT_MS
                while (true) {
                    val wait = (deadline - SystemClock.elapsedRealtime()).toInt()
                    if (wait <= 0) break
                    val size = dtls.receive(buffer, 0, buffer.size, wait)
                    if (size < 0) break
                    val message = Coap.parse(buffer.copyOf(size)) ?: continue
                    if (message.isRequest) {
                        // The band asking us something; we serve nothing.
                        if (message.type == Coap.TYPE_CON) {
                            val ack = Coap.ack(message.messageId, message.token, Coap.NOT_FOUND)
                            dtls.send(ack, 0, ack.size)
                        }
                        continue
                    }
                    if (!message.token.contentEquals(token)) continue
                    if (message.type == Coap.TYPE_CON) {
                        // A separate response: acknowledge it.
                        val ack = Coap.ack(message.messageId, ByteArray(0), 0)
                        dtls.send(ack, 0, ack.size)
                    }
                    if (message.code != 0) return message
                }
            }
            val name = when (method) {
                Coap.GET -> "GET"
                Coap.POST -> "POST"
                else -> "PUT"
            }
            throw BandException(BuzzError.NO_ANSWER, "no reply to $name $path")
        }
    }

    companion object {
        private const val TAG = "AirAlerts"
        private const val BAND_NAME = "Fitbit Air"
        private const val LINK_TIMEOUT_MS = 5_000L
        private const val SECURE_TIMEOUT_MS = 20_000L
        private const val MAX_HANDSHAKES = 4
        private const val QUIET_MS = 400L
        private const val QUIET_MAX_MS = 2_500L
        private const val HANDBACK_TIMEOUT_MS = 10_000L
        private const val COAP_TRIES = 3
        private const val COAP_WAIT_MS = 2_000L

        /** The default gap between buzzes while a call rings; the Settings tab offers slower ones. */
        const val RING_GAP_MS = 1_000L

        /** At most one battery-only check this often. */
        const val BATTERY_CHECK_GAP_MS = 60_000L

        /** Calls usually go to voicemail before this; stops a stuck notification buzzing forever. */
        const val RING_MAX_MS = 60_000L

        @Volatile
        private var instance: AirBand? = null

        fun get(context: Context): AirBand =
            instance ?: synchronized(this) { instance ?: AirBand(context.applicationContext).also { instance = it } }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
