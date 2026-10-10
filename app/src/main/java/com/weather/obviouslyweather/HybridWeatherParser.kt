// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.json.JSONException
import org.json.JSONObject
import java.time.ZoneId


object HybridWeatherParser {
    private val zoneIds: Set<String> = ZoneId.getAvailableZoneIds()

    fun report(
        location: WeatherLocation, units: WeatherUnits, currentJson: String,
        hourlyJson: String?, forecastJson: String?, fetchedAt: Long, note: String? = null,
        dailyJson: String? = null, dailyFetchedAt: Long? = null, dailyFromCache: Boolean = false
    ): WeatherReport {
        val currentReport = try {
            WeatherParser.report(location, units, currentJson, null, fetchedAt)
        } catch (_: JSONException) {
            throw WeatherFailure("OpenWeather returned unreadable current conditions.")
        }
        val hourlyRoot = hourlyJson?.let { envelope(it, "hourly forecast") }
        val dailyRoot = dailyJson?.let { envelope(it, "daily forecast") }
        val zoneId = listOfNotNull(dailyRoot, hourlyRoot).firstNotNullOfOrNull {
            (it.opt("timezone") as? String)?.takeIf { name -> name in zoneIds }
        }
        val offset = dailyRoot?.number("timezone_offset")?.coerceIn(-50400.0, 50400.0)?.toInt()
            ?: currentReport.offset
        val hourlySlots = hourlyJson?.let { OneCall4Parser.hourly(it) }.orEmpty()
        // Only legacy caches use the three-hour aggregation. New daily ranges are
        // genuine provider records; missing days are never synthesized from hours.
        val dailySlots = if (dailyJson == null) forecastJson?.let { forecast(it) }.orEmpty() else null
        val today = localTimeAt(fetchedAt, offset, zoneId).toLocalDate()
        val daily = if (dailyJson == null) dailyForecast(dailySlots.orEmpty(), offset, zoneId)
            else OneCall4Parser.daily(dailyJson, offset, zoneId)
                .filter { it.date >= today && it.date <= today.plusDays(6) }.take(7)
        val unavailable = when {
            hourlySlots.isEmpty() && daily.isEmpty() -> "The hourly and daily forecasts are currently unavailable."
            hourlySlots.isEmpty() -> "The hourly forecast is currently unavailable."
            daily.isEmpty() -> "The daily forecast is currently unavailable."
            dailyJson != null && daily.size < 7 -> "The rest of the daily forecast is currently unavailable."
            else -> null
        }
        return currentReport.copy(
            slots = hourlySlots, forecastNote = note ?: unavailable,
            providerDaily = daily, zoneId = zoneId, offset = offset,
            forecastStepSeconds = 3600, dailySlots = dailySlots,
            dailyFetchedAt = dailyJson?.let { dailyFetchedAt ?: fetchedAt },
            dailyFromCache = dailyJson != null && dailyFromCache
        )
    }

    /** Reject error envelopes instead of treating them as a successful empty daily forecast. */
    fun forecast(json: String): List<ForecastSlot> {
        val root = envelope(json, "daily forecast")
        val records = root.optJSONArray("list")
            ?: throw WeatherFailure("OpenWeather returned an unreadable daily forecast.")
        val slots = WeatherParser.forecast(json)
        if (records.length() > 0 && slots.isEmpty()) {
            throw WeatherFailure("OpenWeather returned an unreadable daily forecast.")
        }
        return slots
    }

    private fun envelope(json: String, what: String): JSONObject = try {
        JSONObject(json)
    } catch (_: JSONException) {
        throw WeatherFailure("OpenWeather returned an unreadable $what.")
    }
}
