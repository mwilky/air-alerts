package com.mwilky.airalerts.band

/** The band's battery as last read, [atMillis] being when. */
data class BandBattery(val percent: Int, val charging: Boolean, val atMillis: Long)

/**
 * The Fitbit Air has no standard Battery Service. Its level comes from `GET /md/606` over the
 * secure session, a protobuf `DeviceInfo.Info` with on-charger in field 10, millivolts in 12 and
 * percent in 13 (from the MIT ThoreKoritzius/fitbit-air library, which matched Google Health's
 * figure).
 */
internal object AirBattery {
    const val PATH = "/md/606"

    private const val ON_CHARGER = 10
    private const val LEVEL = 13

    /** The battery in a `/md/606` reply, or null if the body has no level from 0 to 100. */
    fun parse(body: ByteArray, atMillis: Long): BandBattery? {
        val fields = varints(body) ?: return null
        val level = fields[LEVEL]?.takeIf { it in 0..100 } ?: return null
        return BandBattery(percent = level.toInt(), charging = (fields[ON_CHARGER] ?: 0L) != 0L, atMillis = atMillis)
    }

    /** The top-level varint fields of a protobuf message, skipping the rest; null if it's malformed. */
    private fun varints(body: ByteArray): Map<Int, Long>? {
        val out = mutableMapOf<Int, Long>()
        var i = 0
        fun varint(): Long? {
            var value = 0L
            var shift = 0
            while (i < body.size && shift < 64) {
                val b = body[i++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            return null
        }
        while (i < body.size) {
            val key = varint() ?: return null
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out[field] = varint() ?: return null
                1 -> i += 8
                2 -> {
                    // Read the length first: `i += varint()` would add it to i from before the read.
                    val length = varint()?.toInt()?.takeIf { it >= 0 } ?: return null
                    i += length
                }
                5 -> i += 4
                else -> return null
            }
            if (i > body.size) return null
        }
        return out
    }
}
