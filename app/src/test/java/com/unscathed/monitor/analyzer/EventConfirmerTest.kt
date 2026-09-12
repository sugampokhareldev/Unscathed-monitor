package com.unscathed.monitor.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventConfirmerTest {
    private val key = "dark_arts_merchant"

    private fun confirmer(
        confirmScans: Int = 2,
        confirmWindowMs: Long = 12_000,
        cooldownMs: Long = 10 * 60_000,
        clearMs: Long = 30_000,
    ) = EventConfirmer(confirmScans, confirmWindowMs, cooldownMs, clearMs)

    @Test
    fun oneSightingIsNeverEnough() {
        val c = confirmer()
        val first = c.sighted(key, 1_000)
        assertTrue(first is EventConfirmer.Outcome.Pending)
        assertEquals(1, (first as EventConfirmer.Outcome.Pending).remaining)
    }

    @Test
    fun aSecondSightingConfirmsIt() {
        val c = confirmer()
        c.sighted(key, 1_000)
        val second = c.sighted(key, 4_000)
        assertTrue(second is EventConfirmer.Outcome.Confirmed)
        assertEquals(2, (second as EventConfirmer.Outcome.Confirmed).sightings)
        assertEquals(1_000, second.firstSeenMs)
    }

    /** The message lingers on screen for many scans; that is still one alert. */
    @Test
    fun theSameAppearanceOnlyAlertsOnce() {
        val c = confirmer()
        c.sighted(key, 1_000)
        assertTrue(c.sighted(key, 4_000) is EventConfirmer.Outcome.Confirmed)
        repeat(8) { i ->
            assertEquals(EventConfirmer.Outcome.Suppressed, c.sighted(key, 7_000 + i * 3_000L))
        }
    }

    /** Once the message has been gone long enough, a genuinely new one can alert again. */
    @Test
    fun aLaterAppearanceAlertsAgain() {
        val c = confirmer(clearMs = 30_000)
        c.sighted(key, 1_000)
        c.sighted(key, 4_000)

        val muchLater = 4_000L + 40_000
        assertTrue(c.sighted(key, muchLater) is EventConfirmer.Outcome.Pending)
        assertTrue(c.sighted(key, muchLater + 3_000) is EventConfirmer.Outcome.Confirmed)
    }

    /** Two sightings hours apart are two misreads, not one message. */
    @Test
    fun sightingsSpreadTooFarApartDoNotConfirm() {
        val c = confirmer(confirmWindowMs = 12_000, clearMs = 30_000)
        c.sighted(key, 1_000)
        // Inside clearMs so it is the same track, but outside the confirm window.
        val late = c.sighted(key, 25_000)
        assertTrue(late is EventConfirmer.Outcome.Pending)
        assertEquals(1, (late as EventConfirmer.Outcome.Pending).sightings)
    }

    @Test
    fun absenceEndsTheAppearance() {
        val c = confirmer(clearMs = 10_000)
        c.sighted(key, 1_000)
        c.sighted(key, 2_000)
        c.absent(key, 20_000)
        assertTrue(c.sighted(key, 21_000) is EventConfirmer.Outcome.Pending)
    }

    @Test
    fun threeScansCanBeRequired() {
        val c = confirmer(confirmScans = 3)
        assertTrue(c.sighted(key, 1_000) is EventConfirmer.Outcome.Pending)
        assertTrue(c.sighted(key, 3_000) is EventConfirmer.Outcome.Pending)
        assertTrue(c.sighted(key, 5_000) is EventConfirmer.Outcome.Confirmed)
    }

    @Test
    fun theCooldownIsReportable() {
        val c = confirmer(cooldownMs = 60_000)
        c.sighted(key, 1_000)
        c.sighted(key, 2_000)
        assertEquals(60_000, c.cooldownRemainingMs(key, 2_000))
        assertEquals(30_000, c.cooldownRemainingMs(key, 32_000))
        assertEquals(0, c.cooldownRemainingMs(key, 90_000))
    }

    @Test
    fun differentEventsDoNotBlockEachOther() {
        val c = confirmer()
        c.sighted("a", 1_000)
        c.sighted("b", 1_000)
        assertTrue(c.sighted("a", 3_000) is EventConfirmer.Outcome.Confirmed)
        assertTrue(c.sighted("b", 3_000) is EventConfirmer.Outcome.Confirmed)
    }
}
