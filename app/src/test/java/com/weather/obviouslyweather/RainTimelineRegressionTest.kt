// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RainTimelineRegressionTest {
    private val three = Instant.parse("2026-10-13T12:00:00Z").epochSecond // 15:00 in Kyiv
    private val hour = 3600L
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private fun wet(time: Long) = ForecastSlot(time, 12.0, null, null, 500, "Rain", false, 80.0, 0.5, 0.0)
    private fun dry(time: Long) = wet(time).copy(condition = 804, description = "Overcast clouds", precipitation = 0.0, rainMm = 0.0)
    private fun weather(at: Long, vararg slots: ForecastSlot, raining: Boolean = false) = WeatherReport(
        city, WeatherUnits.METRIC,
        CurrentWeather(at, 12.0, null, if (raining) 500 else 804, if (raining) "Rain" else "Overcast clouds",
            false, null, null, null, null, null, null, null, null, null),
        slots.toList(), 3 * 3600, at, zoneId = "Europe/Kyiv", forecastStepSeconds = 3600
    )
    private fun evaluate(report: WeatherReport, state: RainAlertState = RainAlertState(), at: Long = report.fetchedAt) =
        RainForecastPolicy.evaluate(report, at, state, QuietHoursPreferences(enabled = false), ZoneId.of("Europe/Kyiv"))

    @Test fun aPastHourIsNotAnnouncedAsAnEarlierStartWhenCurrentConditionsAreDry() {
        val first = evaluate(weather(three - hour, wet(three + hour)))
        val result = evaluate(weather(three + 20 * 60, wet(three), dry(three + hour), dry(three + 2 * hour)), first.state)
        assertNull(result.notification)
    }

    @Test fun matchedRainCannotProduceAnEndingAlertFromAnOldWetHourWhenItIsCurrentlyDry() {
        val first = evaluate(weather(three - hour, wet(three), wet(three + hour)))
        val result = evaluate(weather(three + hour + 42 * 60, wet(three + hour), dry(three + 2 * hour),
            dry(three + 3 * hour)), first.state)
        assertNull(result.notification)
    }

    @Test fun aRevisedEndingAlsoRequiresObservedRainForAnOngoingEvent() {
        val first = evaluate(weather(three - hour, wet(three), dry(three + hour), dry(three + 2 * hour)))
        val result = evaluate(weather(three + 20 * 60, wet(three), wet(three + hour), dry(three + 2 * hour),
            dry(three + 3 * hour)), first.state)
        assertNull(result.notification)
    }

    @Test fun enablingAlertsDuringContinuousRainDoesNotAnnounceTheNextHourAsItsStart() {
        val result = evaluate(weather(three + 20 * 60, wet(three), wet(three + hour), wet(three + 2 * hour),
            dry(three + 3 * hour), dry(three + 4 * hour), raining = true))
        assertEquals(RainNoticeKind.ENDING, result.notification!!.kind)
        assertEquals(three, result.state.events.single().start)
    }

    @Test fun anOngoingForecastWithoutObservedRainCannotCreateANewStartWarning() {
        assertNull(evaluate(weather(three + 20 * 60, wet(three), wet(three + hour), wet(three + 2 * hour))).notification)
    }

    @Test fun omittingTheEarlierWetHourDoesNotProveTheStartWasPostponed() {
        val first = evaluate(weather(three - 2 * hour, wet(three), wet(three + hour), wet(three + 2 * hour)))
        val result = evaluate(weather(three - hour, wet(three + hour), wet(three + 2 * hour)), first.state)
        assertNull(result.notification)
        assertEquals(three, result.state.events.single().start)
    }

    @Test fun theStartStaysStableAsAContinuousRainWindowAdvances() {
        val first = evaluate(weather(three - hour, wet(three), wet(three + hour), wet(three + 2 * hour)))
        val withCurrent = evaluate(weather(three + 10 * 60, wet(three), wet(three + hour), wet(three + 2 * hour),
            raining = true), first.state)
        val advanced = evaluate(weather(three + hour + 10 * 60, wet(three + hour), wet(three + 2 * hour),
            raining = true), withCurrent.state)
        assertNull(withCurrent.notification)
        assertNull(advanced.notification)
        assertEquals(three, advanced.state.events.single().start)
    }

    @Test fun actualDryCoverageCanStillPostponeAnUpcomingEvent() {
        val first = evaluate(weather(three - 2 * hour, wet(three), wet(three + hour)))
        val result = evaluate(weather(three - hour, dry(three), wet(three + hour), wet(three + 2 * hour)), first.state)
        assertEquals(RainNoticeKind.CHANGED, result.notification!!.kind)
        assertEquals(three + hour, result.notification!!.start)
        assertEquals(three, result.notification!!.previousStart)
    }

    @Test fun aRealEarlierStartCanBeReportedWhileItIsStillInTheFuture() {
        val first = evaluate(weather(three - 2 * hour, wet(three + hour)))
        val result = evaluate(weather(three - hour, wet(three), wet(three + hour)), first.state)
        assertEquals(RainNoticeKind.CHANGED, result.notification!!.kind)
        assertEquals(three, result.notification!!.start)
    }

    @Test fun freshRainAllowsAnEndingButStaleOrUnknownCurrentConditionsDoNot() {
        val first = evaluate(weather(three - hour, wet(three), wet(three + hour)))
        val report = weather(three + hour + 20 * 60, wet(three + hour), dry(three + 2 * hour),
            dry(three + 3 * hour), raining = true)
        assertEquals(RainNoticeKind.ENDING, evaluate(report, first.state).notification!!.kind)
        assertNull(evaluate(report.copy(current = report.current.copy(timestamp = three - hour)), first.state).notification)
        assertNull(evaluate(report.copy(current = report.current.copy(condition = null)), first.state).notification)
    }

    @Test fun heavyRainChangesInAnElapsedHourCannotClaimRainIsOngoingWhenItIsDry() {
        val first = evaluate(weather(three - hour, wet(three)))
        assertNull(evaluate(weather(three + 20 * 60, wet(three).copy(condition = 502)), first.state).notification)
    }

    @Test fun observedRainIsNotGivenANewStartWhenTheApiOmitsTheCurrentHour() {
        val report = weather(three + 20 * 60, wet(three + hour), wet(three + 2 * hour), raining = true)
        assertNull(evaluate(report).notification)
        val complete = report.copy(slots = report.slots + listOf(dry(three + 3 * hour), dry(three + 4 * hour)))
        val ending = evaluate(complete).notification!!
        assertEquals(RainNoticeKind.ENDING, ending.kind)
        assertTrue(ending.start <= report.fetchedAt)
        assertEquals(three + 3 * hour, ending.end)
    }

    @Test fun aDryGapAllowsASeparateFutureWarningEvenWhenItIsRainingNow() {
        val report = weather(three + 20 * 60, dry(three + hour), wet(three + 2 * hour), raining = true)
        assertEquals(three + 2 * hour, evaluate(report).notification!!.start)
    }

    @Test fun aNewEndingForFutureRainIsAnUpcomingForecastUpdateWhenItIsDryNow() {
        val first = evaluate(weather(three - 2 * hour, wet(three)))
        val report = weather(three - hour, wet(three), dry(three + hour), dry(three + 2 * hour))
        val notice = evaluate(report, first.state).notification!!
        assertEquals(RainNoticeKind.CHANGED, notice.kind)
        assertEquals("Rain expected around 15:00. It may end around 16:00.", notice.message(report, true))
    }

    @Test fun aRevisedEndForFutureRainNamesItsUpcomingStartInsteadOfImplyingRainIsHappening() {
        val first = evaluate(weather(three - 2 * hour, wet(three), dry(three + hour), dry(three + 2 * hour)))
        val report = weather(three - hour, wet(three), wet(three + hour), dry(three + 2 * hour), dry(three + 3 * hour))
        val notice = evaluate(report, first.state).notification!!
        assertEquals(RainNoticeKind.CHANGED, notice.kind)
        assertEquals("Rain expected around 15:00. It may now end around 17:00 (previously 16:00).", notice.message(report, true))
    }

    @Test fun deliveryMustNotAnnounceAStartOrEndThatPassedAfterEvaluation() {
        val start = RainNotice(RainNoticeKind.START, three, three + hour)
        assertTrue(start.isTimely(three - 1))
        assertFalse(start.isTimely(three))
        val ending = start.copy(kind = RainNoticeKind.ENDING)
        assertTrue(ending.isTimely(three + 20 * 60))
        assertFalse(ending.isTimely(three + hour))
        val shifted = start.copy(kind = RainNoticeKind.CHANGED, previousStart = three + hour)
        assertFalse(shifted.isTimely(three + 20 * 60))
    }

    @Test fun aRestartDoesNotLoseTheOriginalStartAsTheForecastWindowAdvances() {
        val first = evaluate(weather(three - hour, wet(three), wet(three + hour), wet(three + 2 * hour)))
        val restored = RainAlertStateCodec.decode(RainAlertStateCodec.encode(first.state))
        val result = evaluate(weather(three + hour + 20 * 60, wet(three + hour), wet(three + 2 * hour),
            raining = true), restored)
        assertNull(result.notification)
        assertEquals(three, result.state.events.single().start)
    }
}
