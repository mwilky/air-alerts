package com.mwilky.airalerts.band

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpFramingTest {
    private val datagram = Datagram(
        srcAddress = IpFraming.PHONE_ADDRESS,
        dstAddress = IpFraming.BAND_ADDRESS,
        srcPort = IpFraming.DTLS_PORT,
        dstPort = IpFraming.DTLS_PORT,
        payload = byteArrayOf(0x16, 0xFE.toByte(), 0xFD.toByte(), 1, 2, 3)
    )

    @Test
    fun builtPacketMatchesTheCapturedLayout() {
        val p = IpFraming.build(datagram, identification = 7)
        // 45 00 ... 11 ... a9fe0002 a9fe0003 1634 1634 ... 16 fefd (from the published capture)
        assertEquals(0x45, p[0].toInt())
        assertEquals(17, p[9].toInt())
        assertArrayEquals(byteArrayOf(0xA9.toByte(), 0xFE.toByte(), 0, 2, 0xA9.toByte(), 0xFE.toByte(), 0, 3), p.copyOfRange(12, 20))
        assertArrayEquals(byteArrayOf(0x16, 0x34, 0x16, 0x34), p.copyOfRange(20, 24))
        assertEquals(34, p.size)
    }

    @Test
    fun headerChecksumVerifies() {
        val p = IpFraming.build(datagram, identification = 0x1234)
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum)
    }

    @Test
    fun roundTrip() {
        assertEquals(datagram, IpFraming.parse(IpFraming.build(datagram, 1)))
    }

    @Test
    fun parsesCompressedHeadersWithEverythingElided() {
        // Flags 0x8040: compressed, UDP, every other field at its default. Then length and id.
        val payload = byteArrayOf(9, 8, 7)
        val total = 6 + payload.size
        val p = byteArrayOf(0x80.toByte(), 0x40, 0, total.toByte(), 0, 1) + payload
        val d = IpFraming.parse(p)!!
        assertEquals(IpFraming.BAND_ADDRESS, d.srcAddress)
        assertEquals(IpFraming.PHONE_ADDRESS, d.dstAddress)
        assertEquals(IpFraming.DTLS_PORT, d.srcPort)
        assertArrayEquals(payload, d.payload)
    }

    @Test
    fun parsesCompressedHeadersWithTtlAndSourcePort() {
        // Flags: compressed | UDP | TTL | own source port (0x0C00). Bits: TTL 64, port 0x1633.
        val flags = 0x8000 or 0x0040 or 0x0020 or 0x0C00
        val variable = byteArrayOf(64, 0x16, 0x33)
        val payload = byteArrayOf(1)
        val total = 6 + variable.size + payload.size
        val p = byteArrayOf((flags shr 8).toByte(), flags.toByte(), 0, total.toByte(), 0, 0) + variable + payload
        val d = IpFraming.parse(p)!!
        assertEquals(0x1633, d.srcPort)
        assertEquals(IpFraming.DTLS_PORT, d.dstPort)
        assertArrayEquals(payload, d.payload)
    }

    @Test
    fun ignoresNonUdp() {
        val p = IpFraming.build(datagram, 1).also { it[9] = 6 }
        assertNull(IpFraming.parse(p))
    }

    @Test
    fun assemblerSplitsPacketsAcrossFeeds() {
        val one = IpFraming.build(datagram, 1)
        val two = IpFraming.build(datagram.copy(payload = ByteArray(300) { it.toByte() }), 2)
        val stream = one + two
        val assembler = PacketAssembler()
        val out = mutableListOf<ByteArray>()
        stream.toList().chunked(17).forEach { out += assembler.feed(it.toByteArray()) }
        assertEquals(2, out.size)
        assertArrayEquals(one, out[0])
        assertArrayEquals(two, out[1])
    }
}
