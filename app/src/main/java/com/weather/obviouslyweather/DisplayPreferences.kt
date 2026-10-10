// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ClockStyle { SYSTEM, TWELVE_HOUR, TWENTY_FOUR_HOUR }
enum class MetricWind { METRES_PER_SECOND, KILOMETRES_PER_HOUR }
data class DisplayPreferences(
    val clock: ClockStyle = ClockStyle.SYSTEM,
    val metricWind: MetricWind = MetricWind.METRES_PER_SECOND,
    val refreshOnOpen: Boolean = true
) {
    fun use24Hour(system24Hour: Boolean): Boolean = when (clock) {
        ClockStyle.SYSTEM -> system24Hour
        ClockStyle.TWELVE_HOUR -> false
        ClockStyle.TWENTY_FOUR_HOUR -> true
    }
}

fun clockFormatter(use24Hour: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "hh:mm a", Locale.getDefault())

fun refreshClock(timestamp: Long, use24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(timestamp).atZone(zone).format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm:ss" else "hh:mm:ss a", Locale.getDefault()))

fun windDisplay(value: Double?, units: WeatherUnits, preference: MetricWind): Pair<Double?, String> =
    if (units == WeatherUnits.IMPERIAL) value to "mph"
    else if (preference == MetricWind.KILOMETRES_PER_HOUR) value?.times(3.6) to "km/h"
    else value to "m/s"
