// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class RainAlertPolicyTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-05T09:00:00Z").epochSecond
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private fun slot(time: Long = now + 3600, chance: Double? = 50.0, condition: Int? = 500, rain: Double = 1.0) =
        ForecastSlot(time, 10.0, 8.0, 12.0, condition, "Rain", false, chance, rain, 0.0)
    private fun report(vararg slots: ForecastSlot): WeatherReport {
        val current = CurrentWeather(now - 7200, 10.0, 10.0, 500, "Rain", false, null, null, null, null, null, null, null, null, null)
        return WeatherReport(city, WeatherUnits.METRIC, current, slots.toList(), 0, now)
    }
    private fun next(report: WeatherReport, checkedAt: Long = now, sent: Set<String> = emptySet()) =
        RainAlertPolicy.next(report, checkedAt, sent, zone)

    @Test fun thresholdIsStrictlyAboveThirtyPercent() {
        assertNull(next(report(slot(chance = 30.0))))
        assertNull(next(report(slot(chance = 0.0))))
        assertNotNull(next(report(slot(chance = 30.1))))
        assertNull(next(report(slot(chance = null))))
        assertNull(next(report(slot(chance = Double.NaN))))
    }

    @Test fun onlyTheNextThreeHoursAreEligibleEvenWithAnOldObservation() {
        assertNull(next(report(slot(time = now))))
        assertNull(next(report(slot(time = now - 1))))
        assertNotNull(next(report(slot(time = now + 10800))))
        assertNull(next(report(slot(time = now + 10801))))
        assertNull(next(report(slot(time = now + 21600))))
    }

    @Test fun probabilityAndProviderHeavyRainSelectAppropriateWording() {
        assertEquals(RainLikelihood.POSSIBLE, next(report(slot(chance = 69.9)))!!.likelihood)
        assertEquals(RainLikelihood.EXPECTED, next(report(slot(chance = 70.0)))!!.likelihood)
        listOf(202, 502, 503, 504, 522).forEach { code ->
            assertEquals(RainLikelihood.HEAVY, next(report(slot(chance = 40.0, condition = code)))!!.likelihood)
        }
        assertNull(next(report(slot(chance = 20.0, condition = 502))))
    }

    @Test fun snowAndDryConditionsDoNotGenerateRainAlerts() {
        listOf(600, 601, 602, 800, 804, 211).forEach { code ->
            assertNull(next(report(slot(chance = 100.0, condition = code, rain = 0.0))))
        }
        assertNotNull(next(report(slot(condition = 804, rain = 0.2))))
        assertNotNull(next(report(slot(condition = 301, rain = 0.0))))
    }

    @Test fun earliestEligiblePeriodWinsRatherThanTheWettestOfTheDay() {
        val wetLater = slot(now + 7200, 100.0, 502)
        val near = slot(now + 3600, 40.0)
        assertEquals(near.timestamp, next(report(wetLater, near))!!.timestamp)
    }

    @Test fun deliveryDuringQuietHoursIsSuppressed() {
        for (hour in listOf(0, 1, 7, 22, 23)) {
            val checked = Instant.parse("2026-10-05T${hour.toString().padStart(2, '0')}:00:00Z").epochSecond
            assertNull(next(report(slot(time = checked + 3600)), checked))
        }
        val eight = Instant.parse("2026-10-05T08:00:00Z").epochSecond
        assertNotNull(next(report(slot(time = eight + 3600)), eight))
    }

    @Test fun forecastDuringQuietHoursIsSuppressedBeforeBedtime() {
        val twentyOne = Instant.parse("2026-10-05T21:00:00Z").epochSecond
        assertNull(next(report(slot(time = twentyOne + 3600)), twentyOne))
        assertNotNull(next(report(slot(time = twentyOne + 1800)), twentyOne))
    }

    @Test fun phoneTimezoneControlsDeliveryQuietHours() {
        val result = RainAlertPolicy.next(report(slot()), now, emptySet(), ZoneId.of("Pacific/Honolulu"))
        assertNull(result) // 23:00 on the phone, even though the forecast city is in daytime.
    }

    @Test fun forecastCityQuietHoursAlsoApplyForRemoteLocations() {
        assertNull(next(report(slot()).copy(offset = -8 * 3600)))
    }
    @Test fun oneCallCityTimezoneControlsQuietHoursInsteadOfTheSnapshotOffset() {
        val checked = Instant.parse("2026-10-05T19:30:00Z").epochSecond
        val weather = report(slot(time = checked + 3600)).copy(zoneId = "Europe/Kyiv", offset = 0, forecastStepSeconds = 3600)
        assertNull(next(weather, checked)) // 23:30 in Kyiv, although the stale UTC offset suggests 20:30.
    }
    @Test fun consecutiveHourlyRainGeneratesOneAlertForTheWholeEpisode() {
        val weather = report(slot(now + 3600), slot(now + 7200), slot(now + 14400)).copy(forecastStepSeconds = 3600)
        val first = next(weather)!!
        assertEquals(now + 3600, first.timestamp)
        assertEquals(setOf("${city.identity}|${now + 3600}", "${city.identity}|${now + 7200}"), first.identities(city))
        assertNull(next(weather, sent = setOf(first.identity(city))))
        assertNull(next(weather, now + 1800, first.identities(city)))
        // The missing third hour breaks continuity; this is another spell once it enters the horizon.
        assertEquals(now + 14400, next(weather, now + 3600, first.identities(city))!!.timestamp)
    }
    @Test fun elapsedQualifyingHourPreservesEpisodeDeduplicationWithLegacyHistoryKeys() {
        val earlier = now - 1800
        val weather = report(slot(earlier), slot(now + 1800), slot(now + 5400)).copy(forecastStepSeconds = 3600)
        val legacy = setOf("${city.identity}|$earlier")
        assertNull(next(weather, sent = legacy))
        assertEquals(setOf(earlier, now + 1800, now + 5400).mapTo(mutableSetOf()) { "${city.identity}|$it" },
            RainAlertPolicy.rememberEpisodes(weather, now, legacy))
    }
    @Test fun dryHourStartsAnotherRainEpisodeEvenWhenTheEarlierOneWasAlreadyAlerted() {
        val dry = slot(now + 7200, chance = 0.0, condition = 800, rain = 0.0)
        val weather = report(slot(now + 3600), dry, slot(now + 10800)).copy(forecastStepSeconds = 3600)
        val first = next(weather)!!
        assertEquals(setOf(first.identity(city)), first.identities(city))
        assertEquals(now + 10800, next(weather, sent = first.identities(city))!!.timestamp)
        val history = RainAlertPolicy.rememberEpisodes(weather, now, first.identities(city))
        assertFalse("${city.identity}|${now + 10800}" in history)
    }
    @Test fun slidingHourlyFetchesRenewAnAlertedSpellWithoutUnboundedHistoryGrowth() {
        fun window(hour: Int) = report(*(hour until hour + 48).map { slot(now + it * 3600L) }.toTypedArray())
            .copy(forecastStepSeconds = 3600)
        var history = next(window(0))!!.identities(city)
        for (hour in 1..72) {
            val checked = now + hour * 3600L
            val weather = window(hour)
            history = RainAlertPolicy.rememberEpisodes(weather, checked, history)
            assertNull(next(weather, checked, history))
            assertTrue("${city.identity}|${now + (hour + 47) * 3600L}" in history)
            assertTrue(history.size <= 73)
            assertTrue(history.all {
                val timestamp = it.substringAfterLast('|').toLong()
                timestamp >= checked - 24 * 3600 && timestamp <= checked + 48 * 3600
            })
        }
    }

    @Test fun repeatedChecksAndProbabilityOrUnitChangesDoNotRepeatTheSamePeriod() {
        val initial = report(slot(chance = 50.0))
        val sent = setOf(next(initial)!!.identity(city))
        assertNull(next(initial, now + 1800, sent))
        val changed = report(slot(chance = 90.0, condition = 502)).copy(units = WeatherUnits.IMPERIAL)
        assertNull(next(changed, now + 1800, sent.toSet()))
    }

    @Test fun differentPeriodsAndCitiesHaveSeparateIdentities() {
        val initial = report(slot())
        val sent = setOf(next(initial)!!.identity(city))
        assertNotNull(next(report(slot(time = now + 10800)), sent = sent))
        assertNotNull(next(initial.copy(location = city.copy(lon = 31.0)), sent = sent))
    }

    @Test fun historyPrunesOldAndCorruptEntriesButKeepsUpcomingSentPeriods() {
        val upcoming = "${city.identity}|${now + 3600}"
        val endOfWindow = "${city.identity}|${now + 48 * 3600}"
        val beyondWindow = "${city.identity}|${now + 48 * 3600 + 1}"
        val old = "${city.identity}|${now - 25 * 3600}"
        assertEquals(setOf(upcoming, endOfWindow),
            RainAlertPolicy.pruneHistory(setOf(upcoming, endOfWindow, beyondWindow, old, "invalid"), now))
    }

    @Test fun messagesUseForecastCityTimeAndTheSelectedClockStyle() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val weather = report(slot()).copy(offset = 3 * 3600)
            assertEquals("Rain possible around 01:00 PM.", RainAlert(now + 3600, RainLikelihood.POSSIBLE).message(weather, false))
            assertEquals("Rain expected around 13:00.", RainAlert(now + 3600, RainLikelihood.EXPECTED).message(weather, true))
            assertEquals("Heavy rain possible around 01:00 PM.", RainAlert(now + 3600, RainLikelihood.HEAVY).message(weather, false))
        } finally { Locale.setDefault(previous) }
    }
}
