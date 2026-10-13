// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class RainForecastPolicyTest {
    private val now = Instant.parse("2026-10-05T09:00:00Z").epochSecond
    private val hour = 3600L
    private val zone = ZoneId.of("UTC")
    private val location = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private fun wet(time: Long, chance: Double? = 60.0, code: Int? = 500, rain: Double = 0.5) =
        ForecastSlot(time, 10.0, 8.0, 12.0, code, "Rain", false, chance, rain, 0.0)
    private fun dry(time: Long) = wet(time, chance = 0.0, code = 800, rain = 0.0)
    private fun report(vararg slots: ForecastSlot, checkedAt: Long = now): WeatherReport {
        val raining = slots.any { it.timestamp <= checkedAt && it.timestamp + hour > checkedAt && it.condition in 300..599 }
        return WeatherReport(
        location, WeatherUnits.METRIC,
        CurrentWeather(checkedAt, 10.0, 10.0, if (raining) 500 else 800, if (raining) "Rain" else "Clear sky",
            false, null, null, null, null, null, null, null, null, null),
        slots.toList(), 0, checkedAt, forecastStepSeconds = 3600
        )
    }
    private fun evaluate(weather: WeatherReport, checkedAt: Long = now, state: RainAlertState = RainAlertState(),
        quiet: QuietHoursPreferences = QuietHoursPreferences(), phoneZone: ZoneId = zone,
        legacy: Set<String> = emptySet()) = RainForecastPolicy.evaluate(weather, checkedAt, state, quiet, phoneZone, legacy)
    private fun initial() = evaluate(report(wet(now + 2 * hour), dry(now + 3 * hour), dry(now + 4 * hour)))

    @Test fun defaultQuietHoursUseMinutePrecisionAndAnExclusiveEnd() {
        val quiet = QuietHoursPreferences()
        assertFalse(quiet.contains(LocalTime.of(21, 59)))
        assertTrue(quiet.contains(LocalTime.of(22, 0)))
        assertTrue(quiet.contains(LocalTime.of(7, 59)))
        assertFalse(quiet.contains(LocalTime.of(8, 0)))
        assertFalse(quiet.copy(enabled = false).contains(LocalTime.MIDNIGHT))
    }

    @Test fun quietHoursSupportDaytimeIntervalsAndEqualTimesMeanAllDay() {
        val lunch = QuietHoursPreferences(startMinutes = 12 * 60, endMinutes = 14 * 60)
        assertFalse(lunch.contains(LocalTime.of(11, 59)))
        assertTrue(lunch.contains(LocalTime.NOON))
        assertFalse(lunch.contains(LocalTime.of(14, 0)))
        assertTrue(lunch.copy(endMinutes = 12 * 60).contains(LocalTime.MIDNIGHT))
        assertFalse(lunch.copy(enabled = false, endMinutes = 12 * 60).contains(LocalTime.NOON))
        assertEquals(QuietHoursPreferences(startMinutes = 0, endMinutes = 1439),
            QuietHoursPreferences(startMinutes = -1, endMinutes = 1440).normalized())
    }

    @Test fun deliveryQuietHoursFollowThePhoneRatherThanForecastCity() {
        val weather = report(wet(now + hour)).copy(zoneId = "Pacific/Honolulu", offset = -10 * 3600)
        assertNotNull(evaluate(weather).notification)
        assertNull(evaluate(weather, phoneZone = ZoneId.of("Pacific/Honolulu")).notification)
        assertNotNull(evaluate(weather, quiet = QuietHoursPreferences(enabled = false),
            phoneZone = ZoneId.of("Pacific/Honolulu")).notification)
    }

    @Test fun futureRainDuringQuietHoursCanBeReportedBeforeQuietHoursBegin() {
        val checked = Instant.parse("2026-10-05T21:00:00Z").epochSecond
        assertNotNull(evaluate(report(wet(checked + 2 * hour), checkedAt = checked), checked).notification)
    }

    @Test fun quietHoursFollowBothOccurrencesOfTheRepeatedDstHour() {
        val berlin = ZoneId.of("Europe/Berlin")
        val quiet = QuietHoursPreferences(startMinutes = 2 * 60, endMinutes = 3 * 60)
        assertTrue(quiet.isQuiet(Instant.parse("2026-10-25T00:30:00Z").epochSecond, berlin))
        assertTrue(quiet.isQuiet(Instant.parse("2026-10-25T01:30:00Z").epochSecond, berlin))
        assertFalse(quiet.isQuiet(Instant.parse("2026-10-25T02:00:00Z").epochSecond, berlin))
    }

    @Test fun quietHoursHandleTheSpringClockJumpWithoutSchedulingImaginaryTimes() {
        val berlin = ZoneId.of("Europe/Berlin")
        val quiet = QuietHoursPreferences(startMinutes = 90, endMinutes = 180)
        assertTrue(quiet.isQuiet(Instant.parse("2026-03-29T00:45:00Z").epochSecond, berlin))
        assertFalse(quiet.isQuiet(Instant.parse("2026-03-29T01:00:00Z").epochSecond, berlin))
    }

    @Test fun initialWarningsStillRequireRainAboveThirtyPercentWithinThreeHours() {
        for (chance in listOf(null, Double.NaN, 0.0, 30.0, 101.0))
            assertNull(evaluate(report(wet(now + hour, chance))).notification)
        assertNotNull(evaluate(report(wet(now + 3 * hour, chance = 30.1))).notification)
        assertNull(evaluate(report(wet(now + 3 * hour + 1))).notification)
        assertNull(evaluate(report(wet(now))).notification)
        assertNull(evaluate(report(wet(now - hour))).notification)
        assertNull(evaluate(report(dry(now + hour).copy(precipitation = 100.0))).notification)
    }

    @Test fun anInitialWarningIncludesAConfirmedApproximateEndOnlyOnce() {
        val weather = report(wet(now + hour), wet(now + 2 * hour), dry(now + 3 * hour), dry(now + 4 * hour))
        val first = evaluate(weather)
        assertEquals(RainNoticeKind.START, first.notification!!.kind)
        assertEquals(now + 3 * hour, first.notification!!.end)
        assertTrue(first.state.events.single().endingNotified)
        assertNull(evaluate(weather, now + hour, first.state).notification)
        assertNull(evaluate(weather, now + 2 * hour, first.state).notification)
    }

    @Test fun oneDrySampleIsNotEnoughToEstimateAnEnd() {
        assertNull(evaluate(report(wet(now + hour), dry(now + 2 * hour))).notification!!.end)
    }

    @Test fun unknownSlotsAndMissingCoverageCannotBeTreatedAsDryWeather() {
        val middle = dry(now + 2 * hour)
        for (unknown in listOf(middle.copy(precipitation = null), middle.copy(condition = null),
            middle.copy(condition = 699), middle.copy(precipitation = Double.NaN), middle.copy(rainMm = Double.NaN),
            middle.copy(rainMm = -1.0))) {
            assertNull(evaluate(report(wet(now + hour), unknown, dry(now + 3 * hour))).notification!!.end)
        }
        assertNull(evaluate(report(wet(now + hour), dry(now + 3 * hour), dry(now + 4 * hour))).notification!!.end)
        assertNull(evaluate(report(wet(now + hour), dry(now + 2 * hour), dry(now + 4 * hour))).notification!!.end)
        assertNull(evaluate(report(wet(now + hour), wet(now + 2 * hour, chance = 20.0),
            dry(now + 3 * hour))).notification!!.end)
    }

    @Test fun legacyThreeHourForecastsCanGiveAnApproximateEndWithCompleteCoverage() {
        val weather = report(wet(now + 3 * hour), dry(now + 6 * hour), dry(now + 9 * hour))
            .copy(forecastStepSeconds = 10800)
        assertEquals(now + 6 * hour, evaluate(weather).notification!!.end)
    }

    @Test fun aNewlyKnownEndIsReportedAfterTheCooldownWhileRainIsNearOrOngoing() {
        val initial = evaluate(report(wet(now + hour), wet(now + 2 * hour)))
        val knownEnd = report(wet(now + hour), wet(now + 2 * hour), dry(now + 3 * hour), dry(now + 4 * hour),
            checkedAt = now + hour)
        assertNull(evaluate(knownEnd, now + hour - 1, initial.state).notification)
        val ending = evaluate(knownEnd, now + hour, initial.state)
        assertEquals(RainNoticeKind.ENDING, ending.notification!!.kind)
        assertEquals(now + 3 * hour, ending.notification!!.end)
        assertNull(evaluate(knownEnd, now + 2 * hour, ending.state).notification)
    }

    @Test fun enablingAlertsDuringCurrentRainCanReportItsReliableEndWithoutAnEarlierWarning() {
        val weather = report(wet(now), dry(now + hour), dry(now + 2 * hour))
        val ending = evaluate(weather)
        assertEquals(RainNoticeKind.ENDING, ending.notification!!.kind)
        assertEquals(now + hour, ending.notification!!.end)
        assertNull(evaluate(weather, now + 60, ending.state).notification)
        assertNull(evaluate(weather.copy(current = weather.current.copy(condition = 800))).notification)
        assertNull(evaluate(weather.copy(current = weather.current.copy(condition = null))).notification)
        assertNull(evaluate(weather.copy(current = weather.current.copy(timestamp = now - 2 * hour))).notification)
    }

    @Test fun aDistantNewEndingIsNotSentUntilTheRainIsNear() {
        val initial = evaluate(report(wet(now + 3 * hour)))
        val knownEnd = report(wet(now + 3 * hour), wet(now + 4 * hour), wet(now + 5 * hour),
            dry(now + 6 * hour), dry(now + 7 * hour))
        assertNull(evaluate(knownEnd, now + hour, initial.state).notification)
        assertEquals(RainNoticeKind.ENDING, evaluate(knownEnd.copy(current = knownEnd.current.copy(timestamp = now + 3 * hour, condition = 500)),
            now + 3 * hour, initial.state).notification!!.kind)
    }

    @Test fun postponedRainUpdatesTheCommunicatedForecastAfterOneHour() {
        val initial = initial()
        val shifted = report(dry(now + 2 * hour), dry(now + 3 * hour), wet(now + 4 * hour),
            dry(now + 5 * hour), dry(now + 6 * hour), checkedAt = now + hour)
        assertNull(evaluate(shifted, now + hour - 1, initial.state).notification)
        val changed = evaluate(shifted, now + hour, initial.state)
        assertEquals(RainNoticeKind.CHANGED, changed.notification!!.kind)
        assertEquals(now + 2 * hour, changed.notification!!.previousStart)
        assertEquals(now + 4 * hour, changed.state.events.single().start)
        assertTrue(changed.state.events.single().endingNotified)
        assertNull(evaluate(shifted, now + 2 * hour, changed.state).notification)
    }

    @Test fun movingAnEventIntoThePastDoesNotSendAStartTimeUpdate() {
        val initial = initial()
        val shifted = report(wet(now + hour), wet(now + 2 * hour), dry(now + 3 * hour), dry(now + 4 * hour))
        assertNull(evaluate(shifted, now + hour, initial.state).notification)
    }

    @Test fun smallChangesAccumulateAgainstTheCommunicatedForecastRatherThanEveryFetch() {
        val initial = initial()
        fun shifted(minutes: Long) = report(dry(now + 2 * hour), wet(now + 2 * hour + minutes * 60),
            dry(now + 3 * hour + minutes * 60), dry(now + 4 * hour + minutes * 60))
        val minor = evaluate(shifted(30), now + hour, initial.state)
        assertNull(minor.notification)
        assertEquals(now + 2 * hour, minor.state.events.single().start)
        assertEquals(RainNoticeKind.CHANGED, evaluate(shifted(60), now + hour + 60, minor.state).notification!!.kind)
    }

    @Test fun possibleToExpectedProbabilityFluctuationsDoNotNotifyButHeavyRainDoes() {
        val initial = initial()
        val expected = report(wet(now + 2 * hour, chance = 75.0), dry(now + 3 * hour), dry(now + 4 * hour))
        assertNull(evaluate(expected, now + hour, initial.state).notification)
        val heavy = expected.copy(slots = expected.slots.map { if (it.condition == 500) it.copy(condition = 502) else it })
        assertNull(evaluate(heavy, now + hour - 1, initial.state).notification)
        assertEquals(RainNoticeKind.CHANGED, evaluate(heavy, now + hour, initial.state).notification!!.kind)
    }

    @Test fun initialSeverityDescribesTheEligibleForecastPeriodRatherThanDistantHeavyRain() {
        val weather = report(wet(now + hour), wet(now + 2 * hour, chance = 100.0, code = 502))
        assertEquals(RainLikelihood.POSSIBLE, evaluate(weather).notification!!.likelihood)
    }

    @Test fun aDistantHeavyHourDoesNotAlterTheCommunicatedNearTermForecast() {
        val initial = evaluate(report(*(1..12).map { wet(now + it * hour) }.toTypedArray()))
        val later = report(*(1..12).map { wet(now + it * hour, code = if (it == 12) 502 else 500) }.toTypedArray())
        assertNull(evaluate(later, now + hour, initial.state).notification)
        val stormNow = later.copy(slots = later.slots.map { if (it.timestamp == now + 2 * hour) it.copy(condition = 502) else it })
        assertEquals(RainLikelihood.HEAVY, evaluate(stormNow.copy(current = stormNow.current.copy(timestamp = now + 2 * hour, condition = 500)),
            now + 2 * hour, initial.state).notification!!.likelihood)
    }

    @Test fun currentRainSeverityUpdatesDoNotReferToAnAlreadyPassedStartingTime() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val weather = report(checkedAt = now + 2 * hour)
            val heavy = RainNotice(RainNoticeKind.CHANGED, now + hour, now + 3 * hour,
                RainLikelihood.HEAVY, previousStart = now + hour)
            assertEquals("Heavy rain is now possible. It may end around 12:00 PM.", heavy.message(weather, false))
            assertEquals("Heavy rain is no longer expected. Rain is still possible. It may end around 12:00.",
                heavy.copy(likelihood = RainLikelihood.EXPECTED).message(weather, true))
        } finally { Locale.setDefault(previousLocale) }
    }

    @Test fun aMeaningfullyRevisedEndUsesTheSameCooldownAsOtherUpdates() {
        val initial = initial()
        val longer = report(wet(now + 2 * hour), wet(now + 3 * hour), dry(now + 4 * hour), dry(now + 5 * hour))
        assertNull(evaluate(longer, now + hour - 1, initial.state).notification)
        val result = evaluate(longer, now + hour, initial.state)
        assertEquals(RainNoticeKind.CHANGED, result.notification!!.kind)
        assertEquals(now + 4 * hour, result.notification!!.end)
    }

    @Test fun temporaryMissingEndCoverageDoesNotRepeatAnAlreadyCommunicatedEstimate() {
        val initial = initial()
        val heavyIncomplete = report(wet(now + 2 * hour, code = 502))
        val heavy = evaluate(heavyIncomplete, now + hour, initial.state)
        assertEquals(RainNoticeKind.CHANGED, heavy.notification!!.kind)
        assertTrue(heavy.state.events.single().endingNotified)
        assertEquals(initial.notification!!.end, heavy.state.events.single().end)
        val restored = report(wet(now + 2 * hour, code = 502), dry(now + 3 * hour), dry(now + 4 * hour))
        assertNull(evaluate(restored, now + 2 * hour, heavy.state).notification)
    }

    @Test fun cancellationRequiresCoverageAndIsNotRepeated() {
        val initial = initial()
        val dryForecast = report(dry(now + 2 * hour), dry(now + 3 * hour), dry(now + 4 * hour))
        assertNull(evaluate(dryForecast, now + hour - 1, initial.state).notification)
        val cancelled = evaluate(dryForecast, now + hour, initial.state)
        assertEquals(RainNoticeKind.CANCELLED, cancelled.notification!!.kind)
        assertTrue(cancelled.state.events.single().cancelled)
        assertNull(evaluate(dryForecast, now + hour + 30 * 60, cancelled.state).notification)
    }

    @Test fun missingOrUncertainForecastsCannotCancelAnAlreadyCommunicatedEvent() {
        val initial = initial()
        val samples = listOf(report(), report(dry(now + 2 * hour)),
            report(dry(now + 2 * hour), dry(now + 3 * hour).copy(precipitation = null)),
            report(wet(now + 2 * hour, chance = 30.0), dry(now + 3 * hour)),
            report(wet(now + 2 * hour, chance = 20.0), dry(now + 3 * hour)),
            report(dry(now + 2 * hour).copy(rainMm = Double.NaN), dry(now + 3 * hour)))
        samples.forEach { assertNull(evaluate(it, now + hour, initial.state).notification) }
    }

    @Test fun cancellationAndEndingAreNotDeliveredForEventsWhoseTimesHavePassed() {
        val initial = initial()
        val dryForecast = report(dry(now + 2 * hour), dry(now + 3 * hour), dry(now + 4 * hour))
        assertNull(evaluate(dryForecast, now + 2 * hour, initial.state).notification)
        val unknown = evaluate(report(wet(now + hour)))
        assertNull(evaluate(report(wet(now + hour), dry(now + 2 * hour), dry(now + 3 * hour)),
            now + 3 * hour, unknown.state).notification)
    }

    @Test fun renewedRainAfterCancellationUsesTheUpdateCooldown() {
        val initial = initial()
        val cancelled = evaluate(report(dry(now + 2 * hour), dry(now + 3 * hour)), now + hour, initial.state)
        assertEquals(RainNoticeKind.CANCELLED, cancelled.notification!!.kind)
        val renewed = report(wet(now + 3 * hour), dry(now + 4 * hour), dry(now + 5 * hour))
        assertNull(evaluate(renewed, now + hour + 1800, cancelled.state).notification)
        assertEquals(RainNoticeKind.START, evaluate(renewed, now + 2 * hour, cancelled.state).notification!!.kind)
    }

    @Test fun aNewSeparateEpisodeIsNotDelayedByAnEarlierNotification() {
        val first = evaluate(report(wet(now + hour), dry(now + 2 * hour), dry(now + 3 * hour)))
        val twoEvents = report(wet(now + hour), dry(now + 2 * hour), wet(now + 3 * hour),
            dry(now + 4 * hour), dry(now + 5 * hour))
        val result = evaluate(twoEvents, now + 1800, first.state)
        assertEquals(RainNoticeKind.START, result.notification!!.kind)
        assertEquals(now + 3 * hour, result.notification!!.start)
        assertEquals(2, result.state.events.size)
    }

    @Test fun aKnownDryGapAfterTheEarlierWindowMovesAwaySeparatesNewRain() {
        val first = evaluate(report(wet(now + hour), wet(now + 2 * hour)))
        val advanced = report(dry(now + 3 * hour), wet(now + 4 * hour), dry(now + 5 * hour), dry(now + 6 * hour))
        val result = evaluate(advanced, now + 3 * hour, first.state)
        assertEquals(RainNoticeKind.START, result.notification!!.kind)
        assertEquals(now + 4 * hour, result.notification!!.start)
    }

    @Test fun slidingAnOngoingRainWindowDoesNotInventLaterStartingTimesOrRepeatWarnings() {
        val first = evaluate(report(wet(now + hour), wet(now + 2 * hour), wet(now + 3 * hour)))
        val advanced = report(wet(now + 2 * hour), wet(now + 3 * hour), wet(now + 4 * hour))
        val result = evaluate(advanced, now + 2 * hour, first.state)
        assertNull(result.notification)
        assertEquals(now + hour, result.state.events.single().start)
        assertTrue(now + 4 * hour in result.state.events.single().timestamps)
    }

    @Test fun quietHoursDoNotQueueNotificationsForRainThatHasAlreadyPassed() {
        val asleep = Instant.parse("2026-10-06T06:00:00Z").epochSecond
        val weather = report(wet(asleep + hour), dry(asleep + 2 * hour), dry(asleep + 3 * hour), checkedAt = asleep)
        val suppressed = evaluate(weather, asleep)
        assertNull(suppressed.notification)
        assertTrue(suppressed.state.events.isEmpty())
        assertNull(evaluate(weather, asleep + 2 * hour, suppressed.state).notification)
        val fresh = report(wet(asleep + 3 * hour), checkedAt = asleep + 2 * hour)
        assertEquals(RainNoticeKind.START, evaluate(fresh, asleep + 2 * hour, suppressed.state).notification!!.kind)
    }

    @Test fun suppressedUpdatesDoNotChangeWhatWasLastCommunicated() {
        val first = initial()
        val shifted = report(dry(now + 2 * hour), dry(now + 3 * hour), wet(now + 4 * hour),
            dry(now + 5 * hour), dry(now + 6 * hour))
        val quiet = QuietHoursPreferences(startMinutes = 10 * 60, endMinutes = 10 * 60 + 1)
        val suppressed = evaluate(shifted, now + hour, first.state, quiet)
        assertNull(suppressed.notification)
        assertEquals(first.state.events.single().lastNotifiedAt, suppressed.state.events.single().lastNotifiedAt)
        assertEquals(RainNoticeKind.CHANGED, evaluate(shifted, now + hour + 120, suppressed.state, quiet).notification!!.kind)
    }

    @Test fun oldIdentityHistoryMigratesWithoutRepeatingItsInitialWarning() {
        val weather = report(wet(now + hour), wet(now + 2 * hour), dry(now + 3 * hour), dry(now + 4 * hour))
        val legacy = setOf("${location.identity}|${now + hour}")
        val migrated = evaluate(weather, legacy = legacy)
        assertNull(migrated.notification)
        assertEquals(1, migrated.state.events.size)
        assertTrue(now + 2 * hour in migrated.state.events.single().timestamps)
        assertEquals(RainNoticeKind.ENDING, evaluate(weather.copy(current = weather.current.copy(timestamp = now + hour, condition = 500)),
            now + hour, migrated.state).notification!!.kind)
    }

    @Test fun selectedCitiesKeepSeparateForecastHistories() {
        val initial = initial()
        val anotherCity = report(wet(now + 2 * hour)).copy(location = location.copy(name = "Lviv", lon = 24.03))
        val result = evaluate(anotherCity, now + 60, initial.state)
        assertEquals(RainNoticeKind.START, result.notification!!.kind)
        assertEquals(2, result.state.events.size)
    }

    @Test fun persistentHistoryIsBoundedAndCorruptFarFutureOrOldEntriesAreDiscarded() {
        val events = (0..40).map { index -> RainEventMemory(location.identity, now + index * hour,
            timestamps = setOf(now - 49 * hour, now, now + 49 * hour), lastNotifiedAt = now,
            lastSeenAt = now - index) }
        val malformed = listOf(events.first().copy(lastSeenAt = now - 49 * hour),
            events.first().copy(lastSeenAt = now + 2 * hour), events.first().copy(locationIdentity = ""))
        val result = RainForecastPolicy.prune(RainAlertState(events + malformed), now)
        assertEquals(32, result.events.size)
        assertTrue(result.events.all { it.timestamps == setOf(now) })
    }

    @Test fun messagesUseTheSharedPaddedClockCityTimeAndApproximateWording() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val weather = report().copy(offset = 3 * 3600)
            val initial = RainNotice(RainNoticeKind.START, now + hour, now + 2 * hour, RainLikelihood.EXPECTED)
            assertEquals("Rain expected around 01:00 PM. It may end around 02:00 PM.", initial.message(weather, false))
            assertEquals("Rain expected around 13:00. It may end around 14:00.", initial.message(weather, true))
            assertEquals("Rain may end around 02:00 PM.", initial.copy(kind = RainNoticeKind.ENDING).message(weather, false))
            assertEquals("Rain is no longer expected around 13:00.", initial.copy(kind = RainNoticeKind.CANCELLED).message(weather, true))
            val changed = initial.copy(kind = RainNoticeKind.CHANGED, previousStart = now, previousEnd = null)
            assertEquals("Rain expected around 01:00 PM (previously 12:00 PM). It may end around 02:00 PM.",
                changed.message(weather, false))
        } finally { Locale.setDefault(previousLocale) }
    }

    @Test fun crossMidnightEstimatesIncludeTheForecastCityDayAndUseItsDstOffset() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val checked = Instant.parse("2026-10-24T20:00:00Z").epochSecond
            val weather = report(checkedAt = checked).copy(zoneId = "Europe/Berlin", offset = 0)
            val notice = RainNotice(RainNoticeKind.START, checked + hour, checked + 5 * hour)
            assertEquals("Rain possible around 11:00 PM. It may end around Sun 02:00 AM.", notice.message(weather, false))
            assertEquals("Rain possible around 23:00. It may end around Sun 02:00.", notice.message(weather, true))
        } finally { Locale.setDefault(previousLocale) }
    }

    @Test fun notificationExpiryNeverUsesAnAlreadyElapsedTimestamp() {
        assertEquals(now + hour, RainNotice(RainNoticeKind.CANCELLED, now - hour).expiresAt(now))
        assertEquals(now + 2 * hour, RainNotice(RainNoticeKind.ENDING, now - hour, now + 2 * hour).expiresAt(now))
        assertEquals(now + hour, RainNotice(RainNoticeKind.ENDING, now - 2 * hour, now - hour).expiresAt(now))
    }
}
