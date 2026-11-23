package com.mwilky.airalerts.band

/**
 * Gattlink: the reliable, ordered byte stream the Fitbit Air runs over two GATT characteristics.
 * Ported from Fitbit's Golden Gate (`xp/gattlink/gg_gattlink.c`, Apache-2.0).
 *
 * Packets are either control packets (a reset handshake that opens a session and agrees window
 * sizes) or data packets, which carry a 5-bit sequence number, an optional cumulative ack, and a
 * slice of the stream. Unacked data is resent after [EXPECTED_ACK_TIMEOUT_MS].
 *
 * Not thread-safe: every call, including [Scheduler] callbacks, must come from one thread.
 */
internal class Gattlink(
    private val transport: Transport,
    private val scheduler: Scheduler,
    private val listener: Listener,
    private val windowSize: Int = DEFAULT_WINDOW_SIZE
) {
    interface Transport {
        /** Largest packet the link carries (the ATT MTU minus 3). */
        val maxPacketSize: Int
        fun send(packet: ByteArray)
    }

    fun interface Scheduler {
        fun schedule(delayMs: Long, action: () -> Unit): Cancellable
    }

    fun interface Cancellable {
        fun cancel()
    }

    interface Listener {
        /** A reset handshake finished; the stream starts again from nothing. */
        fun onSessionReady()

        /** The band asked for a new session. Called before it is answered, so [mute] still stops the answer. */
        fun onResetRequested() {}

        /** In-order stream bytes from the band. */
        fun onData(data: ByteArray)
    }

    enum class State { INITIALIZED, AWAITING_SELF_INITIATED, AWAITING_REMOTE_INITIATED, READY }

    var state = State.INITIALIZED
        private set

    private var muted = false

    // Negotiated window sizes.
    private var txWindow = windowSize
    private var rxWindow = windowSize

    // Outbound.
    private var pending = ByteArray(0) // unacked bytes first, then bytes not sent yet
    private val payloadSizes = IntArray(SN_WINDOW)
    private var nextDataSn = 0
    private var nextExpectedAckSn = 0
    private var psnToAckWith = 0
    private var outstandingUnacked = 0
    private var ackNow = false
    private var ackTimer: Cancellable? = null
    private var retransmitTimer: Cancellable? = null

    // Inbound.
    private var nextExpectedPsn = 0

    /** Opens a session by sending a reset request. */
    fun start() {
        check(state == State.INITIALIZED)
        reset()
    }

    /** Starts a new session, dropping anything in flight. */
    fun reset() {
        cancelTimers()
        state = State.AWAITING_SELF_INITIATED
        clearSession()
        retransmitTimer = scheduler.schedule(RESET_COMPLETE_TIMEOUT_MS) { onRetransmitTimer() }
        sendRaw(byteArrayOf((CONTROL or RESET_REQUEST).toByte()))
    }

    /** Stops sending anything at all, so the band and Google Health can resynchronise without us. */
    fun mute() {
        muted = true
        cancelTimers()
    }

    /** Queues stream bytes for the band. */
    fun write(data: ByteArray) {
        pending += data
        if (state == State.READY) sendNextPackets()
    }

    fun handlePacket(packet: ByteArray) {
        if (packet.isEmpty() || state == State.INITIALIZED || muted) return
        val header = packet[0].toInt() and 0xFF
        if (header and CONTROL != 0) {
            when (header and CONTROL_TYPE_MASK) {
                RESET_REQUEST -> handleResetRequest()
                RESET_COMPLETE -> handleResetComplete(packet)
            }
            return
        }
        if (state == State.READY) handleDataPacket(packet)
    }

    private fun handleResetRequest() {
        cancelTimers()
        state = State.AWAITING_REMOTE_INITIATED
        clearSession()
        listener.onResetRequested()
        if (muted) return
        sendResetComplete()
    }

    private fun handleResetComplete(packet: ByteArray) {
        if (state != State.AWAITING_SELF_INITIATED && state != State.AWAITING_REMOTE_INITIATED) return
        if (packet.size < RESET_COMPLETE_SIZE) return
        // The peer's rx window caps what we send, its tx window caps what we accept.
        txWindow = minOf(windowSize, packet[3].toInt() and 0xFF).coerceIn(1, SN_WINDOW - 1)
        rxWindow = minOf(windowSize, packet[4].toInt() and 0xFF).coerceIn(1, SN_WINDOW - 1)
        if (state == State.AWAITING_SELF_INITIATED) sendResetComplete()
        cancelTimers()
        clearSession()
        state = State.READY
        listener.onSessionReady()
        if (pending.isNotEmpty()) sendNextPackets()
    }

    private fun sendResetComplete() {
        retransmitTimer?.cancel()
        retransmitTimer = scheduler.schedule(RESET_COMPLETE_ACK_TIMEOUT_MS) { onRetransmitTimer() }
        sendRaw(
            byteArrayOf(
                (CONTROL or RESET_COMPLETE).toByte(),
                MIN_VERSION,
                MAX_VERSION,
                windowSize.toByte(), // max rx window
                windowSize.toByte()  // max tx window
            )
        )
    }

    private fun handleDataPacket(packet: ByteArray) {
        var offset = 0
        if (packet[0].toInt() and WITH_ACK != 0) {
            val ackedPsn = packet[0].toInt() and SN_MASK
            if (payloadSizes[ackedPsn] != 0) {
                val nextPsn = nextSn(ackedPsn)
                val acked = bytesAwaitingAckUpTo(nextPsn)
                var sn = nextExpectedAckSn
                while (sn != nextPsn) {
                    payloadSizes[sn] = 0
                    sn = nextSn(sn)
                }
                retransmitTimer?.cancel()
                retransmitTimer = if (payloadSizes[nextPsn] != 0) {
                    scheduler.schedule(EXPECTED_ACK_TIMEOUT_MS) { onRetransmitTimer() }
                } else null
                nextExpectedAckSn = nextPsn
                pending = pending.copyOfRange(acked, pending.size)
            }
            offset = 1
        }

        if (offset == packet.size) {
            // Only an ack.
            sendNextPackets()
            return
        }

        val psn = packet[offset].toInt() and SN_MASK
        if (psn == nextExpectedPsn) {
            nextExpectedPsn = nextSn(psn)
            psnToAckWith = psn
            listener.onData(packet.copyOfRange(offset + 1, packet.size))
            if (muted || state != State.READY) return
        } else if (distance(psn, nextExpectedPsn) >= rxWindow) {
            // Neither expected nor a resend of something already received.
            return
        }
        // A resend of something already received is acked again with the last good sequence number.
        outstandingUnacked++
        ackTimer?.cancel()
        ackTimer = scheduler.schedule(SEND_ACK_TIMEOUT_MS) {
            ackTimer = null
            ackNow = true
            sendNextPackets()
        }
        sendNextPackets()
    }

    private fun sendNextPackets() {
        while (!muted && state == State.READY) {
            val packet = nextPacket() ?: return
            sendRaw(packet.bytes)
            if (packet.withAck) {
                outstandingUnacked = 0
                ackNow = false
                ackTimer?.cancel()
                ackTimer = null
            }
            if (packet.payloadSize > 0) {
                payloadSizes[nextDataSn] = packet.payloadSize
                nextDataSn = nextSn(nextDataSn)
            }
        }
    }

    private class Outgoing(val bytes: ByteArray, val withAck: Boolean, val payloadSize: Int)

    private fun nextPacket(): Outgoing? {
        var room = minOf(transport.maxPacketSize, MAX_PACKET_SIZE)
        val withAck = ackNow || outstandingUnacked > rxWindow / 2
        val header = if (withAck) byteArrayOf((WITH_ACK or psnToAckWith).toByte()) else ByteArray(0)
        if (withAck) room -= 1

        if (distance(nextExpectedAckSn, nextDataSn) >= txWindow) {
            return if (withAck) Outgoing(header, true, 0) else null
        }
        val offset = bytesAwaitingAckUpTo(nextDataSn)
        // A resend keeps the slicing of the first send; its size is still recorded until acked.
        var size = payloadSizes[nextDataSn]
        if (size == 0) {
            size = minOf(pending.size - offset, room - 1)
            if (size <= 0) return if (withAck) Outgoing(header, true, 0) else null
        }
        if (retransmitTimer == null) {
            retransmitTimer = scheduler.schedule(EXPECTED_ACK_TIMEOUT_MS) { onRetransmitTimer() }
        }
        val bytes = header + byteArrayOf(nextDataSn.toByte()) + pending.copyOfRange(offset, offset + size)
        return Outgoing(bytes, withAck, size)
    }

    private fun onRetransmitTimer() {
        retransmitTimer = null
        if (muted) return
        when (state) {
            // No reset complete in time: start over from our side.
            State.AWAITING_SELF_INITIATED, State.AWAITING_REMOTE_INITIATED -> reset()
            State.READY -> {
                // Roll back and resend everything not acked yet.
                nextDataSn = nextExpectedAckSn
                sendNextPackets()
            }
            State.INITIALIZED -> Unit
        }
    }

    private fun bytesAwaitingAckUpTo(endExclusive: Int): Int {
        var total = 0
        var sn = nextExpectedAckSn
        while (sn != endExclusive) {
            total += payloadSizes[sn]
            sn = nextSn(sn)
        }
        return total
    }

    private fun clearSession() {
        pending = ByteArray(0)
        payloadSizes.fill(0)
        nextDataSn = 0
        nextExpectedAckSn = 0
        psnToAckWith = 0
        outstandingUnacked = 0
        ackNow = false
        nextExpectedPsn = 0
    }

    private fun cancelTimers() {
        ackTimer?.cancel()
        ackTimer = null
        retransmitTimer?.cancel()
        retransmitTimer = null
    }

    private fun sendRaw(packet: ByteArray) {
        if (!muted) transport.send(packet)
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 8
        const val MAX_PACKET_SIZE = 512

        private const val SN_WINDOW = 32
        private const val SN_MASK = 0x1F
        private const val WITH_ACK = 0x40
        private const val CONTROL = 0x80
        private const val CONTROL_TYPE_MASK = 0x7F
        private const val RESET_REQUEST = 0x00
        private const val RESET_COMPLETE = 0x01
        private const val RESET_COMPLETE_SIZE = 5
        private const val MIN_VERSION: Byte = 0
        private const val MAX_VERSION: Byte = 0

        private const val SEND_ACK_TIMEOUT_MS = 200L
        private const val RESET_COMPLETE_TIMEOUT_MS = 1_000L
        private const val RESET_COMPLETE_ACK_TIMEOUT_MS = 2_000L
        private const val EXPECTED_ACK_TIMEOUT_MS = 4_000L

        private fun nextSn(sn: Int) = (sn + 1) % SN_WINDOW
        private fun distance(begin: Int, endExclusive: Int) = (SN_WINDOW + endExclusive - begin) % SN_WINDOW
    }
}
