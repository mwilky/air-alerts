package com.mwilky.airalerts

import com.mwilky.airalerts.band.AirHaptics
import com.mwilky.airalerts.band.BuzzPattern
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuzzPatternTest {
    @Test
    fun eachPatternsWritesLeaveTheWearersStrength() {
        val gentle = AirHaptics.GENTLE
        val single = AirHaptics.writes(BuzzPattern.SINGLE, gentle)
        assertEquals(listOf(0L), single.map { it.afterMillis })
        assertArrayEquals(AirHaptics.buzz(gentle), single[0].body)

        val double = AirHaptics.writes(BuzzPattern.DOUBLE, gentle)
        assertEquals(listOf(0L, AirHaptics.DOUBLE_GAP_MS), double.map { it.afterMillis })
        double.forEach { assertArrayEquals(AirHaptics.buzz(gentle), it.body) }

        // Six changes in one write, ending on the wearer's own strength.
        val long = AirHaptics.writes(BuzzPattern.LONG, gentle).single()
        assertEquals(0L, long.afterMillis)
        assertEquals(6 * AirHaptics.entry(gentle).size, long.body.size)
        assertArrayEquals(AirHaptics.entry(gentle), long.body.copyOfRange(long.body.size - 6, long.body.size))
    }

    @Test
    fun aRulesPatternSurvivesJsonAndOlderRulesFollowTheSettingsTab() {
        val c = RuleConditions(pattern = BuzzPattern.LONG)
        assertEquals(c, RuleConditions.fromJson(c.toJson().toString()))
        assertNull(RuleConditions.fromJson("""{"dnd":"SKIP","quiet":"INHERIT"}""").pattern)
        val rules = listOf(ContactRule("a", "Mum", conditions = c))
        assertEquals(rules, RuleJson.decodeContacts(RuleJson.encodeContacts(rules)))
    }

    @Test
    fun theMatchUsesItsOwnPatternElseTheSettingsTabs() {
        val settings = AlertSettings(buzzPattern = BuzzPattern.DOUBLE)
        assertEquals(BuzzPattern.DOUBLE, RuleMatchResult(true, RuleConditions()).pattern(settings))
        assertEquals(BuzzPattern.LONG, RuleMatchResult(true, RuleConditions(pattern = BuzzPattern.LONG)).pattern(settings))
    }

    @Test
    fun ringingCallsKeepTheirOneSecondGapUntilChanged() {
        assertEquals(com.mwilky.airalerts.band.AirBand.RING_GAP_MS, AlertSettings().ringSpeed.gapMillis)
        assertEquals(listOf(1_000L, 1_500L, 2_000L), RingSpeed.entries.map { it.gapMillis })
    }

    @Test
    fun aSharedBuzzTakesTheRingingCallsPatternElseTheLongest() {
        val single = AlertBuzzer.Trigger(AlertKind.NOTIFICATION, BuzzPattern.SINGLE)
        val long = AlertBuzzer.Trigger(AlertKind.NOTIFICATION, BuzzPattern.LONG)
        val double = AlertBuzzer.Trigger(AlertKind.NOTIFICATION, BuzzPattern.DOUBLE)
        val call = AlertBuzzer.Trigger(AlertKind.CALL, BuzzPattern.SINGLE, ringing = MutableStateFlow(true))
        assertEquals(BuzzPattern.LONG, AlertBuzzer.patternFor(listOf(single, long, double)))
        assertEquals(BuzzPattern.SINGLE, AlertBuzzer.patternFor(listOf(long, call)))
    }
}
