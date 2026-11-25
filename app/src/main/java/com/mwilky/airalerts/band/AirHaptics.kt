package com.mwilky.airalerts.band

/** What one buzz feels like. */
enum class BuzzPattern { SINGLE, DOUBLE, LONG }

/** One `POST /settings` body, sent [afterMillis] after the band confirms the write before it. */
internal class HapticWrite(val afterMillis: Long, val body: ByteArray)

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

    /**
     * The writes for one [pattern] that leave [current] in place. Found on the band: each change is
     * felt at the strength it changes to, so a buzz alternates strong and gentle; changes that land
     * while the motor still runs blur into a longer buzz, and more than 6 in one write feel no
     * longer; separate writes need about 500 ms between them to feel apart. Each takes 80–340 ms
     * to be confirmed and a buzz's first write is the slow one, so a gap timed from the first
     * write's send came out shorter by a varying amount; timing it from the confirmation doesn't.
     */
    fun writes(pattern: BuzzPattern, current: Int): List<HapticWrite> = when (pattern) {
        BuzzPattern.SINGLE -> listOf(HapticWrite(0, buzz(current)))
        BuzzPattern.DOUBLE -> listOf(HapticWrite(0, buzz(current)), HapticWrite(DOUBLE_GAP_MS, buzz(current)))
        BuzzPattern.LONG -> listOf(HapticWrite(0, buzz(current) + buzz(current) + buzz(current)))
    }

    /**
     * From the band confirming a repeat's first write to sending its second. The second takes about
     * 100 ms more to land, so the felt gap is near the 700 ms that felt clearest in testing.
     */
    const val DOUBLE_GAP_MS = 600L

    fun other(strength: Int) = if (strength == GENTLE) STRONG else GENTLE

    /** The strength in a read reply, or null if the body isn't exactly one known entry. */
    fun parseStrength(body: ByteArray): Int? = listOf(GENTLE, STRONG).firstOrNull { body.contentEquals(entry(it)) }
}
