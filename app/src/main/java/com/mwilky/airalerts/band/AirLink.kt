package com.mwilky.airalerts.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Our own GATT client on the Fitbit Air's tracker service. Google Health usually holds the
 * Bluetooth link already; Android shares it, so this connects at once.
 *
 * Every callback is moved onto [handler]'s thread, which also runs Gattlink, so the protocol
 * stays single-threaded. [write] must be called on that thread.
 */
@SuppressLint("MissingPermission") // AirBand checks Nearby devices before opening a link.
internal class AirLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val handler: Handler,
    private val onPacket: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val writes = ArrayDeque<ByteArray>()
    private var writing = false
    private var busyRetries = 0
    private var closed = false

    private var connected = CompletableDeferred<Boolean>()
    private var discovered = CompletableDeferred<Boolean>()
    private var mtuDone = CompletableDeferred<Int>()
    private var subscribed = CompletableDeferred<Boolean>()

    /** Largest Gattlink packet: the ATT MTU less its 3-byte header. */
    var maxPacketSize = 20
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onThread {
            Log.d(TAG, "connection state $newState status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.complete(false)
                discovered.complete(false)
                subscribed.complete(false)
                if (!closed) onDisconnected()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onThread {
            discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = onThread {
            mtuDone.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = onThread {
            if (d.uuid == CCCD) subscribed.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 12 calls only this older form, with the bytes left on the characteristic; Android 13
        // and later call the form below instead, so the version check just guards against both firing.
        @Deprecated("Android 12 only")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            onCharacteristicChanged(g, c, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != RX) return
            val copy = value.copyOf()
            onThread { if (!closed) onPacket(copy) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = onThread {
            writing = false
            writeNext()
        }
    }

    /**
     * Connects, finds the tracker channel and subscribes to it. Throws [BandException] when the
     * band can't be reached or doesn't have the channel.
     */
    suspend fun open() {
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
            ?: throw BandException(BuzzError.OUT_OF_REACH, "connectGatt returned null")
        gatt = g
        if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } != true) {
            throw BandException(BuzzError.OUT_OF_REACH)
        }
        if (!g.discoverServices() || withTimeoutOrNull(STEP_TIMEOUT_MS) { discovered.await() } != true) {
            throw BandException(BuzzError.OUT_OF_REACH, "service discovery failed")
        }
        val service = g.getService(TRACKER) ?: throw BandException(BuzzError.UNEXPECTED, "no tracker service")
        val txChar = service.getCharacteristic(TX) ?: throw BandException(BuzzError.UNEXPECTED, "no TX")
        val rxChar = service.getCharacteristic(RX) ?: throw BandException(BuzzError.UNEXPECTED, "no RX")
        tx = txChar

        // Google Health has usually raised the MTU already; asking again just reports it.
        val mtu = if (g.requestMtu(REQUESTED_MTU)) withTimeoutOrNull(STEP_TIMEOUT_MS) { mtuDone.await() } ?: 23 else 23
        maxPacketSize = (mtu - 3).coerceIn(20, Gattlink.MAX_PACKET_SIZE)

        g.setCharacteristicNotification(rxChar, true)
        val cccd = rxChar.getDescriptor(CCCD) ?: throw BandException(BuzzError.UNEXPECTED, "no CCCD")
        val value = if (rxChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        if (!writeDescriptor(g, cccd, value) ||
            withTimeoutOrNull(STEP_TIMEOUT_MS) { subscribed.await() } != true
        ) {
            throw BandException(BuzzError.OUT_OF_REACH, "subscribe failed")
        }
        Log.d(TAG, "link open, mtu $mtu")
    }

    /** Asks for the fast connection interval; only once our own session exists (see AirBand). */
    fun requestFast() {
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    fun write(packet: ByteArray) {
        if (closed) return
        writes.addLast(packet)
        writeNext()
    }

    private fun writeNext() {
        if (writing || closed) return
        val g = gatt ?: return
        val c = tx ?: return
        val packet = writes.removeFirstOrNull() ?: return
        when (writeCharacteristic(g, c, packet)) {
            BluetoothStatusCodes.SUCCESS -> {
                writing = true
                busyRetries = 0
            }
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> {
                writes.addFirst(packet)
                handler.postDelayed({ writeNext() }, BUSY_RETRY_MS)
            }
            else -> Log.w(TAG, "write of ${packet.size} bytes refused")
        }
    }

    /** The descriptor write, in the form this Android version has. */
    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    /**
     * The characteristic write as a [BluetoothStatusCodes] result. Before Android 13 the call only
     * says yes or no, and no usually means the stack is still busy with the last packet, so it's
     * retried as busy for a while before being counted as refused.
     */
    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, packet: ByteArray): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return g.writeCharacteristic(c, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = packet
        return when {
            g.writeCharacteristic(c) -> BluetoothStatusCodes.SUCCESS
            ++busyRetries <= MAX_BUSY_RETRIES -> BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
            else -> {
                busyRetries = 0
                BluetoothStatusCodes.ERROR_UNKNOWN
            }
        }
    }

    /** Lets go of our client only; Google Health's connection to the band stays up. */
    fun close() {
        if (closed) return
        closed = true
        writes.clear()
        gatt?.let {
            it.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    private fun onThread(block: () -> Unit) {
        if (handler.looper.isCurrentThread) block() else handler.post(block)
    }

    companion object {
        private const val TAG = "AirAlerts"
        val TRACKER: UUID = UUID.fromString("abbaff00-e56a-484c-b832-8b17cf6cbfe8")
        val TX: UUID = UUID.fromString("abbaff01-e56a-484c-b832-8b17cf6cbfe8")
        val RX: UUID = UUID.fromString("abbaff02-e56a-484c-b832-8b17cf6cbfe8")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val REQUESTED_MTU = 517
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val STEP_TIMEOUT_MS = 5_000L
        private const val BUSY_RETRY_MS = 10L
        /** A second of retries before an Android 12 write counts as refused. */
        private const val MAX_BUSY_RETRIES = 100
    }
}
