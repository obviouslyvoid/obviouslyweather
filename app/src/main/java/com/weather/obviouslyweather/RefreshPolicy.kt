// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

/** Freshness is measured from the app's completed fetch, not the provider's observation time. */
object RefreshPolicy {
    const val DEFAULT_INTERVAL_MINUTES = 30
    const val MIN_INTERVAL_MINUTES = 15
    const val MAX_INTERVAL_MINUTES = 24 * 60

    fun normalizedMinutes(minutes: Int): Int = minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    fun intervalSeconds(minutes: Int = DEFAULT_INTERVAL_MINUTES): Long = normalizedMinutes(minutes) * 60L

    fun scheduledMinutes(enabled: Boolean, intervalMinutes: Int): Long? =
        if (enabled) normalizedMinutes(intervalMinutes).toLong() else null

    fun isRecent(report: WeatherReport?, now: Long, intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES): Boolean {
        val fetchedAt = report?.fetchedAt ?: return false
        // Reject invalid or future timestamps so a clock change cannot indefinitely block updates.
        if (now < 0 || fetchedAt < 0 || fetchedAt > now) return false
        return now - fetchedAt < intervalSeconds(intervalMinutes)
    }

    /** Opening an app with auto-update off never causes an immediate timer fetch. */
    fun foregroundDelayMillis(fetchedAt: Long?, now: Long, intervalMinutes: Int): Long {
        val interval = intervalSeconds(intervalMinutes)
        val remaining = if (fetchedAt != null && now >= 0 && fetchedAt >= 0 && fetchedAt <= now) {
            val age = now - fetchedAt
            if (age < interval) interval - age else interval
        } else interval
        return remaining * 1000L
    }
}
