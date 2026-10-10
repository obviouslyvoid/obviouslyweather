// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.ZoneId


object OneCall4Parser {
    private val zoneIds: Set<String> = ZoneId.getAvailableZoneIds()
    fun report(
        location: WeatherLocation, units: WeatherUnits, currentJson: String,
        hourlyJson: String?, dailyJson: String?, fetchedAt: Long, note: String? = null
    ): WeatherReport {
        val root = envelope(currentJson, "current conditions")
        val currentRecord = records(root, "current conditions").optJSONObject(0)
            ?: throw WeatherFailure("OpenWeather returned unreadable current conditions.")
        val timestamp = currentRecord.epoch("dt")
            ?: throw WeatherFailure("OpenWeather returned an invalid update time.")
        val temperature = currentRecord.number("temp")
            ?: throw WeatherFailure("OpenWeather returned unreadable current conditions.")
        val hourlyRoot = hourlyJson?.let { envelope(it, "hourly forecast") }
        val dailyRoot = dailyJson?.let { envelope(it, "daily forecast") }
        val roots = listOfNotNull(root, hourlyRoot, dailyRoot)
        val zoneId = roots.firstNotNullOfOrNull { it.validZone() }
        val offset = roots.firstNotNullOfOrNull { it.number("timezone_offset") }
            ?.coerceIn(-50400.0, 50400.0)?.toInt() ?: 0
        val slots = hourlyRoot?.let { hourly(it) }.orEmpty()
        val daily = dailyRoot?.let { daily(it, offset, zoneId) }.orEmpty()
        val current = CurrentWeather(
            timestamp, temperature, currentRecord.number("feels_like"), currentRecord.condition(),
            currentRecord.description(), currentRecord.isNight(), currentRecord.number("humidity"),
            currentRecord.number("pressure"), currentRecord.number("visibility"),
            currentRecord.number("wind_speed"), currentRecord.number("wind_deg"),
            currentRecord.number("wind_gust"), currentRecord.number("clouds"),
            currentRecord.epoch("sunrise"), currentRecord.epoch("sunset")
        )
        val unavailable = when {
            slots.isEmpty() && daily.isEmpty() -> "The forecast is currently unavailable."
            slots.isEmpty() -> "The hourly forecast is currently unavailable."
            daily.isEmpty() -> "The daily forecast is currently unavailable."
            else -> null
        }
        return WeatherReport(location, units, current, slots, offset, fetchedAt,
            note ?: unavailable, daily, zoneId, 3600)
    }

    fun hourly(json: String): List<ForecastSlot> = hourly(envelope(json, "hourly forecast"))

    fun daily(json: String, offset: Int, zoneId: String? = null): List<DailyWeather> =
        daily(envelope(json, "daily forecast"), offset, zoneId)

    private fun hourly(root: JSONObject): List<ForecastSlot> {
        val data = records(root, "hourly forecast")
        val slots = data.objects().mapNotNull { record ->
            val timestamp = record.epoch("dt") ?: return@mapNotNull null
            ForecastSlot(timestamp, record.number("temp"), null, null,
                record.condition(), record.description(), record.isNight(), record.chance(),
                record.hourAmount("rain"), record.hourAmount("snow"))
        }.sortedBy { it.timestamp }.distinctBy { it.timestamp }
        if (data.length() > 0 && slots.isEmpty()) throw WeatherFailure("OpenWeather returned an unreadable hourly forecast.")
        return slots
    }

    private fun daily(root: JSONObject, offset: Int, zoneId: String?): List<DailyWeather> {
        val data = records(root, "daily forecast")
        val zone = zoneId?.takeIf { it in zoneIds } ?: root.validZone()
        val days = data.objects().mapNotNull { record ->
            val timestamp = record.epoch("dt") ?: return@mapNotNull null
            val temperature = record.optJSONObject("temp")
            timestamp to DailyWeather(localTimeAt(timestamp, offset, zone).toLocalDate(),
                temperature?.number("min"), temperature?.number("max"), record.condition(),
                record.description(), record.chance(), record.dayAmount("rain"), record.dayAmount("snow"))
        }.sortedBy { it.first }.distinctBy { it.second.date }.map { it.second }
        if (data.length() > 0 && days.isEmpty()) throw WeatherFailure("OpenWeather returned an unreadable daily forecast.")
        return days
    }

    private fun envelope(json: String, what: String): JSONObject = try {
        JSONObject(json)
    } catch (_: JSONException) {
        throw WeatherFailure("OpenWeather returned an unreadable $what.")
    }

    private fun records(root: JSONObject, what: String): JSONArray = root.optJSONArray("data")
        ?: throw WeatherFailure("OpenWeather returned an unreadable $what.")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.validZone(): String? = (opt("timezone") as? String)
        ?.takeIf { it in zoneIds }
    private fun JSONObject.epoch(name: String): Long? = number(name)
        ?.takeIf { it in 0.0..253402300799.0 && it == kotlin.math.floor(it) }?.toLong()
    private fun JSONObject.weather(): JSONObject? = optJSONArray("weather")?.optJSONObject(0)
    private fun JSONObject.condition(): Int? = weather()?.number("id")
        ?.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() && it == kotlin.math.floor(it) }?.toInt()
    private fun JSONObject.description(): String = weather()?.let { descriptor ->
        (descriptor.opt("description") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (descriptor.opt("main") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }?.replaceFirstChar { it.uppercase() } ?: "Unknown conditions"
    private fun JSONObject.isNight(): Boolean = (weather()?.opt("icon") as? String)?.endsWith("n") == true
    private fun JSONObject.chance(): Double? = number("pop")?.coerceIn(0.0, 1.0)?.times(100)
    private fun JSONObject.hourAmount(name: String): Double =
        optJSONObject(name)?.number("1h")?.coerceAtLeast(0.0) ?: 0.0
    private fun JSONObject.dayAmount(name: String): Double =
        (number(name) ?: optJSONObject(name)?.number("1h"))?.coerceAtLeast(0.0) ?: 0.0
}
