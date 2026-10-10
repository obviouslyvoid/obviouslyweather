// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HybridWeatherParserTest {
    private val location = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private val now = epoch("2026-10-05T09:02:00Z")
    private val current = """{"dt":1791190800,"timezone":10800,"main":{"temp":17.5,"feels_like":16,"humidity":64,"pressure":1023},"wind":{"speed":2.6,"deg":275,"gust":5.2},"clouds":{"all":20},"visibility":10000,"sys":{"sunrise":1791172680,"sunset":1791214020},"weather":[{"id":801,"description":"few clouds","icon":"02d"}]}"""
    private fun epoch(iso: String) = Instant.parse(iso).epochSecond
    private fun hourly(): String = """{"timezone":"Europe/Kyiv","timezone_offset":10800,"data":[{"dt":${now + 3600},"temp":90,"pop":0.65,"rain":{"1h":0.9},"weather":[{"id":500,"description":"light rain","icon":"10d"}]}]}"""
    private fun dailySlot(iso: String, temp: Double, low: Double = temp - 1, high: Double = temp + 1, code: Int = 801, night: Boolean = false): JSONObject =
        JSONObject().put("dt", epoch(iso)).put("main", JSONObject().put("temp", temp).put("temp_min", low).put("temp_max", high))
            .put("weather", JSONArray().put(JSONObject().put("id", code).put("description", "Condition $code").put("icon", if (night) "04n" else "04d")))
            .put("pop", 0.4).put("rain", JSONObject().put("3h", 0.6))
    private fun forecast(vararg slots: JSONObject) = JSONObject().put("list", JSONArray(slots.toList())).toString()
    private fun daily(): String = forecast(
        dailySlot("2026-10-05T09:00:00Z", 18.0, 15.0, 20.0, 801),
        dailySlot("2026-10-05T21:00:00Z", 9.0, 8.0, 10.0, 804, true)
    )
    private fun report(hourly: String? = hourly(), daily: String? = daily(), current: String = this.current, fetchedAt: Long = now) =
        HybridWeatherParser.report(location, WeatherUnits.METRIC, current, hourly, daily, fetchedAt)
    private fun failure(action: () -> Any?) {
        try {
            action()
            fail("Unreadable data must fail explicitly")
        } catch (error: WeatherFailure) {
            assertFalse(error.message.isNullOrBlank())
            assertFalse(error.message!!.contains("appid"))
        }
    }

    @Test fun currentUsesTheStandardWeatherSchemaWithAllExistingDetails() {
        val parsed = report()
        assertEquals(17.5, parsed.current.temperature, 0.0)
        assertEquals(16.0, parsed.current.feelsLike!!, 0.0)
        assertEquals(64.0, parsed.current.humidity!!, 0.0)
        assertEquals(1023.0, parsed.current.pressure!!, 0.0)
        assertEquals(2.6, parsed.current.windSpeed!!, 0.0)
        assertEquals(275.0, parsed.current.windDegrees!!, 0.0)
        assertEquals(5.2, parsed.current.gust!!, 0.0)
        assertEquals(20.0, parsed.current.clouds!!, 0.0)
        assertEquals(10000.0, parsed.current.visibility!!, 0.0)
        assertEquals(1791172680L, parsed.current.sunrise)
        assertEquals(1791214020L, parsed.current.sunset)
        assertEquals("Few clouds", parsed.current.description)
        assertFalse(parsed.current.night)
        assertEquals(now, parsed.fetchedAt)
        assertNull(parsed.forecastNote)
    }

    @Test fun hourlyAndDailyValuesStayWithTheirOwnSources() {
        val parsed = report(daily = forecast(
            dailySlot("2026-10-05T09:00:00Z", 18.0, 15.0, 20.0, 801),
            dailySlot("2026-10-05T18:00:00Z", 9.0, 8.0, 10.0, 804, true)
        ))
        assertEquals(1, parsed.slots.size)
        assertEquals(90.0, parsed.slots.single().temperature!!, 0.0)
        assertEquals(65.0, parsed.slots.single().precipitation!!, 0.0)
        assertEquals(0.9, parsed.slots.single().rainMm, 0.0)
        assertNull(parsed.slots.single().low)
        assertNull(parsed.slots.single().high)
        assertEquals(3600, parsed.forecastStepSeconds)
        assertEquals(2, parsed.dailySlots!!.size)
        val day = parsed.daily.single()
        assertEquals(8.0, day.low!!, 0.0)
        assertEquals(20.0, day.high!!, 0.0)
        assertEquals(801, day.condition)
        assertEquals(1.2, day.rainMm, 0.001)
        assertEquals(40.0, day.precipitation!!, 0.0)
    }

    @Test fun oldThreeHourForecastIsNotAnHourlyFallback() {
        val parsed = report(hourly = null)
        assertTrue(parsed.slots.isEmpty())
        assertTrue(parsed.next24Hours.isEmpty())
        assertFalse(parsed.daily.isEmpty())
        assertFalse(parsed.dailySlots!!.isEmpty())
        assertEquals("The hourly forecast is currently unavailable.", parsed.forecastNote)
    }

    @Test fun missingDailyDataDoesNotCreateRangesFromHourlyTemperatures() {
        val parsed = report(daily = null)
        assertFalse(parsed.slots.isEmpty())
        assertTrue(parsed.daily.isEmpty())
        assertEquals(emptyList<DailyWeather>(), parsed.providerDaily)
        assertEquals(emptyList<ForecastSlot>(), parsed.dailySlots)
        assertEquals("The daily forecast is currently unavailable.", parsed.forecastNote)
        assertTrue(report(daily = """{"list":[]}""").daily.isEmpty())
    }

    @Test fun missingBothForecastsKeepsValidCurrentConditionsAndMarksBothUnavailable() {
        val parsed = report(hourly = null, daily = null)
        assertEquals(17.5, parsed.current.temperature, 0.0)
        assertTrue(parsed.slots.isEmpty())
        assertTrue(parsed.daily.isEmpty())
        assertEquals("The hourly and daily forecasts are currently unavailable.", parsed.forecastNote)
        val withReason = HybridWeatherParser.report(location, WeatherUnits.IMPERIAL, current, null, null, now, "Forecast service unavailable")
        assertEquals(WeatherUnits.IMPERIAL, withReason.units)
        assertEquals("Forecast service unavailable", withReason.forecastNote)
    }

    @Test fun malformedDailyResponsesCannotLookLikeSuccessfulEmptyForecasts() {
        listOf("bad JSON", "[]", "{}", """{"list":{}}""", """{"list":[{},null,{"dt":"bad"},{"dt":-1}]}""").forEach { invalid ->
            failure { HybridWeatherParser.forecast(invalid) }
            failure { report(daily = invalid) }
        }
        assertTrue(HybridWeatherParser.forecast("""{"list":[]}""").isEmpty())
    }

    @Test fun currentOneCallEnvelopeOrMissingCurrentValuesAreRejected() {
        listOf("bad JSON", "[]", "{}", """{"data":[{"dt":1,"temp":17}]}""", """{"main":{},"dt":1}""", """{"main":{"temp":17}}""").forEach { invalid ->
            failure { report(current = invalid) }
        }
    }

    @Test fun malformedHourlyForecastCannotFallBackToTheStandardForecast() {
        listOf("{}", """{"list":[]}""", """{"data":[{}]}""").forEach { invalid -> failure { report(hourly = invalid) } }
    }

    @Test fun hourlyIanaTimezoneUsesDstAndAbsentOrInvalidZoneUsesLegacyCurrentOffset() {
        val parsed = report(hourly = """{"timezone":"Europe/Kyiv","timezone_offset":-18000,"data":[]}""")
        assertEquals("Europe/Kyiv", parsed.zoneId)
        assertEquals(10800, parsed.offset)
        assertEquals(7200, parsed.localTime(epoch("2026-10-26T12:00:00Z")).offset.totalSeconds)
        val invalid = report(hourly = """{"timezone":"invalid/zone","timezone_offset":-18000,"data":[]}""")
        assertNull(invalid.zoneId)
        assertEquals(10800, invalid.localTime(now).offset.totalSeconds)
        assertEquals(10800, report(hourly = null).localTime(now).offset.totalSeconds)
    }

    @Test fun legacyDailyGroupingUsesTheHourlyIanaZoneAfterDstRatherThanAnOldOffset() {
        val parsed = report(daily = forecast(
            dailySlot("2026-10-25T21:30:00Z", 10.0),
            dailySlot("2026-10-25T22:30:00Z", 8.0)
        ))
        assertEquals(listOf(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-26")), parsed.daily.map { it.date })
        assertEquals(10.0, parsed.dailySlots!!.first().temperature!!, 0.0)
    }

    @Test fun dailyRepresentativeConditionUsesActualLocalNoonAcrossDst() {
        val parsed = report(daily = forecast(
            dailySlot("2026-10-26T09:00:00Z", 10.0, code = 800),
            dailySlot("2026-10-26T10:30:00Z", 11.0, code = 500)
        ))
        assertEquals(500, parsed.daily.single().condition)
        assertEquals(800, dailyForecast(parsed.dailySlots!!, parsed.offset).single().condition)
    }

    @Test fun dailyForecastRetainsFiveDaysAndRealLaterNightSlots() {
        val records = (0L..5L).flatMap { index ->
            val day = LocalDate.parse("2026-10-05").plusDays(index)
            listOf(dailySlot("${day}T09:00:00Z", 18.0, code = 801), dailySlot("${day}T18:00:00Z", 9.0, code = 804, night = true))
        }
        val parsed = report(daily = forecast(*records.toTypedArray()))
        assertEquals(5, parsed.daily.size)
        assertEquals(12, parsed.dailySlots!!.size)
        assertTrue(parsed.dailySlots.any { it.timestamp > now + 3 * 86400 && it.night })
        assertEquals(1, parsed.slots.size)
    }

    private fun oneCallDays(first: String, count: Int, zone: String = "Europe/Kyiv", offset: Int = 10800): String {
        val date = LocalDate.parse(first)
        val data = JSONArray()
        repeat(count) { day ->
            val timestamp = date.plusDays(day.toLong()).atStartOfDay(java.time.ZoneId.of(zone)).toEpochSecond()
            data.put(JSONObject().put("dt", timestamp)
                .put("temp", JSONObject().put("min", 5.0 + day).put("max", 20.0 + day))
                .put("pop", 0.2).put("rain", 1.25)
                .put("weather", JSONArray().put(JSONObject().put("id", 500).put("icon", "10d"))))
        }
        return JSONObject().put("timezone", zone).put("timezone_offset", offset).put("data", data).toString()
    }

    @Test fun oneCallDailyWindowUsesSevenRealDaysIncludingTodayWithoutHourlyRanges() {
        val parsed = HybridWeatherParser.report(location, WeatherUnits.METRIC, current, hourly(), null, now,
            dailyJson = oneCallDays("2026-10-04", 10))
        assertEquals((0L..6L).map { LocalDate.of(2026, 10, 5).plusDays(it) }, parsed.daily.map { it.date })
        assertEquals(6.0, parsed.daily.first().low!!, 0.0)
        assertEquals(21.0, parsed.daily.first().high!!, 0.0)
        assertEquals(20.0, parsed.daily.first().precipitation!!, 0.0)
        assertEquals(1.25, parsed.daily.first().rainMm, 0.0)
        assertNull(parsed.dailySlots)
        assertEquals(90.0, parsed.slots.single().temperature!!, 0.0)
        assertNull(parsed.forecastNote)
    }

    @Test fun oneCallDailyTimezoneStillControlsDatesWhenHourlyFailsNearUtcMidnight() {
        val nearMidnight = epoch("2026-10-05T22:30:00Z")
        val parsed = HybridWeatherParser.report(location, WeatherUnits.METRIC, current, null, null, nearMidnight,
            dailyJson = oneCallDays("2026-10-05", 10))
        assertEquals("Europe/Kyiv", parsed.zoneId)
        assertEquals(LocalDate.of(2026, 10, 6), parsed.daily.first().date)
        assertEquals(LocalDate.of(2026, 10, 12), parsed.daily.last().date)
        assertEquals(7, parsed.daily.size)
        assertEquals("The hourly forecast is currently unavailable.", parsed.forecastNote)
    }

    @Test fun oneCallDailyWindowIncludesTodayAcrossTwentyFiveHourDstDay() {
        val lateDstDay = epoch("2026-10-25T21:59:00Z")
        val parsed = HybridWeatherParser.report(location, WeatherUnits.METRIC, current, null, null, lateDstDay,
            dailyJson = oneCallDays("2026-10-24", 10))
        assertEquals(LocalDate.of(2026, 10, 25), parsed.daily.first().date)
        assertEquals(LocalDate.of(2026, 10, 31), parsed.daily.last().date)
        assertEquals(7, parsed.daily.size)
    }

    @Test fun oneCallDailyOffsetFallbackUsesItsMetadataWhenBothIanaZonesAreMissing() {
        val dailyData = JSONArray()
        repeat(10) { day ->
            dailyData.put(JSONObject().put("dt", epoch("2026-10-05T05:00:00Z") + day * 86400L)
                .put("temp", JSONObject().put("min", day).put("max", day + 10)))
        }
        val dailyRoot = JSONObject().put("timezone", "invalid/zone").put("timezone_offset", -18000)
            .put("data", dailyData)
        val parsed = HybridWeatherParser.report(location, WeatherUnits.METRIC, current, null, null,
            epoch("2026-10-06T02:30:00Z"), dailyJson = dailyRoot.toString())
        assertNull(parsed.zoneId)
        assertEquals(-18000, parsed.offset)
        assertEquals(LocalDate.of(2026, 10, 5), parsed.daily.first().date)
        assertEquals(LocalDate.of(2026, 10, 11), parsed.daily.last().date)
        assertEquals(7, parsed.daily.size)
    }

    @Test fun invalidDailyZoneUsesTheValidHourlyZoneBeforeFallingBackToOffset() {
        val dailyRoot = JSONObject(oneCallDays("2026-10-05", 10))
            .put("timezone", "invalid/zone").put("timezone_offset", -18000)
        val parsed = HybridWeatherParser.report(location, WeatherUnits.METRIC, current, hourly(), null,
            epoch("2026-10-05T22:30:00Z"), dailyJson = dailyRoot.toString())
        assertEquals("Europe/Kyiv", parsed.zoneId)
        assertEquals(LocalDate.of(2026, 10, 6), parsed.daily.first().date)
        assertEquals(LocalDate.of(2026, 10, 12), parsed.daily.last().date)
        assertEquals(7, parsed.daily.size)
    }

    @Test fun oneCallDailyWindowDoesNotFillMissingCalendarDaysWithLaterRecords() {
        val dailyRoot = JSONObject(oneCallDays("2026-10-04", 10))
        // Remove today + 2 while keeping later records past the displayed week.
        dailyRoot.getJSONArray("data").remove(3)
        val parsed = HybridWeatherParser.report(location, WeatherUnits.METRIC, current, hourly(), null, now,
            dailyJson = dailyRoot.toString())
        assertEquals(6, parsed.daily.size)
        assertFalse(parsed.daily.any { it.date == LocalDate.of(2026, 10, 7) })
        assertEquals(LocalDate.of(2026, 10, 11), parsed.daily.last().date)
        assertEquals("The rest of the daily forecast is currently unavailable.", parsed.forecastNote)
    }

    @Test fun dailyRefreshMetadataKeepsItsOwnAgeWhenCoreConditionsAreNewer() {
        val earlierDailyRefresh = now - 3600
        val parsed = HybridWeatherParser.report(location, WeatherUnits.METRIC, current, hourly(), null, now,
            dailyJson = oneCallDays("2026-10-05", 7), dailyFetchedAt = earlierDailyRefresh, dailyFromCache = true)
        assertEquals(now, parsed.fetchedAt)
        assertEquals(earlierDailyRefresh, parsed.dailyFetchedAt)
        assertTrue(parsed.dailyFromCache)
        assertFalse(parsed.dailyLoading)
    }

    @Test fun olderDailyCallersInferSourceTimeFromTheirActualRefreshTime() {
        val parsed = HybridWeatherParser.report(location, WeatherUnits.METRIC, current, hourly(), null, now,
            dailyJson = oneCallDays("2026-10-05", 7))
        assertEquals(now, parsed.dailyFetchedAt)
        assertFalse(parsed.dailyFromCache)
    }

    @Test fun absentDailyDataCannotInheritCachedSourceMetadata() {
        val parsed = HybridWeatherParser.report(location, WeatherUnits.METRIC, current, hourly(), null, now,
            dailyFetchedAt = now - 3600, dailyFromCache = true)
        assertNull(parsed.dailyFetchedAt)
        assertFalse(parsed.dailyFromCache)
    }
}
