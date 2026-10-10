// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

private val weatherZoneIds: Set<String> = ZoneId.getAvailableZoneIds()

enum class WeatherUnits(val apiValue: String, val temperature: String, val speed: String) {
    METRIC("metric", "°C", "m/s"), IMPERIAL("imperial", "°F", "mph")
}
enum class Appearance { SYSTEM, LIGHT, DARK }

data class WeatherLocation(val name: String, val country: String, val state: String?, val lat: Double, val lon: Double) {
    val detail: String get() = listOfNotNull(state?.takeIf { it.isNotBlank() }, country.takeIf { it.isNotBlank() }).joinToString(", ")
    val identity: String get() = "$lat,$lon"
}
data class CurrentWeather(
    val timestamp: Long, val temperature: Double, val feelsLike: Double?, val condition: Int?,
    val description: String, val night: Boolean, val humidity: Double?, val pressure: Double?,
    val visibility: Double?, val windSpeed: Double?, val windDegrees: Double?, val gust: Double?,
    val clouds: Double?, val sunrise: Long?, val sunset: Long?
)
data class ForecastSlot(
    val timestamp: Long, val temperature: Double?, val low: Double?, val high: Double?,
    val condition: Int?, val description: String, val night: Boolean, val precipitation: Double?,
    val rainMm: Double, val snowMm: Double
)
data class DailyWeather(
    val date: LocalDate, val low: Double?, val high: Double?, val condition: Int?,
    val description: String, val precipitation: Double?, val rainMm: Double, val snowMm: Double
)
data class WeatherReport(
    val location: WeatherLocation, val units: WeatherUnits, val current: CurrentWeather,
    val slots: List<ForecastSlot>, val offset: Int, val fetchedAt: Long, val forecastNote: String? = null,
    val providerDaily: List<DailyWeather>? = null, val zoneId: String? = null,
    val forecastStepSeconds: Int = 10800, val dailySlots: List<ForecastSlot>? = null,
    val dailyFetchedAt: Long? = null, val dailyFromCache: Boolean = false,
    val dailyLoading: Boolean = false
) {
    fun localTime(timestamp: Long): OffsetDateTime = localTimeAt(timestamp, offset, zoneId)
    val next24Hours: List<ForecastSlot> get() = slots.asSequence()
        .filter { it.timestamp >= fetchedAt && it.timestamp - fetchedAt < 86400 }
        .sortedBy { it.timestamp }.distinctBy { it.timestamp }
        .take((86400 / forecastStepSeconds.coerceAtLeast(1)).coerceIn(1, 1440)).toList()
    val daily: List<DailyWeather> get() = providerDaily ?: dailyForecast(dailySlots ?: slots, offset, zoneId)
}

fun localTimeAt(timestamp: Long, offset: Int, zoneId: String? = null): OffsetDateTime {
    val instant = Instant.ofEpochSecond(timestamp)
    val zone = zoneId?.takeIf { it in weatherZoneIds }?.let { ZoneId.of(it) }
    return zone?.let { instant.atZone(it).toOffsetDateTime() }
        ?: instant.atOffset(ZoneOffset.ofTotalSeconds(offset.coerceIn(-50400, 50400)))
}

/** Ranges over available three-hour slots, not a separate daily forecast product. */
fun dailyForecast(slots: List<ForecastSlot>, offset: Int, zoneId: String? = null): List<DailyWeather> =
    slots.sortedBy { it.timestamp }.groupBy { localTimeAt(it.timestamp, offset, zoneId).toLocalDate() }
        .toSortedMap().entries.take(5).map { (date, entries) ->
            val representative = entries.minBy { abs(localTimeAt(it.timestamp, offset, zoneId).toLocalTime().toSecondOfDay() - 43200) }
            DailyWeather(
                date, entries.mapNotNull { it.low ?: it.temperature }.minOrNull(),
                entries.mapNotNull { it.high ?: it.temperature }.maxOrNull(),
                representative.condition, representative.description,
                entries.mapNotNull { it.precipitation }.maxOrNull(), entries.sumOf { it.rainMm }, entries.sumOf { it.snowMm }
            )
        }

fun windDirection(degrees: Double?): String? = degrees?.takeIf { it.isFinite() }?.let {
    val normalized = (it % 360 + 360) % 360
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((normalized + 22.5) / 45).toInt() % 8]
}
