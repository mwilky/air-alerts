package com.mwilky.airalerts.band

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirBatteryTest {
    @Test
    fun readsLevelAndChargerAmongOtherFields() {
        val body = byteArrayOf(
            0x0A, 0x03, 0x41, 0x42, 0x43, // field 1, a string to skip
            0x50, 0x01, // field 10 on charger = 1
            0x60, 0xEF.toByte(), 0x1E, // field 12 voltage = 3951
            0x68, 0x37, // field 13 level = 55
            0x75, 1, 2, 3, 4 // field 14, 32-bit, skipped
        )
        assertEquals(BandBattery(percent = 55, charging = true, atMillis = 7), AirBattery.parse(body, 7))
    }

    @Test
    fun notChargingWhenFieldMissing() {
        assertEquals(false, AirBattery.parse(byteArrayOf(0x68, 0x64), 0)?.charging)
    }

    @Test
    fun rejectsMissingOrOutOfRangeLevel() {
        assertNull(AirBattery.parse(byteArrayOf(0x50, 0x00), 0))
        assertNull(AirBattery.parse(byteArrayOf(0x68, 0x65), 0)) // 101
        assertNull(AirBattery.parse(byteArrayOf(0x0A, 0x05, 0x01), 0)) // truncated string
        assertNull(AirBattery.parse(ByteArray(0), 0))
    }

    @Test
    fun getRequestHasNoBody() {
        val token = byteArrayOf(1, 2)
        val bytes = Coap.request(Coap.GET, AirBattery.PATH, 0x0001, token, ByteArray(0))
        val expected = byteArrayOf(0x42, 0x01, 0x00, 0x01) + token +
            byteArrayOf(0xB2.toByte()) + "md".encodeToByteArray() +
            byteArrayOf(0x03) + "606".encodeToByteArray()
        assertArrayEquals(expected, bytes)
    }
}
