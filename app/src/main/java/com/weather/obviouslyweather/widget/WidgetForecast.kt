// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import com.weather.obviouslyweather.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// Keep the persisted name so existing forecast widgets retain their selection.
enum class WidgetForecastMode { THREE_HOUR, DAILY }
enum class WidgetTextColor { WHITE, BLACK }
data class WidgetPreferences(
    val forecast: WidgetForecastMode = WidgetForecastMode.THREE_HOUR,
    val background: Boolean = false,
    val textColor: WidgetTextColor = WidgetTextColor.WHITE,
    val hideAmPm: Boolean = false
) {
    fun normalizedForClock(use24Hour: Boolean): WidgetPreferences =
        if (use24Hour && hideAmPm) copy(hideAmPm = false) else this
}
data class WidgetForecastRow(
    val label: String, val value: String, val chance: String?,
    val condition: Int?, val night: Boolean,
    val secondCondition: Int? = null, val secondNight: Boolean = false,
    val description: String = ""
)

internal fun widgetTemperature(value: Double?): String =
    if (value == null || !value.isFinite()) "—" else String.format(Locale.getDefault(), "%.0f°", value)

/** Widget windows follow the phone's current time, never an old provider observation. */
fun widgetForecastRows(report: WeatherReport, mode: WidgetForecastMode, now: Long, use24Hour: Boolean,
                       hideAmPm: Boolean = false): List<WidgetForecastRow> {
    if (mode == WidgetForecastMode.THREE_HOUR) {
        val formatter = if (!use24Hour && hideAmPm) DateTimeFormatter.ofPattern("hh:mm", Locale.getDefault())
            else clockFormatter(use24Hour)
        return report.slots.filter { it.timestamp >= now }.sortedBy { it.timestamp }.take(4).map {
            WidgetForecastRow(report.localTime(it.timestamp).format(formatter), widgetTemperature(it.temperature),
                it.precipitation?.takeIf { chance -> chance.isFinite() }?.let { chance -> "${chance.coerceIn(0.0, 100.0).toInt()}%" },
                it.condition, it.night, description = it.description)
        }
    }
    val today = report.localTime(now).toLocalDate()
    return report.daily.filter { it.date >= today }.take(4).map { day ->
        val slots = (report.dailySlots ?: report.slots).filter { report.localTime(it.timestamp).toLocalDate() == day.date }
        fun nearest(night: Boolean, hour: Int) = slots.filter { it.night == night }
            .minByOrNull { abs(report.localTime(it.timestamp).hour - hour) }
        val daytime = nearest(false, 12)
        val nighttime = nearest(true, 23)
        if (report.providerDaily != null && report.dailySlots == null) {
            // Use the daily condition's night artwork beyond hourly coverage.
            return@map WidgetForecastRow(
                if (day.date == today) "Today" else day.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
                "${widgetTemperature(day.high)} ${widgetTemperature(day.low)}", null,
                day.condition, false, nighttime?.condition ?: day.condition, true, day.description)
        }
        val first = daytime ?: nighttime
        // Saved three-hour forecasts also keep night artwork when a night slot is absent.
        WidgetForecastRow(if (day.date == today) "Today" else day.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
            "${widgetTemperature(day.high)} ${widgetTemperature(day.low)}", null,
            first?.condition ?: day.condition, first?.night ?: false,
            nighttime?.condition ?: day.condition ?: first?.condition, true, day.description)
    }
}

fun widgetTodayRange(report: WeatherReport, now: Long): String {
    val day = report.daily.firstOrNull { it.date == report.localTime(now).toLocalDate() } ?: return "— / —"
    return "↑${widgetTemperature(day.high)} / ↓${widgetTemperature(day.low)}"
}
