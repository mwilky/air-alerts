package com.mwilky.airalerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trial arithmetic on [Licensing.Status]: days left, the moment it ends, and that a purchase or a
 * trial that hasn't started always lets buzzes through.
 */
class LicensingStatusTest {
    private val day = 24 * 60 * 60 * 1000L
    private val start = 1_700_000_000_000L

    private fun status(now: Long, purchased: Boolean = false, trialStart: Long? = start) =
        Licensing.Status(purchased = purchased, trialStartMillis = trialStart, now = now, priceText = null)

    @Test
    fun freshTrialHasThirtyDaysLeft() {
        val s = status(now = start)
        assertEquals(30, Licensing.TRIAL_DAYS)
        assertEquals(30, s.trialDaysLeft)
        assertFalse(s.trialExpired)
        assertTrue(s.entitled)
    }

    @Test
    fun partialLastDayStillReadsAsOneDay() {
        val s = status(now = start + Licensing.TRIAL_DAYS * day - 1)
        assertEquals(1, s.trialDaysLeft)
        assertTrue(s.entitled)
    }

    @Test
    fun trialEndsExactlyAfterThirtyDays() {
        val s = status(now = start + Licensing.TRIAL_DAYS * day)
        assertEquals(0, s.trialDaysLeft)
        assertTrue(s.trialExpired)
        assertFalse(s.entitled)
    }

    @Test
    fun purchaseUnlocksAfterTheTrial() {
        val s = status(now = start + 90 * day, purchased = true)
        assertTrue(s.trialExpired)
        assertTrue(s.entitled)
    }

    @Test
    fun trialNotStartedYetBuzzes() {
        val s = status(now = start, trialStart = null)
        assertEquals(Licensing.TRIAL_DAYS, s.trialDaysLeft)
        assertFalse(s.trialExpired)
        assertTrue(s.entitled)
    }

    @Test
    fun clockSetBeforeTheStartNeverShowsMoreThanThirtyDays() {
        assertEquals(Licensing.TRIAL_DAYS, status(now = start - 5 * day).trialDaysLeft)
    }
}
