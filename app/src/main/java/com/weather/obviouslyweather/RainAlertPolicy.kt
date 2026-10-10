// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class BackgroundPreferences(
    val enabled: Boolean = true,
    val rainAlerts: Boolean = false,
    val intervalMinutes: Int = RefreshPolicy.DEFAULT_INTERVAL_MINUTES,
    val quietHours: QuietHoursPreferences = QuietHoursPreferences()
) {
    val normalizedIntervalMinutes: Int get() = RefreshPolicy.normalizedMinutes(intervalMinutes)
}
enum class RainLikelihood { POSSIBLE, EXPECTED, HEAVY }
data class RainAlert(
    val timestamp: Long, val likelihood: RainLikelihood,
    val episodeTimestamps: Set<Long> = setOf(timestamp)
) {
    fun identity(location: WeatherLocation): String = "${location.identity}|$timestamp"
    fun identities(location: WeatherLocation): Set<String> = episodeTimestamps.mapTo(mutableSetOf()) { "${location.identity}|$it" }
    fun message(report: WeatherReport, use24Hour: Boolean): String {
        val time = report.localTime(timestamp)
        val clock = time.format(clockFormatter(use24Hour))
        return when (likelihood) {
            RainLikelihood.POSSIBLE -> "Rain possible around $clock."
            RainLikelihood.EXPECTED -> "Rain expected around $clock."
            RainLikelihood.HEAVY -> "Heavy rain possible around $clock."
        }
    }
}

object RainAlertPolicy {
    const val HORIZON_SECONDS = 3 * 60 * 60L
    private const val HISTORY_FUTURE_SECONDS = 48 * 60 * 60L
    fun quietHour(hour: Int, quietHours: QuietHoursPreferences = QuietHoursPreferences()): Boolean =
        quietHours.contains(LocalTime.of(hour.coerceIn(0, 23), 0))

    /** Uses the actual check time, never the provider's older observation timestamp. */
    fun next(report: WeatherReport, now: Long, sent: Set<String>, phoneZone: ZoneId = ZoneId.systemDefault(),
        quietHours: QuietHoursPreferences = QuietHoursPreferences()): RainAlert? {
        if (quietHours.isQuiet(now, phoneZone)) return null
        return episodes(report).asSequence().mapNotNull { episode ->
            // Include elapsed slots in this comparison: an hourly fetch can still
            // cover rain for which the phone already alerted earlier in the spell.
            if (episode.any { "${report.location.identity}|${it.timestamp}" in sent }) return@mapNotNull null
            val slot = episode.firstOrNull {
                it.timestamp > now && it.timestamp <= now + HORIZON_SECONDS &&
                    !quietHours.contains(report.localTime(it.timestamp).toLocalTime()) &&
                    !quietHours.contains(Instant.ofEpochSecond(it.timestamp).atZone(phoneZone).toLocalTime())
            } ?: return@mapNotNull null
            RainAlert(slot.timestamp, likelihood(slot)!!, episode.mapTo(mutableSetOf()) { it.timestamp })
        }.firstOrNull()
    }

    /** Carry an alerted rain spell forward as each fetch extends its hourly window. */
    fun rememberEpisodes(report: WeatherReport, now: Long, sent: Set<String>): Set<String> {
        val retained = pruneHistory(sent, now)
        val expanded = retained.toMutableSet()
        episodes(report).filter { episode ->
            episode.any { "${report.location.identity}|${it.timestamp}" in retained }
        }.forEach { episode -> episode.forEach { expanded += "${report.location.identity}|${it.timestamp}" } }
        return pruneHistory(expanded, now)
    }

    fun pruneHistory(sent: Set<String>, now: Long): Set<String> = sent.filterTo(mutableSetOf()) {
        val timestamp = it.substringAfterLast('|').toLongOrNull()
        timestamp != null && timestamp >= now - 24 * 60 * 60 && timestamp <= now + HISTORY_FUTURE_SECONDS
    }

    private fun likelihood(slot: ForecastSlot): RainLikelihood? {
        val probability = slot.precipitation?.takeIf { it.isFinite() && it > 30.0 } ?: return null
        val rainyCode = slot.condition in 300..599 || slot.condition in setOf(200, 201, 202, 230, 231, 232, 615, 616)
        if (!rainyCode && slot.rainMm <= 0.0) return null
        return when {
            slot.condition in setOf(202, 502, 503, 504, 522) -> RainLikelihood.HEAVY
            probability >= 70.0 -> RainLikelihood.EXPECTED
            else -> RainLikelihood.POSSIBLE
        }
    }

    private fun episodes(report: WeatherReport): List<List<ForecastSlot>> = buildList {
        val current = mutableListOf<ForecastSlot>()
        fun finish() {
            if (current.isNotEmpty()) add(current.toList())
            current.clear()
        }
        val maximumGap = report.forecastStepSeconds.coerceAtLeast(1).toLong()
        report.slots.sortedBy { it.timestamp }.distinctBy { it.timestamp }.forEach { slot ->
            if (likelihood(slot) == null) {
                finish()
            } else {
                if (current.lastOrNull()?.let { slot.timestamp - it.timestamp > maximumGap } == true) finish()
                current += slot
            }
        }
        finish()
    }
}
