// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {
    private val now = 1_791_187_200L
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)

    private fun report(fetchedAt: Long, observedAt: Long = now - 7200): WeatherReport {
        val current = CurrentWeather(
            observedAt, 17.0, null, 800, "Clear", false,
            null, null, null, null, null, null, null, null, null
        )
        return WeatherReport(city, WeatherUnits.METRIC, current, emptyList(), 0, fetchedAt)
    }

    @Test fun missingDataAlwaysAllowsARefresh() {
        assertFalse(RefreshPolicy.isRecent(null, now))
    }

    @Test fun recentDataEndsAtExactlyThirtyMinutes() {
        assertTrue(RefreshPolicy.isRecent(report(now), now))
        assertTrue(RefreshPolicy.isRecent(report(now - 1), now))
        assertTrue(RefreshPolicy.isRecent(report(now - 1799), now))
        assertFalse(RefreshPolicy.isRecent(report(now - 1800), now))
        assertFalse(RefreshPolicy.isRecent(report(now - 1801), now))
    }

    @Test fun oldProviderObservationDoesNotMakeARecentAppFetchStale() {
        assertTrue(RefreshPolicy.isRecent(report(now - 60, observedAt = now - 86400), now))
    }

    @Test fun recentProviderObservationDoesNotMakeAnOldAppFetchRecent() {
        assertFalse(RefreshPolicy.isRecent(report(now - 1800, observedAt = now), now))
        assertFalse(RefreshPolicy.isRecent(report(now - 86400, observedAt = now + 3600), now))
    }

    @Test fun futureFetchesAllowRefreshAfterAClockChange() {
        assertFalse(RefreshPolicy.isRecent(report(now + 1), now))
        assertFalse(RefreshPolicy.isRecent(report(now + 86400), now))
        assertFalse(RefreshPolicy.isRecent(report(Long.MAX_VALUE), now))
    }

    @Test fun invalidNegativeTimestampsNeverSuppressRefresh() {
        assertFalse(RefreshPolicy.isRecent(report(-1), now))
        assertFalse(RefreshPolicy.isRecent(report(Long.MIN_VALUE), now))
        assertFalse(RefreshPolicy.isRecent(report(now), -1))
        assertFalse(RefreshPolicy.isRecent(report(Long.MIN_VALUE), Long.MIN_VALUE))
    }

    @Test fun oldEpochAndLargeTimestampsCannotOverflowIntoARecentAge() {
        assertFalse(RefreshPolicy.isRecent(report(0), now))
        assertFalse(RefreshPolicy.isRecent(report(0), Long.MAX_VALUE))
        assertFalse(RefreshPolicy.isRecent(report(now), Long.MAX_VALUE))
        assertTrue(RefreshPolicy.isRecent(report(Long.MAX_VALUE - 1799), Long.MAX_VALUE))
        assertFalse(RefreshPolicy.isRecent(report(Long.MAX_VALUE - 1800), Long.MAX_VALUE))
    }

    @Test fun selectedIntervalControlsTheExactFreshnessBoundary() {
        for (minutes in listOf(15, 30, 45, 60, 120, 1440)) {
            val seconds = minutes * 60L
            assertTrue(RefreshPolicy.isRecent(report(now - seconds + 1), now, minutes))
            assertFalse(RefreshPolicy.isRecent(report(now - seconds), now, minutes))
        }
        assertFalse(RefreshPolicy.isRecent(report(now - 16 * 60), now, 15))
        assertTrue(RefreshPolicy.isRecent(report(now - 31 * 60), now, 120))
    }

    @Test fun invalidSavedIntervalsStayWithinAndroidPeriodicWorkLimits() {
        assertEquals(15 * 60L, RefreshPolicy.intervalSeconds(Int.MIN_VALUE))
        assertEquals(15 * 60L, RefreshPolicy.intervalSeconds(0))
        assertEquals(1440 * 60L, RefreshPolicy.intervalSeconds(Int.MAX_VALUE))
        assertEquals(15, BackgroundPreferences(intervalMinutes = 1).normalizedIntervalMinutes)
        assertEquals(1440, BackgroundPreferences(intervalMinutes = 2000).normalizedIntervalMinutes)
    }

    @Test fun disablingBackgroundRefreshRemovesItsCadenceInsteadOfSchedulingAnHour() {
        for (minutes in listOf(15, 30, 45, 60, 120, 1440)) {
            assertNull(RefreshPolicy.scheduledMinutes(false, minutes))
            assertEquals(minutes.toLong(), RefreshPolicy.scheduledMinutes(true, minutes))
        }
        val defaults = BackgroundPreferences()
        assertEquals(30L, RefreshPolicy.scheduledMinutes(defaults.enabled, defaults.intervalMinutes))
    }

    @Test fun foregroundWaitFollowsTheAppFetchAndDoesNotFetchImmediatelyOnOpening() {
        assertEquals(30 * 60 * 1000L, RefreshPolicy.foregroundDelayMillis(null, now, 30))
        assertEquals(30 * 60 * 1000L, RefreshPolicy.foregroundDelayMillis(now - 7200, now, 30))
        assertEquals(29 * 60 * 1000L, RefreshPolicy.foregroundDelayMillis(now - 60, now, 30))
        assertEquals(1000L, RefreshPolicy.foregroundDelayMillis(now - 1799, now, 30))
        assertEquals(120 * 60 * 1000L, RefreshPolicy.foregroundDelayMillis(now + 1, now, 120))
        assertEquals(1440 * 60 * 1000L, RefreshPolicy.foregroundDelayMillis(Long.MIN_VALUE, Long.MAX_VALUE, 1440))
    }
}
