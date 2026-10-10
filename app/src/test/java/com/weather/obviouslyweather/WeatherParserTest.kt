// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WeatherParserTest {
    private val location = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private fun epoch(iso: String) = Instant.parse(iso).epochSecond
    private val current = """{"dt":1791133200,"timezone":10800,"main":{"temp":17.5,"feels_like":16,"humidity":64},"weather":[{"id":800,"description":"clear sky","icon":"01n"}]}"""
    private fun report(forecast: String? = null) = WeatherParser.report(location, WeatherUnits.METRIC, current, forecast, 1791133300)

    private fun slot(iso: String, temp: Double?, low: Double? = null, high: Double? = null,
                     condition: Int = 800, precipitation: Double? = null, rain: Double = 0.0, snow: Double = 0.0) =
        ForecastSlot(epoch(iso), temp, low, high, condition, "Condition $condition", false, precipitation, rain, snow)

    @Test fun realOpenWeatherResponsesParseWithoutLosingPeriods() {
        val currentFixture = javaClass.getResource("/openweather-current.json")!!.readText().removePrefix("\uFEFF")
        val forecastFixture = javaClass.getResource("/openweather-forecast.json")!!.readText().removePrefix("\uFEFF")
        val parsed = WeatherParser.report(location, WeatherUnits.METRIC, currentFixture, forecastFixture, 1791133300)
        assertTrue(parsed.current.temperature.isFinite())
        assertEquals(40, parsed.slots.size)
        assertEquals(8, parsed.next24Hours.size)
        assertTrue(parsed.daily.size in 1..5)
        assertNull(parsed.forecastNote)
        assertTrue(parsed.slots.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })
    }

    @Test fun missingOptionalFieldsStayUnknownRatherThanZero() {
        val parsed = report()
        assertNull(parsed.current.pressure)
        assertNull(parsed.current.windSpeed)
        assertNull(parsed.current.sunrise)
        assertNull(parsed.current.visibility)
        assertEquals(17.5, parsed.current.temperature, 0.001)
        assertEquals("Clear sky", parsed.current.description)
        assertTrue(parsed.current.night)
        assertNotNull(parsed.forecastNote)
    }

    @Test fun missingCurrentTemperatureIsRejected() {
        try {
            WeatherParser.report(location, WeatherUnits.METRIC, """{"main":{},"dt":1}""", null, 1)
            fail("A missing temperature must not become 0 degrees")
        } catch (error: WeatherFailure) {
            assertTrue(error.message!!.contains("unreadable"))
        }
    }

    @Test fun booleanAndStringNumbersAreNotAccepted() {
        val obj = JSONObject("""{"a":true,"b":"19","c":0}""")
        assertNull(obj.number("a"))
        assertNull(obj.number("b"))
        assertEquals(0.0, obj.number("c")!!, 0.0)
    }

    @Test fun forecastSkipsBadTimestampsSortsAndDeduplicates() {
        val slots = WeatherParser.forecast("""{"list":[{"dt":20,"main":{"temp":8}},{"dt":"invalid"},{"dt":10,"main":{"temp":7}},{"dt":20},{"dt":-1},null]}""")
        assertEquals(listOf(10L, 20L), slots.map { it.timestamp })
        assertEquals(8.0, slots.last().temperature!!, 0.0)
    }

    @Test fun precipitationIsClampedAndMissingChanceStaysUnknown() {
        val slots = WeatherParser.forecast("""{"list":[{"dt":1,"pop":1.8,"rain":{"3h":-2},"snow":{"3h":0.8}},{"dt":2,"pop":-1},{"dt":3}]}""")
        assertEquals(100.0, slots[0].precipitation!!, 0.0)
        assertEquals(0.0, slots[1].precipitation!!, 0.0)
        assertNull(slots[2].precipitation)
        assertEquals(0.0, slots[0].rainMm, 0.0)
        assertEquals(0.8, slots[0].snowMm, 0.0)
    }

    @Test fun dailyGroupingUsesTheCityTimezoneAcrossMidnight() {
        val slots = listOf(slot("2026-10-04T20:00:00Z", 12.0), slot("2026-10-04T23:00:00Z", 9.0))
        val days = dailyForecast(slots, 10800)
        assertEquals(listOf(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5)), days.map { it.date })
        assertEquals(LocalDate.of(2026, 10, 3), localTimeAt(epoch("2026-10-04T02:00:00Z"), -18000).toLocalDate())
    }

    @Test fun dailyRangeChanceVolumesAndNoonConditionAreAggregatedCorrectly() {
        val days = dailyForecast(listOf(
            slot("2026-10-04T06:00:00Z", 10.0, 8.0, 11.0, 800, 10.0, 1.2),
            slot("2026-10-04T09:00:00Z", 16.0, 15.0, 18.0, 500, 70.0, 2.3, 0.4),
            slot("2026-10-04T18:00:00Z", 12.0, precipitation = 40.0)
        ), 10800)
        assertEquals(1, days.size)
        assertEquals(8.0, days[0].low!!, 0.0)
        assertEquals(18.0, days[0].high!!, 0.0)
        assertEquals(500, days[0].condition)
        assertEquals(70.0, days[0].precipitation!!, 0.0)
        assertEquals(3.5, days[0].rainMm, 0.001)
        assertEquals(0.4, days[0].snowMm, 0.001)
    }

    @Test fun temperaturesAreFallbacksForMissingDailyRanges() {
        val days = dailyForecast(listOf(slot("2026-10-04T12:00:00Z", -4.0), slot("2026-10-04T15:00:00Z", -1.0)), 0)
        assertEquals(-4.0, days[0].low!!, 0.0)
        assertEquals(-1.0, days[0].high!!, 0.0)
        assertNull(days[0].precipitation)
    }

    @Test fun dailyOutlookIsLimitedToFiveDates() {
        val slots = (1..7).map { slot("2026-10-0${it}T12:00:00Z", it.toDouble()) }
        assertEquals(5, dailyForecast(slots.reversed(), 0).size)
    }

    @Test fun next24HoursExcludePeriodsBeforeFetchAndLimitToEightForLegacyReports() {
        val report = report()
        val slots = (-2..10).map { index ->
            ForecastSlot(report.current.timestamp + index * 10800, 10.0, null, null, 800, "Clear", false, null, 0.0, 0.0)
        }
        val next = report.copy(slots = slots).next24Hours
        assertEquals(8, next.size)
        assertEquals(report.current.timestamp + 10800, next.first().timestamp)
    }

    @Test fun geocodingOffersDistinctValidCities() {
        val cities = WeatherParser.locations("""[{"name":"Kyiv","country":"UA","lat":50.45,"lon":30.52},{"name":"Kyiv","lat":50.45,"lon":30.52},{"name":"Bad","lat":95,"lon":0},{"name":"Missing","lat":0}]""")
        assertEquals(1, cities.size)
        assertEquals("UA", cities.first().detail)
    }

    @Test fun extremeTimezoneOffsetsAreClamped() {
        assertEquals(50400, localTimeAt(0, Int.MAX_VALUE).offset.totalSeconds)
        assertEquals(-50400, localTimeAt(0, Int.MIN_VALUE).offset.totalSeconds)
    }

    @Test fun compassDirectionHandlesWraparoundAndNegativeValues() {
        assertEquals("N", windDirection(360.0))
        assertEquals("N", windDirection(-1.0))
        assertEquals("W", windDirection(-90.0))
        assertEquals("NE", windDirection(45.0))
        assertNull(windDirection(Double.NaN))
        assertNull(windDirection(null))
    }

    @Test fun serviceErrorsAreActionableWithoutCredentials() {
        assertTrue(serviceError(401).contains("Settings"))
        assertTrue(serviceError(429).contains("limit"))
        assertTrue(serviceError(503).contains("temporarily"))
        assertFalse(serviceError(401).contains("appid"))
    }

    @Test fun brokenForecastDoesNotEraseValidCurrentConditions() {
        val parsed = WeatherParser.report(location, WeatherUnits.METRIC, current, null, 1791133300, "Forecast unavailable")
        assertEquals(17.5, parsed.current.temperature, 0.001)
        assertEquals("Forecast unavailable", parsed.forecastNote)
        assertTrue(parsed.slots.isEmpty())
    }
}
