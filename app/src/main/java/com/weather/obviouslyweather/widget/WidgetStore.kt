// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.content.Context
import android.text.format.DateFormat
import com.weather.obviouslyweather.WeatherStore

class WidgetStore(context: Context) {
    private companion object { val clockLock = Any() }
    private val applicationContext = context.applicationContext
    private val preferences = context.getSharedPreferences("weather_widgets", Context.MODE_PRIVATE)
    private fun use24Hour() = WeatherStore(applicationContext).display().use24Hour(DateFormat.is24HourFormat(applicationContext))

    fun read(id: Int): WidgetPreferences {
        val use24Hour = use24Hour()
        // Also reset when a SYSTEM clock changes outside the app.
        syncClock(use24Hour)
        return WidgetPreferences(
            runCatching { WidgetForecastMode.valueOf(preferences.getString("${id}_forecast", null) ?: "THREE_HOUR") }.getOrDefault(WidgetForecastMode.THREE_HOUR),
            preferences.getBoolean("${id}_background", false),
            runCatching { WidgetTextColor.valueOf(preferences.getString("${id}_text", null) ?: "WHITE") }.getOrDefault(WidgetTextColor.WHITE),
            preferences.getBoolean("${id}_hide_am_pm", false)
        ).normalizedForClock(use24Hour)
    }

    fun save(id: Int, settings: WidgetPreferences): Boolean = synchronized(clockLock) {
        val use24Hour = use24Hour()
        syncClock(use24Hour)
        val saved = settings.normalizedForClock(use24Hour)
        preferences.edit()
            .putString("${id}_forecast", saved.forecast.name).putBoolean("${id}_background", saved.background)
            .putString("${id}_text", saved.textColor.name).putBoolean("${id}_hide_am_pm", saved.hideAmPm).commit()
    }

    fun hiddenAmPmResetVersion(): Long = preferences.getLong("hide_am_pm_reset_version", 0L)

    fun syncClock(use24Hour: Boolean) = synchronized(clockLock) {
        val previous24Hour = preferences.getBoolean("clock_24_hour", false)
        val hidden = if (use24Hour) preferences.all.filter { (key, value) -> key.endsWith("_hide_am_pm") && value == true }.keys
            else emptySet()
        if (previous24Hour == use24Hour && hidden.isEmpty()) return@synchronized
        val editor = preferences.edit()
            .putBoolean("clock_24_hour", use24Hour)
        if (use24Hour) {
            hidden.forEach(editor::remove)
            // Invalidate a paused settings draft even if the clock returns to 12 hours before resume.
            editor.putLong("hide_am_pm_reset_version", hiddenAmPmResetVersion() + 1)
        }
        editor.apply()
    }

    fun delete(id: Int) {
        preferences.edit().remove("${id}_forecast").remove("${id}_background").remove("${id}_text").remove("${id}_hide_am_pm").apply()
    }
    fun restore(oldId: Int, newId: Int) { save(newId, read(oldId)); delete(oldId) }
}
