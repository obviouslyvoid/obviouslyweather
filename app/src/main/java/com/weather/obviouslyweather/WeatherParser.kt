// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.json.JSONArray
import org.json.JSONObject

class WeatherFailure(message: String) : Exception(message)

internal fun JSONObject.number(name: String): Double? = (opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() }
private fun JSONObject.epoch(name: String): Long? = number(name)?.takeIf { it in 0.0..253402300799.0 }?.toLong()
private fun JSONObject.description(): String = optJSONArray("weather")?.optJSONObject(0)?.let {
    it.optString("description").ifBlank { it.optString("main") }
}?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() } ?: "Unknown conditions"
private fun JSONObject.condition(): Int? = optJSONArray("weather")?.optJSONObject(0)?.number("id")?.toInt()
private fun JSONObject.isNight(): Boolean = optJSONArray("weather")?.optJSONObject(0)?.optString("icon")?.endsWith("n") == true

object WeatherParser {
    fun locations(json: String): List<WeatherLocation> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            location(obj)
        }.distinctBy { it.identity }
    }

    fun location(obj: JSONObject): WeatherLocation? {
        val lat = obj.number("lat")?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = obj.number("lon")?.takeIf { it in -180.0..180.0 } ?: return null
        val name = obj.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        return WeatherLocation(name, obj.optString("country"), obj.optString("state").takeIf { it.isNotBlank() }, lat, lon)
    }

    fun report(location: WeatherLocation, units: WeatherUnits, currentJson: String, forecastJson: String?, fetchedAt: Long, note: String? = null): WeatherReport {
        val obj = JSONObject(currentJson)
        val main = obj.optJSONObject("main") ?: throw WeatherFailure("OpenWeather returned unreadable current conditions.")
        val temp = main.number("temp") ?: throw WeatherFailure("OpenWeather returned unreadable current conditions.")
        val timestamp = obj.epoch("dt") ?: throw WeatherFailure("OpenWeather returned an invalid update time.")
        val wind = obj.optJSONObject("wind")
        val sys = obj.optJSONObject("sys")
        val offset = (obj.number("timezone") ?: 0.0).coerceIn(-50400.0, 50400.0).toInt()
        val current = CurrentWeather(
            timestamp, temp, main.number("feels_like"), obj.condition(), obj.description(), obj.isNight(),
            main.number("humidity"), main.number("pressure"), obj.number("visibility"), wind?.number("speed"),
            wind?.number("deg"), wind?.number("gust"), obj.optJSONObject("clouds")?.number("all"), sys?.epoch("sunrise"), sys?.epoch("sunset")
        )
        val slots = forecastJson?.let { forecast(it) }.orEmpty()
        return WeatherReport(location, units, current, slots, offset, fetchedAt,
            note ?: if (slots.isEmpty()) "The forecast is currently unavailable." else null)
    }

    fun forecast(json: String): List<ForecastSlot> {
        val entries = JSONObject(json).optJSONArray("list") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            val obj = entries.optJSONObject(index) ?: return@mapNotNull null
            val timestamp = obj.epoch("dt") ?: return@mapNotNull null
            val main = obj.optJSONObject("main")
            ForecastSlot(timestamp, main?.number("temp"), main?.number("temp_min"), main?.number("temp_max"),
                obj.condition(), obj.description(), obj.isNight(), obj.number("pop")?.coerceIn(0.0, 1.0)?.times(100),
                obj.optJSONObject("rain")?.number("3h")?.coerceAtLeast(0.0) ?: 0.0,
                obj.optJSONObject("snow")?.number("3h")?.coerceAtLeast(0.0) ?: 0.0)
        }.sortedBy { it.timestamp }.distinctBy { it.timestamp }
    }

    fun locationJson(location: WeatherLocation): JSONObject = JSONObject().put("name", location.name)
        .put("country", location.country).put("state", location.state ?: "").put("lat", location.lat).put("lon", location.lon)
}
