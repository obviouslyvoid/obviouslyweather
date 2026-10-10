// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import com.weather.obviouslyweather.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

class WidgetForecastTest {
    private val now = Instant.parse("2026-10-05T09:00:00Z").epochSecond
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private fun slot(time: Long, temp: Double? = 12.0, night: Boolean = false, code: Int = 801, chance: Double? = 20.0) =
        ForecastSlot(time, temp, temp?.minus(1), temp?.plus(1), code, "Cloudy", night, chance, 0.0, 0.0)
    private fun report(slots: List<ForecastSlot>, offset: Int = 0) = WeatherReport(city, WeatherUnits.METRIC,
        CurrentWeather(now - 7200, 12.0, null, 801, "Cloudy", false, null, null, null, null, null, null, null, null, null), slots, offset, now)

    @Test fun threeHourRowsStartAtNowAndExcludeOldObservationPeriods() {
        val rows = widgetForecastRows(report(listOf(slot(now - 3600), slot(now), slot(now + 10800))), WidgetForecastMode.THREE_HOUR, now, true)
        assertEquals(listOf("09:00", "12:00"), rows.map { it.label })
    }
    @Test fun threeHourRowsSortAndLimitToFourWithoutInventingHourlyData() {
        val slots = (0..7).map { slot(now + it * 10800) }.reversed()
        assertEquals(listOf("09:00", "12:00", "15:00", "18:00"), widgetForecastRows(report(slots), WidgetForecastMode.THREE_HOUR, now, true).map { it.label })
    }
    @Test fun oneCallRowsUseFourActualUpcomingHoursAndPreserveClockFormatting() {
        val savedLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val slots = (-2..7).map { slot(now + it * 3600) }.reversed()
            val weather = report(slots).copy(forecastStepSeconds = 3600, zoneId = "Europe/Kyiv")
            assertEquals(listOf("12:00", "13:00", "14:00", "15:00"),
                widgetForecastRows(weather, WidgetForecastMode.THREE_HOUR, now, true).map { it.label })
            assertEquals(listOf("12:00 PM", "01:00 PM", "02:00 PM", "03:00 PM"),
                widgetForecastRows(weather, WidgetForecastMode.THREE_HOUR, now, false).map { it.label })
        } finally { Locale.setDefault(savedLocale) }
    }
    @Test fun forecastTimeUsesCityOffsetAndClockPreference() {
        val savedLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val report = report(listOf(slot(now), slot(now + 10800), slot(now + 21600)), 10800)
            assertEquals(listOf("12:00", "15:00", "18:00"),
                widgetForecastRows(report, WidgetForecastMode.THREE_HOUR, now, true).map { it.label })
            assertEquals(listOf("12:00 PM", "03:00 PM", "06:00 PM"),
                widgetForecastRows(report, WidgetForecastMode.THREE_HOUR, now, false).map { it.label })
            assertEquals("12:00 AM", widgetForecastRows(report(listOf(slot(now)), -32400),
                WidgetForecastMode.THREE_HOUR, now, false).single().label)
            assertEquals("09:00 PM", widgetForecastRows(report(listOf(slot(now)), 43200),
                WidgetForecastMode.THREE_HOUR, now, false).single().label)
        } finally { Locale.setDefault(savedLocale) }
    }
    @Test fun hidingAmPmRetainsPaddedTwelveHourTimesAtNoonAndMidnight() {
        val savedLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val slots = listOf(0L, 3L, 9L, 12L).map { slot(now + it * 3600) }
            val weather = report(slots, 10800)
            assertEquals(listOf("12:00", "03:00", "09:00", "12:00"),
                widgetForecastRows(weather, WidgetForecastMode.THREE_HOUR, now, false, hideAmPm = true).map { it.label })
            assertEquals(listOf("12:00 PM", "03:00 PM", "09:00 PM", "12:00 AM"),
                widgetForecastRows(weather, WidgetForecastMode.THREE_HOUR, now, false).map { it.label })
        } finally { Locale.setDefault(savedLocale) }
    }
    @Test fun twentyFourHourClockOverridesHiddenAmPmPreference() {
        val weather = report(listOf(slot(now), slot(now + 43200)))
        assertEquals(listOf("09:00", "21:00"),
            widgetForecastRows(weather, WidgetForecastMode.THREE_HOUR, now, true, hideAmPm = true).map { it.label })
        assertEquals(widgetForecastRows(weather, WidgetForecastMode.THREE_HOUR, now, true),
            widgetForecastRows(weather, WidgetForecastMode.THREE_HOUR, now, true, hideAmPm = true))
    }
    @Test fun hidingAmPmOnWidgetDoesNotChangeApplicationClockFormatter() {
        val savedLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val weather = report(listOf(slot(now)))
            assertEquals("09:00", widgetForecastRows(weather, WidgetForecastMode.THREE_HOUR, now,
                false, hideAmPm = true).single().label)
            assertEquals("09:00 AM", weather.localTime(now).format(clockFormatter(false)))
        } finally { Locale.setDefault(savedLocale) }
    }
    @Test fun hidingAmPmDoesNotAlterDailyForecast() {
        val weather = report(listOf(slot(now + 10800, 18.0, false, 800), slot(now + 43200, 9.0, true, 804)))
        assertEquals(widgetForecastRows(weather, WidgetForecastMode.DAILY, now, false),
            widgetForecastRows(weather, WidgetForecastMode.DAILY, now, false, hideAmPm = true))
    }
    @Test fun hidingAmPmDefaultsOffAndResetsWhenClockBecomesTwentyFourHour() {
        assertFalse(WidgetPreferences().hideAmPm)
        val hidden = WidgetPreferences(WidgetForecastMode.DAILY, true, WidgetTextColor.BLACK, hideAmPm = true)
        assertEquals(hidden, hidden.normalizedForClock(false))
        val reset = hidden.normalizedForClock(true)
        assertEquals(hidden.copy(hideAmPm = false), reset)
        assertFalse(reset.normalizedForClock(false).hideAmPm)
        assertEquals(reset, reset.normalizedForClock(true))
    }
    @Test fun missingOrInvalidValuesRemainPlaceholders() {
        val rows = widgetForecastRows(report(listOf(slot(now, null, chance = null), slot(now + 10800, Double.NaN, chance = Double.NaN))), WidgetForecastMode.THREE_HOUR, now, true)
        assertEquals(listOf("—", "—"), rows.map { it.value })
        assertTrue(rows.all { it.chance == null })
    }
    @Test fun dailyRowsUseHighLowAndRealSeparateDayNightConditions() {
        val report = report(listOf(slot(now + 10800, 18.0, false, 800), slot(now + 43200, 9.0, true, 804)))
        val row = widgetForecastRows(report, WidgetForecastMode.DAILY, now, true).single()
        assertEquals("Today", row.label)
        assertEquals("19° 8°", row.value)
        assertEquals(800, row.condition)
        assertEquals(804, row.secondCondition)
        assertTrue(row.secondNight)
        assertNull(row.chance)
        assertEquals("↑19° / ↓8°", widgetTodayRange(report, now))
    }
    @Test fun dailyRowsSkipPastDaysAndUseLocalCalendarDate() {
        val report = report(listOf(slot(now - 86400), slot(now), slot(now + 86400)), 10800)
        assertEquals(2, widgetForecastRows(report, WidgetForecastMode.DAILY, now, true).size)
        assertEquals("Today", widgetForecastRows(report, WidgetForecastMode.DAILY, now, true).first().label)
        assertTrue(widgetForecastRows(report, WidgetForecastMode.DAILY, now + 3 * 86400, true).isEmpty())
    }
    @Test fun partialDaysKeepNightArtworkUsingTheirKnownCondition() {
        val row = widgetForecastRows(report(listOf(slot(now, night = false))), WidgetForecastMode.DAILY, now, true).single()
        assertEquals(801, row.secondCondition)
        assertTrue(row.secondNight)
        val night = widgetForecastRows(report(listOf(slot(now, night = true, code = 800))), WidgetForecastMode.DAILY, now, true).single()
        assertTrue(night.night)
        assertEquals(800, night.secondCondition)
    }
    @Test fun oneCallDailyRowsUseProviderRangeAndConditionRatherThanHourlyExtremes() {
        val day = DailyWeather(LocalDate.parse("2026-10-05"), 5.0, 24.0, 500, "Light rain", 70.0, 3.0, 0.0)
        val weather = report(listOf(slot(now + 3600, 15.0, false, 800), slot(now + 43200, 12.0, true, 804)))
            .copy(providerDaily = listOf(day), forecastStepSeconds = 3600)
        val row = widgetForecastRows(weather, WidgetForecastMode.DAILY, now, true).single()
        assertEquals("24° 5°", row.value)
        assertEquals(500, row.condition)
        assertFalse(row.night)
        assertEquals("Light rain", row.description)
        assertEquals(804, row.secondCondition)
        assertTrue(row.secondNight)
        assertEquals("↑24° / ↓5°", widgetTodayRange(weather, now))
    }
    @Test fun oneCallDailyRowsKeepNightArtworkBeyondHourlyCoverage() {
        val conditions = listOf(804, 500, 801, 800, 802, 600, 200)
        val days = conditions.mapIndexed { index, condition ->
            DailyWeather(LocalDate.parse("2026-10-05").plusDays(index.toLong()), 9.0, 20.0, condition,
                "Daily condition", 10.0, 0.0, 0.0)
        }
        val weather = report(listOf(slot(now + 3600, night = false), slot(now + 43200, night = true, code = 800),
            slot(now + 129600, night = true, code = 804)))
            .copy(providerDaily = days, forecastStepSeconds = 3600)
        val rows = widgetForecastRows(weather, WidgetForecastMode.DAILY, now, true)
        assertEquals(4, rows.size)
        assertEquals(conditions.take(4), rows.map { it.condition })
        assertEquals(listOf(800, 804, 801, 800), rows.map { it.secondCondition })
        assertTrue(rows.all { !it.night && it.secondNight })
    }
    @Test fun oneCallDailyNightArtworkRemainsAvailableWhenHourlyForecastIsMissing() {
        val days = (0L..6L).map { index ->
            DailyWeather(LocalDate.parse("2026-10-05").plusDays(index), 11.0, 23.0, 801,
                "Few clouds", 0.0, 0.0, 0.0)
        }
        val weather = report(emptyList()).copy(providerDaily = days, forecastStepSeconds = 3600)
        val rows = widgetForecastRows(weather, WidgetForecastMode.DAILY, now + 86400, true)
        assertEquals(4, rows.size)
        assertEquals("Today", rows.first().label)
        assertTrue(rows.all { it.condition == 801 && it.secondCondition == 801 && it.secondNight })
        assertTrue(widgetForecastRows(weather, WidgetForecastMode.THREE_HOUR, now, true).isEmpty())
    }
    @Test fun oneCallDailyNightArtworkUsesDailyConditionWhenHourlyConditionIsMissing() {
        val day = DailyWeather(LocalDate.parse("2026-10-05"), 11.0, 23.0, 500, "Light rain", 50.0, 2.0, 0.0)
        val weather = report(listOf(slot(now + 43200, night = true).copy(condition = null)))
            .copy(providerDaily = listOf(day), forecastStepSeconds = 3600)
        val row = widgetForecastRows(weather, WidgetForecastMode.DAILY, now, true).single()
        assertEquals(500, row.secondCondition)
        assertTrue(row.secondNight)
    }
    @Test fun oneCallDailyMissingConditionsStayUnavailable() {
        val day = DailyWeather(LocalDate.parse("2026-10-05"), 11.0, 23.0, null, "", null, 0.0, 0.0)
        val row = widgetForecastRows(report(emptyList()).copy(providerDaily = listOf(day)),
            WidgetForecastMode.DAILY, now, true).single()
        assertNull(row.condition)
        assertNull(row.secondCondition)
    }
    @Test fun missingOneCallDailyForecastDoesNotAggregateHourlyDataIntoDailyRows() {
        val weather = report(listOf(slot(now), slot(now + 3600))).copy(providerDaily = emptyList(), forecastStepSeconds = 3600)
        assertTrue(widgetForecastRows(weather, WidgetForecastMode.DAILY, now, true).isEmpty())
        assertEquals("— / —", widgetTodayRange(weather, now))
    }
    @Test fun hybridDailyRowsUseSeparateStandardForecastForDayAndNightIcons() {
        val legacySlots = (0L..3L).flatMap { index ->
            listOf(slot(now + index * 86400, 18.0, false, 801), slot(now + index * 86400 + 43200, 9.0, true, 804))
        }
        val weather = report(listOf(slot(now + 3600, code = 500), slot(now + 43200, night = true, code = 800)))
            .copy(providerDaily = dailyForecast(legacySlots, 0), forecastStepSeconds = 3600, dailySlots = legacySlots)
        val rows = widgetForecastRows(weather, WidgetForecastMode.DAILY, now, true)
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.condition == 801 && it.secondCondition == 804 && it.secondNight })
        assertEquals(500, widgetForecastRows(weather, WidgetForecastMode.THREE_HOUR, now, true).first().condition)
    }
    @Test fun hybridDailyRowsUseDailyNightArtworkWithoutBorrowingAnHourlyCondition() {
        val legacySlots = listOf(slot(now + 10800, 18.0, false, 801))
        val weather = report(listOf(slot(now + 43200, night = true, code = 804)))
            .copy(providerDaily = dailyForecast(legacySlots, 0), forecastStepSeconds = 3600, dailySlots = legacySlots)
        val row = widgetForecastRows(weather, WidgetForecastMode.DAILY, now, true).single()
        assertEquals(801, row.condition)
        assertEquals(801, row.secondCondition)
        assertTrue(row.secondNight)
        assertFalse(row.night)
    }
    @Test fun savedThreeHourForecastKeepsBothIconsForLaterDaysWithoutNightSlots() {
        val days = listOf(804, 500, 804, 500)
        val legacySlots = days.flatMapIndexed { index, code ->
            listOf(slot(now + index * 86400, code = code)) + if (index < 2)
                listOf(slot(now + index * 86400 + 43200, night = true, code = if (index == 0) 800 else 804))
                else emptyList()
        }
        val weather = report(emptyList()).copy(providerDaily = dailyForecast(legacySlots, 0), dailySlots = legacySlots)
        val rows = widgetForecastRows(weather, WidgetForecastMode.DAILY, now, true)
        assertEquals(days, rows.map { it.condition })
        assertEquals(listOf(800, 804, 804, 500), rows.map { it.secondCondition })
        assertTrue(rows.all { it.secondNight })
    }
    @Test fun emptyOrExpiredForecastHasNoSampleData() {
        assertTrue(widgetForecastRows(report(emptyList()), WidgetForecastMode.THREE_HOUR, now, true).isEmpty())
        assertTrue(widgetForecastRows(report(listOf(slot(now - 3600))), WidgetForecastMode.THREE_HOUR, now, true).isEmpty())
        assertEquals("— / —", widgetTodayRange(report(emptyList()), now))
    }
}
