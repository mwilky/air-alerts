package com.mwilky.airalerts.band

/**
 * IPv4 + UDP datagrams carried back to back on the Gattlink stream, as Golden Gate frames them
 * (`xp/protocols/gg_ipv4_protocol.c`, Apache-2.0). We send plain IPv4. The band may send either
 * plain IPv4 or Golden Gate's compressed headers, which set the top bit of the first byte; both
 * keep the packet's total length in bytes 2-3, which is how packets are split out of the stream.
 */
internal data class Datagram(
    val srcAddress: Int,
    val dstAddress: Int,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?) = other is Datagram &&
        srcAddress == other.srcAddress && dstAddress == other.dstAddress &&
        srcPort == other.srcPort && dstPort == other.dstPort && payload.contentEquals(other.payload)

    override fun hashCode() = 31 * srcPort + payload.contentHashCode()
}

internal object IpFraming {
    /** Golden Gate's link-local defaults: the hub (phone) and the node (band). */
    const val PHONE_ADDRESS = 0xA9FE0002.toInt() // 169.254.0.2
    const val BAND_ADDRESS = 0xA9FE0003.toInt()  // 169.254.0.3
    const val DTLS_PORT = 5684

    private const val PROTOCOL_UDP = 17
    private const val IP_HEADER_SIZE = 20
    private const val UDP_HEADER_SIZE = 8
    private const val DEFAULT_TTL = 64

    fun build(datagram: Datagram, identification: Int): ByteArray {
        val udpLength = UDP_HEADER_SIZE + datagram.payload.size
        val total = IP_HEADER_SIZE + udpLength
        val p = ByteArray(total)
        p[0] = 0x45
        p.putShort(2, total)
        p.putShort(4, identification)
        p[8] = DEFAULT_TTL.toByte()
        p[9] = PROTOCOL_UDP.toByte()
        p.putInt(12, datagram.srcAddress)
        p.putInt(16, datagram.dstAddress)
        p.putShort(10, checksum(p, 0, IP_HEADER_SIZE).inv() and 0xFFFF)
        p.putShort(20, datagram.srcPort)
        p.putShort(22, datagram.dstPort)
        p.putShort(24, udpLength)
        datagram.payload.copyInto(p, IP_HEADER_SIZE + UDP_HEADER_SIZE)
        p.putShort(26, udpChecksum(datagram, p, IP_HEADER_SIZE, udpLength))
        return p
    }

    /** Parses one whole packet, plain or compressed. Null for anything that isn't UDP over IPv4. */
    fun parse(packet: ByteArray): Datagram? =
        if (packet.isNotEmpty() && packet[0].toInt() and 0x80 != 0) parseCompressed(packet) else parsePlain(packet)

    private fun parsePlain(p: ByteArray): Datagram? {
        if (p.size < IP_HEADER_SIZE || p[0].toInt() shr 4 != 4) return null
        val ihl = (p[0].toInt() and 0x0F) * 4
        val total = p.getShort(2)
        if (p[9].toInt() != PROTOCOL_UDP || ihl < IP_HEADER_SIZE || total > p.size || total < ihl + UDP_HEADER_SIZE) return null
        val udpLength = p.getShort(ihl + 4).coerceIn(UDP_HEADER_SIZE, total - ihl)
        return Datagram(
            srcAddress = p.getInt(12),
            dstAddress = p.getInt(16),
            srcPort = p.getShort(ihl),
            dstPort = p.getShort(ihl + 2),
            payload = p.copyOfRange(ihl + UDP_HEADER_SIZE, ihl + udpLength)
        )
    }

    /**
     * Golden Gate's compressed header: 16 flag bits (top bit set), the total length, the IP
     * identification, then only the fields that differ from their defaults, packed as bits.
     * Elided addresses and ports default to the band sending to the phone on [DTLS_PORT].
     */
    private fun parseCompressed(p: ByteArray): Datagram? {
        if (p.size < 6) return null
        val flags = p.getShort(0)
        val bits = BitReader(p, 6)
        val ihl = if (flags and 0x0001 != 0) bits.read(4) else 5
        if (flags and 0x0002 != 0) bits.read(6) // DSCP
        if (flags and 0x0004 != 0) bits.read(2) // ECN
        if (flags and 0x0008 != 0) bits.read(3) // flags
        if (flags and 0x0010 != 0) bits.read(13) // fragment offset
        if (flags and 0x0020 != 0) bits.read(8) // TTL
        val protocol = when (flags and 0x00C0) {
            0x0040 -> PROTOCOL_UDP
            0x00C0 -> bits.read(8)
            else -> return null // TCP or ICMP
        }
        if (protocol != PROTOCOL_UDP || ihl < 5) return null
        val src = if (flags and 0x0100 != 0) bits.read(32) else BAND_ADDRESS
        val dst = if (flags and 0x0200 != 0) bits.read(32) else PHONE_ADDRESS
        repeat((ihl - 5) * 4) { bits.read(8) } // options
        val srcPort = if (flags and 0x0C00 == 0x0C00) bits.read(16) else DTLS_PORT
        val dstPort = if (flags and 0x3000 == 0x3000) bits.read(16) else DTLS_PORT
        if (flags and 0x4000 != 0) bits.read(16) // UDP length; the packet length says the same
        val headerSize = 6 + (bits.position + 7) / 8
        if (headerSize > p.size) return null
        return Datagram(src, dst, srcPort, dstPort, p.copyOfRange(headerSize, p.size))
    }

    /** One's-complement sum of 16-bit words, folded, before inversion. */
    private fun checksum(data: ByteArray, from: Int, length: Int, initial: Long = 0): Int {
        var sum = initial
        var i = from
        while (i + 1 < from + length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < from + length) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt()
    }

    private fun udpChecksum(d: Datagram, p: ByteArray, udpOffset: Int, udpLength: Int): Int {
        val pseudo = (d.srcAddress.toLong() ushr 16) + (d.srcAddress and 0xFFFF) +
            (d.dstAddress.toLong() ushr 16) + (d.dstAddress and 0xFFFF) + PROTOCOL_UDP + udpLength
        val sum = checksum(p, udpOffset, udpLength, pseudo).inv() and 0xFFFF
        return if (sum == 0) 0xFFFF else sum
    }

    private class BitReader(private val data: ByteArray, private val start: Int) {
        var position = 0
            private set

        fun read(count: Int): Int {
            var value = 0L
            repeat(count) {
                val index = start + position / 8
                val bit = if (index < data.size) (data[index].toInt() shr (7 - position % 8)) and 1 else 0
                value = (value shl 1) or bit.toLong()
                position++
            }
            return value.toInt()
        }
    }
}

/** Splits the Gattlink byte stream back into whole packets. */
internal class PacketAssembler(private val maxPacketSize: Int = 1280) {
    private var buffer = ByteArray(0)

    fun feed(data: ByteArray): List<ByteArray> {
        buffer += data
        val packets = mutableListOf<ByteArray>()
        while (buffer.size >= 4) {
            val size = buffer.getShort(2)
            if (size < 4 || size > maxPacketSize) {
                // Out of step with the stream; Golden Gate drops it too and waits for a reset.
                buffer = ByteArray(0)
                break
            }
            if (buffer.size < size) break
            packets += buffer.copyOfRange(0, size)
            buffer = buffer.copyOfRange(size, buffer.size)
        }
        return packets
    }

    fun reset() {
        buffer = ByteArray(0)
    }
}

private fun ByteArray.putShort(at: Int, value: Int) {
    this[at] = (value shr 8).toByte()
    this[at + 1] = value.toByte()
}

private fun ByteArray.putInt(at: Int, value: Int) {
    putShort(at, value ushr 16)
    putShort(at + 2, value)
}

private fun ByteArray.getShort(at: Int) = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

private fun ByteArray.getInt(at: Int) = (getShort(at) shl 16) or getShort(at + 2)
