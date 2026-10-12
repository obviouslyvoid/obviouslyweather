// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ForecastRegressionTest {
    private val hour = Instant.parse("2026-10-12T12:00:00Z").epochSecond
    private val now = hour + 17 * 60
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private fun slot(time: Long, wet: Boolean) = ForecastSlot(time, 20.0, null, null,
        if (wet) 500 else 800, if (wet) "Rain" else "Clear", false,
        if (wet) 80.0 else 0.0, if (wet) 1.0 else 0.0, 0.0)
    private fun report(slots: List<ForecastSlot>) = WeatherReport(city, WeatherUnits.METRIC,
        CurrentWeather(now, 20.0, null, 500, "Rain", false, null, null, null, null, null, null,
            null, null, null), slots, 0, now, forecastStepSeconds = 3600)
    private fun evaluate(weather: WeatherReport, at: Long = now, state: RainAlertState = RainAlertState()) =
        RainForecastPolicy.evaluate(weather, at, state, quietHours = QuietHoursPreferences(enabled = false))

    @Test fun repositoryPreservesTheCurrentHourForEndingNotificationsWithoutMoreCalls() = runBlocking {
        val records = listOf(slot(hour, true), slot(hour + 3600, false), slot(hour + 7200, false))
        val hourly = JSONObject().put("timezone", "UTC").put("data", JSONArray(records.map {
            JSONObject().put("dt", it.timestamp).put("temp", it.temperature).put("pop", it.precipitation!! / 100)
                .put("rain", JSONObject().put("1h", it.rainMm))
                .put("weather", JSONArray().put(JSONObject().put("id", it.condition)))
        })).toString()
        val current = JSONObject().put("dt", now).put("timezone", 0).put("main", JSONObject().put("temp", 20))
            .put("weather", JSONArray().put(JSONObject().put("id", 500))).toString()
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val repository = WeatherRepository({ path, parameters, _ ->
            calls += path
            when (path) {
                "/data/2.5/weather" -> current
                "/data/4.0/onecall/timeline/1h" -> {
                    assertEquals(hour.toString(), parameters["start"])
                    hourly
                }
                else -> """{"timezone":"UTC","data":[]}"""
            }
        }, { now })
        val weather = repository.weather(city, WeatherUnits.METRIC, "test-key").report
        assertEquals(records.map { it.timestamp }, weather.slots.map { it.timestamp })
        val notice = evaluate(weather).notification!!
        assertEquals(RainNoticeKind.ENDING, notice.kind)
        assertEquals(hour + 3600, notice.end)
        assertEquals(3, calls.size)
        assertEquals(listOf(hour + 3600, hour + 7200), weather.next24Hours(now).map { it.timestamp })
    }

    @Test fun freshObservedRainCanEndEvenIfTheApiOmitsTheCurrentHour() {
        val weather = report(listOf(slot(hour + 3600, false), slot(hour + 7200, false)))
        val first = evaluate(weather)
        assertEquals(RainNoticeKind.ENDING, first.notification!!.kind)
        assertEquals(hour + 3600, first.notification!!.end)
        assertNull(evaluate(weather, now + 60, first.state).notification)
        assertNull(evaluate(weather, now + 3600, first.state).notification)
        assertNull(evaluate(weather.copy(current = weather.current.copy(timestamp = now - 7200))).notification)
        assertNull(evaluate(weather.copy(slots = weather.slots.take(1))).notification)
        assertNull(evaluate(weather.copy(slots = listOf(slot(hour + 7200, false), slot(hour + 10800, false)))).notification)
        assertNull(evaluate(weather.copy(slots = weather.slots.map { it.copy(precipitation = null) })).notification)
    }

    @Test fun cachedHourlyWindowFollowsDisplayTimeAcrossDateChanges() {
        val weather = report((0..48).map { slot(hour + it * 3600, false) })
        val next = weather.next24Hours(hour + 26 * 3600)
        assertEquals(23, next.size)
        assertEquals(hour + 26 * 3600, next.first().timestamp)
        assertTrue(next.all { it.timestamp >= hour + 26 * 3600 })
        assertTrue(weather.next24Hours(hour + 49 * 3600).isEmpty())
    }
}
