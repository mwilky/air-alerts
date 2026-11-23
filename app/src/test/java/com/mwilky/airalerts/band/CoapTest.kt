package com.mwilky.airalerts.band

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoapTest {
    @Test
    fun postSettingsBytes() {
        val token = byteArrayOf(1, 2, 3, 4)
        val body = AirHaptics.buzz(AirHaptics.GENTLE)
        val bytes = Coap.request(Coap.POST, "/settings", 0x1234, token, body)
        val expected = byteArrayOf(0x44, 0x02, 0x12, 0x34) + token +
            byteArrayOf(0xB8.toByte()) + "settings".encodeToByteArray() +
            byteArrayOf(0xFF.toByte()) + body
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun parsesPiggybackedReply() {
        // ACK, 2.04 Changed, same id and token, a Content-Format option, then the body.
        val token = byteArrayOf(9, 9)
        val reply = byteArrayOf(0x62, 0x44, 0x00, 0x05) + token + byteArrayOf(0xC1.toByte(), 0x2A, 0xFF.toByte(), 0x0A, 0x04)
        val m = Coap.parse(reply)!!
        assertEquals(Coap.TYPE_ACK, m.type)
        assertTrue(m.isSuccess)
        assertEquals("2.04", m.codeText)
        assertEquals(5, m.messageId)
        assertArrayEquals(token, m.token)
        assertArrayEquals(byteArrayOf(0x0A, 0x04), m.payload)
    }

    @Test
    fun buzzBodyChangesStrengthThenRestoresIt() {
        assertArrayEquals(
            byteArrayOf(0x0A, 0x04, 0x0A, 0x02, 0x08, 0x02, 0x0A, 0x04, 0x0A, 0x02, 0x08, 0x03),
            AirHaptics.buzz(AirHaptics.STRONG)
        )
        assertEquals(AirHaptics.GENTLE, AirHaptics.parseStrength(AirHaptics.entry(AirHaptics.GENTLE)))
    }
}
