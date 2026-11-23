package com.mwilky.airalerts.band

/**
 * Bodies for the Fitbit Air's CoAP `/settings` resource, which holds its buzz strength.
 *
 * The band has no "buzz" command. It buzzes once whenever its strength setting changes, so one
 * message that sets the other strength and then the wearer's own buzzes once and keeps their
 * setting. Each entry is `Setting { haptics { intensity } }`, with intensity 2 (gentle) or 3 (strong).
 *
 * Resource and bodies as found by the MIT-licensed fitbit-air library
 * (github.com/ThoreKoritzius/fitbit-air, Copyright (c) 2026 Thore Koritzius).
 */
internal object AirHaptics {
    const val PATH = "/settings"
    const val GENTLE = 2
    const val STRONG = 3

    /** `PUT /settings` with this body answers with the current strength entry. */
    val READ_REQUEST = byteArrayOf(0x0A, 0x01, 0x01)

    fun entry(strength: Int): ByteArray {
        require(strength == GENTLE || strength == STRONG) { "Strength must be 2 or 3, was $strength" }
        return byteArrayOf(0x0A, 0x04, 0x0A, 0x02, 0x08, strength.toByte())
    }

    /** `POST /settings` body for one buzz that leaves [current] in place. */
    fun buzz(current: Int): ByteArray = entry(other(current)) + entry(current)

    fun other(strength: Int) = if (strength == GENTLE) STRONG else GENTLE

    /** The strength in a read reply, or null if the body isn't exactly one known entry. */
    fun parseStrength(body: ByteArray): Int? = listOf(GENTLE, STRONG).firstOrNull { body.contentEquals(entry(it)) }
}
