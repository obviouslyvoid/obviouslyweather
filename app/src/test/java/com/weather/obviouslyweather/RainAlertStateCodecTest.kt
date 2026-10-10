// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RainAlertStateCodecTest {
    private val now = Instant.parse("2026-10-09T09:00:00Z").epochSecond
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private val event = RainEventMemory(city.identity, now + 3600, now + 7200, RainLikelihood.HEAVY,
        setOf(now + 3600), now, endingNotified = true)

    @Test fun restartKeepsTheCommunicatedForecastAndDoesNotRepeatTheAlert() {
        val state = RainAlertState(listOf(event))
        val restored = RainAlertStateCodec.decode(RainAlertStateCodec.encode(state))
        assertEquals(state, restored)
        val current = CurrentWeather(now, 10.0, null, 800, "Clear sky", false,
            null, null, null, null, null, null, null, null, null)
        val slots = listOf(
            ForecastSlot(now + 3600, 10.0, null, null, 502, "Rain", false, 80.0, 2.0, 0.0),
            ForecastSlot(now + 7200, 10.0, null, null, 800, "Clear sky", false, 0.0, 0.0, 0.0),
            ForecastSlot(now + 10800, 10.0, null, null, 800, "Clear sky", false, 0.0, 0.0, 0.0))
        val report = WeatherReport(city, WeatherUnits.METRIC, current, slots, 0, now, forecastStepSeconds = 3600)
        assertNull(RainForecastPolicy.evaluate(report, now + 1800, restored,
            QuietHoursPreferences(enabled = false), ZoneId.of("UTC")).notification)
    }

    @Test fun endingAndCancellationFlagsSurviveRestart() {
        val state = RainAlertState(listOf(event.copy(cancelled = true, lastSeenAt = now + 1800)))
        assertEquals(state, RainAlertStateCodec.decode(RainAlertStateCodec.encode(state)))
    }

    @Test fun corruptOrUnsupportedStateDoesNotBreakWeatherLoading() {
        listOf(null, "", "{", "{\"version\":2,\"events\":[]}", "{\"version\":1,\"events\":[{\"start\":-1}]}")
            .forEach { assertEquals(RainAlertState(), RainAlertStateCodec.decode(it)) }
    }

    @Test fun oneBadRecordDoesNotEraseValidRecords() {
        val json = org.json.JSONObject(RainAlertStateCodec.encode(RainAlertState(listOf(event))))
        json.getJSONArray("events").put(org.json.JSONObject().put("likelihood", "BOGUS"))
        assertEquals(RainAlertState(listOf(event)), RainAlertStateCodec.decode(json.toString()))
    }
}
