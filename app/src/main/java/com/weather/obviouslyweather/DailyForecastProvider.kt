// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.json.JSONException
import org.json.JSONObject

enum class DailyForecastProvider(val label: String, internal val path: String) {
    OCA4("One Call 4.0", "/data/4.0/onecall/timeline/1day"),
    OCA3("One Call 3.0", "/data/3.0/onecall");

    internal fun parameters(base: Map<String, String>): Map<String, String> =
        if (this == OCA3) base + ("exclude" to "current,minutely,hourly,alerts") else base

    internal fun cacheJson(raw: String): String {
        if (this == OCA4) return cacheSafeJson(raw, "daily forecast")
        return try {
            val root = JSONObject(raw)
            val safe = JSONObject()
            for (name in listOf("lat", "lon", "timezone", "timezone_offset")) {
                if (root.has(name)) safe.put(name, root.get(name))
            }
            // Both providers use the existing daily cache format.
            safe.put("data", root.getJSONArray("daily")).toString()
        } catch (_: JSONException) {
            throw WeatherFailure("OpenWeather returned an unreadable daily forecast.")
        }
    }

    companion object {
        fun fromStoredValue(value: String?): DailyForecastProvider = entries.firstOrNull { it.name == value } ?: OCA4

        internal fun isDailyPath(path: String): Boolean = entries.any { it.path == path }
    }
}
