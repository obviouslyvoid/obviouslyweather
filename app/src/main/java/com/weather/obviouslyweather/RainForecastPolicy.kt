// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class QuietHoursPreferences(
    val enabled: Boolean = true,
    val startMinutes: Int = 22 * 60,
    val endMinutes: Int = 8 * 60
) {
    fun normalized(): QuietHoursPreferences = copy(
        startMinutes = startMinutes.coerceIn(0, 1439), endMinutes = endMinutes.coerceIn(0, 1439)
    )

    fun contains(time: LocalTime): Boolean {
        if (!enabled) return false
        val value = normalized()
        val minute = time.hour * 60 + time.minute
        return when {
            value.startMinutes == value.endMinutes -> true
            value.startMinutes < value.endMinutes -> minute >= value.startMinutes && minute < value.endMinutes
            else -> minute >= value.startMinutes || minute < value.endMinutes
        }
    }

    fun isQuiet(now: Long, zone: ZoneId): Boolean = contains(Instant.ofEpochSecond(now).atZone(zone).toLocalTime())
}

data class RainEventMemory(
    val locationIdentity: String,
    val start: Long,
    val end: Long? = null,
    val likelihood: RainLikelihood = RainLikelihood.POSSIBLE,
    val timestamps: Set<Long> = setOf(start),
    val lastNotifiedAt: Long,
    val endingNotified: Boolean = false,
    val cancelled: Boolean = false,
    val lastSeenAt: Long = lastNotifiedAt
)

data class RainAlertState(val events: List<RainEventMemory> = emptyList())

enum class RainNoticeKind { START, ENDING, CHANGED, CANCELLED }

data class RainNotice(
    val kind: RainNoticeKind,
    val start: Long,
    val end: Long? = null,
    val likelihood: RainLikelihood = RainLikelihood.POSSIBLE,
    val previousStart: Long? = null,
    val previousEnd: Long? = null
) {
    fun message(report: WeatherReport, use24Hour: Boolean): String {
        fun clock(timestamp: Long): String {
            val time = report.localTime(timestamp)
            val day = if (time.toLocalDate() != report.localTime(report.fetchedAt).toLocalDate())
                time.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) + " " else ""
            return day + time.format(clockFormatter(use24Hour))
        }
        val rain = when (likelihood) {
            RainLikelihood.POSSIBLE -> "Rain possible"
            RainLikelihood.EXPECTED -> "Rain expected"
            RainLikelihood.HEAVY -> "Heavy rain possible"
        }
        val changedEnd = end?.takeIf { previousEnd == null || abs(it - previousEnd) >= RainForecastPolicy.CHANGE_INTERVAL_SECONDS }
            ?.let { " It may end around ${clock(it)}." }.orEmpty()
        val upcomingTime = if (start > report.fetchedAt) " around ${clock(start)}" else ""
        return when (kind) {
            RainNoticeKind.START -> "$rain around ${clock(start)}." +
                (end?.let { " It may end around ${clock(it)}." } ?: "")
            RainNoticeKind.ENDING -> "Rain may end around ${clock(requireNotNull(end))}."
            RainNoticeKind.CANCELLED -> "Rain is no longer expected around ${clock(start)}."
            RainNoticeKind.CHANGED -> when {
                previousStart != null && abs(start - previousStart) >= RainForecastPolicy.CHANGE_INTERVAL_SECONDS ->
                    "$rain around ${clock(start)} (previously ${clock(previousStart)}).$changedEnd"
                end != null && previousEnd != null && abs(end - previousEnd) >= RainForecastPolicy.CHANGE_INTERVAL_SECONDS ->
                    "Rain may now end around ${clock(end)} (previously ${clock(previousEnd)})."
                likelihood == RainLikelihood.HEAVY -> "Heavy rain is now possible$upcomingTime.$changedEnd"
                else -> "Heavy rain is no longer expected$upcomingTime. Rain is still possible.$changedEnd"
            }
        }
    }

    fun expiresAt(now: Long): Long = when (kind) {
        RainNoticeKind.CANCELLED -> now + RainForecastPolicy.CHANGE_INTERVAL_SECONDS
        else -> end?.takeIf { it > now } ?: start.takeIf { it > now }
            ?: (now + RainForecastPolicy.CHANGE_INTERVAL_SECONDS)
    }
}

data class RainAlertDecision(val notification: RainNotice?, val state: RainAlertState)

object RainForecastPolicy {
    const val CHANGE_INTERVAL_SECONDS = 60 * 60L
    private const val HISTORY_SECONDS = 48 * 60 * 60L
    private const val MAX_EVENTS = 32
    private const val MAX_SHIFT_SECONDS = 6 * 60 * 60L

    fun evaluate(
        report: WeatherReport,
        now: Long,
        previous: RainAlertState = RainAlertState(),
        quietHours: QuietHoursPreferences = QuietHoursPreferences(),
        phoneZone: ZoneId = ZoneId.systemDefault(),
        legacySent: Set<String> = emptySet()
    ): RainAlertDecision {
        val slots = report.slots.sortedBy { it.timestamp }.distinctBy { it.timestamp }
        val step = report.forecastStepSeconds.coerceAtLeast(1).toLong()
        val episodes = episodes(slots, step)
        val memories = prune(previous, now).events.toMutableList()
        val matched = mutableSetOf<Int>()
        val pairs = mutableListOf<Pair<Episode, Int>>()
        val newEpisodes = mutableListOf<Episode>()
        val location = report.location.identity
        for (episode in episodes) {
            val index = memories.indices.filter { it !in matched && memories[it].locationIdentity == location }
                .filter { canMatch(memories[it], episode, slots, now, step) }
                .minByOrNull { index ->
                    val memory = memories[index]
                    if (memory.timestamps.any { it in episode.timestamps }) 0L else abs(memory.start - episode.start) + 1
                }
            if (index != null) {
                matched += index
                val memory = memories[index]
                memories[index] = memory.copy(timestamps = memory.timestamps + episode.timestamps, lastSeenAt = now)
                pairs += episode to index
            } else if (episode.timestamps.any { "$location|$it" in legacySent }) {
                val index = memories.size
                memories += RainEventMemory(location, episode.start, likelihood = episode.likelihoodAt(now, step),
                    timestamps = episode.timestamps, lastNotifiedAt = now, lastSeenAt = now)
                matched += index
                pairs += episode to index
            } else {
                newEpisodes += episode
            }
        }
        fun decision(notice: RainNotice? = null) = RainAlertDecision(notice, prune(RainAlertState(memories), now))
        if (quietHours.isQuiet(now, phoneZone)) return decision()

        newEpisodes.firstNotNullOfOrNull { episode ->
            episode.slots.firstOrNull { it.timestamp > now && it.timestamp <= now + RainAlertPolicy.HORIZON_SECONDS }
                ?.let { slot -> episode to slot }
        }?.let { (episode, slot) ->
            val notice = RainNotice(RainNoticeKind.START, slot.timestamp, episode.end, requireNotNull(rainLikelihood(slot)))
            memories += RainEventMemory(location, notice.start, notice.end, notice.likelihood, episode.timestamps,
                now, endingNotified = notice.end != null, lastSeenAt = now)
            return decision(notice)
        }

        if (rainyCode(report.current.condition) && report.current.timestamp in (now - step)..(now + 5 * 60)) {
            newEpisodes.firstOrNull { episode ->
                episode.slots.any { it.timestamp <= now && it.timestamp + step > now } &&
                    episode.end?.let { it > now && it <= now + RainAlertPolicy.HORIZON_SECONDS } == true
            }?.let { episode ->
                val likelihood = episode.likelihoodAt(now, step)
                val notice = RainNotice(RainNoticeKind.ENDING, episode.start, episode.end, likelihood)
                memories += RainEventMemory(location, episode.start, episode.end, likelihood, episode.timestamps,
                    now, endingNotified = true, lastSeenAt = now)
                return decision(notice)
            }
            // Some timeline responses omit the hour containing the current observation.
            // Fresh observed rain plus two adjacent dry hours can still support an estimate.
            if (slots.none { it.timestamp <= now && it.timestamp + step > now } &&
                memories.none { it.locationIdentity == location && (it.cancelled || it.start > now) }) {
                val first = slots.firstOrNull { it.timestamp > now }
                val second = slots.firstOrNull { it.timestamp == first?.timestamp?.plus(step) }
                if (first != null && second != null && first.timestamp <= now + step &&
                    confirmedDry(first) && confirmedDry(second)) {
                    val index = memories.indexOfLast { it.locationIdentity == location && !it.cancelled &&
                        it.start <= now && (it.end == null || it.end > now || now - it.lastSeenAt <= step) }
                    val memory = memories.getOrNull(index)
                    if (memory == null) {
                        val notice = RainNotice(RainNoticeKind.ENDING, minOf(report.current.timestamp, now), first.timestamp)
                        memories += RainEventMemory(location, notice.start, notice.end, lastNotifiedAt = now,
                            endingNotified = true, lastSeenAt = now)
                        return decision(notice)
                    }
                    memories[index] = memory.copy(lastSeenAt = now)
                    if (now - memory.lastNotifiedAt >= CHANGE_INTERVAL_SECONDS) {
                        val changed = memory.end != null && abs(first.timestamp - memory.end) >= CHANGE_INTERVAL_SECONDS
                        if (changed || !memory.endingNotified) {
                            val notice = RainNotice(if (changed) RainNoticeKind.CHANGED else RainNoticeKind.ENDING,
                                memory.start, first.timestamp, memory.likelihood, previousEnd = memory.end)
                            memories[index] = memories[index].copy(end = first.timestamp,
                                lastNotifiedAt = now, endingNotified = true)
                            return decision(notice)
                        }
                    }
                }
            }
        }

        for ((episode, index) in pairs) {
            val memory = memories[index]
            if (now - memory.lastNotifiedAt < CHANGE_INTERVAL_SECONDS) continue
            val start = if (memory.start <= now && episode.start <= now + step) memory.start else episode.start
            val active = episode.slots.any { it.timestamp + step > now }
            if (!active) continue
            val likelihood = episode.likelihoodAt(now, step)
            val significantStart = abs(start - memory.start) >= CHANGE_INTERVAL_SECONDS &&
                (start > now || memory.start > now)
            val significantEnd = memory.end != null && episode.end != null && episode.end > now &&
                abs(episode.end - memory.end) >= CHANGE_INTERVAL_SECONDS
            val significantSeverity = (likelihood == RainLikelihood.HEAVY) !=
                (memory.likelihood == RainLikelihood.HEAVY)
            val ending = episode.end?.takeIf { it > now && it <= now + RainAlertPolicy.HORIZON_SECONDS }
            val notice = when {
                memory.cancelled -> RainNotice(RainNoticeKind.START, start, episode.end, likelihood)
                significantStart || significantEnd || significantSeverity ->
                    RainNotice(RainNoticeKind.CHANGED, start, episode.end, likelihood, memory.start, memory.end)
                !memory.endingNotified && ending != null && start <= now + step ->
                    RainNotice(RainNoticeKind.ENDING, start, ending, likelihood)
                else -> null
            } ?: continue
            memories[index] = memory.copy(start = start, end = episode.end ?: memory.end, likelihood = likelihood,
                lastNotifiedAt = now, endingNotified = episode.end != null || memory.endingNotified, cancelled = false)
            return decision(notice)
        }

        for (index in memories.indices) {
            val memory = memories[index]
            if (index in matched || memory.locationIdentity != location || memory.cancelled || memory.start <= now ||
                now - memory.lastNotifiedAt < CHANGE_INTERVAL_SECONDS) continue
            if (!cancelledByForecast(memory, slots, step, now)) continue
            memories[index] = memory.copy(cancelled = true, lastNotifiedAt = now, lastSeenAt = now)
            return decision(RainNotice(RainNoticeKind.CANCELLED, memory.start, likelihood = memory.likelihood))
        }
        return decision()
    }

    fun prune(state: RainAlertState, now: Long): RainAlertState = RainAlertState(state.events.asSequence()
        .filter { it.locationIdentity.isNotBlank() && it.lastSeenAt >= now - HISTORY_SECONDS && it.lastSeenAt <= now + CHANGE_INTERVAL_SECONDS }
        .map { it.copy(timestamps = it.timestamps.filterTo(mutableSetOf()) { timestamp ->
            timestamp >= now - HISTORY_SECONDS && timestamp <= now + HISTORY_SECONDS
        }) }
        .sortedByDescending { it.lastSeenAt }.take(MAX_EVENTS).toList())

    private data class Episode(val slots: List<ForecastSlot>, val end: Long?) {
        val start: Long get() = slots.first().timestamp
        val timestamps: Set<Long> get() = slots.mapTo(mutableSetOf()) { it.timestamp }
        fun likelihoodAt(now: Long, step: Long): RainLikelihood = requireNotNull(rainLikelihood(
            slots.firstOrNull { it.timestamp + step > now } ?: slots.last()
        ))
    }

    private fun canMatch(memory: RainEventMemory, episode: Episode, slots: List<ForecastSlot>, now: Long, step: Long): Boolean {
        if (memory.start <= now && episode.start > now && slots.any {
                it.timestamp >= now - step && it.timestamp < episode.start && confirmedDry(it)
            }) return false
        if (memory.timestamps.any { it in episode.timestamps }) return true
        if (memory.end != null && memory.end <= now && episode.start >= memory.end) return false
        if (memory.start <= now && episode.start > now + CHANGE_INTERVAL_SECONDS) return false
        return abs(memory.start - episode.start) <= MAX_SHIFT_SECONDS &&
            (memory.start > now || episode.slots.any { it.timestamp + CHANGE_INTERVAL_SECONDS > now })
    }

    private fun episodes(slots: List<ForecastSlot>, step: Long): List<Episode> = buildList {
        var index = 0
        while (index < slots.size) {
            if (rainLikelihood(slots[index]) == null) { index++; continue }
            val rain = mutableListOf(slots[index++])
            while (index < slots.size && rainLikelihood(slots[index]) != null &&
                slots[index].timestamp - rain.last().timestamp <= step) rain += slots[index++]
            val dry = slots.getOrNull(index)
            val nextDry = slots.getOrNull(index + 1)
            val end = dry?.timestamp?.takeIf {
                nextDry != null && dry.timestamp - rain.last().timestamp == step &&
                    nextDry.timestamp - dry.timestamp == step && confirmedDry(dry) && confirmedDry(nextDry)
            }
            add(Episode(rain, end))
        }
    }

    private fun cancelledByForecast(memory: RainEventMemory, slots: List<ForecastSlot>, step: Long, now: Long): Boolean {
        val end = minOf(memory.end ?: ((memory.timestamps.maxOrNull() ?: memory.start) + step),
            memory.start + RainAlertPolicy.HORIZON_SECONDS)
        if (end <= memory.start || memory.start > now + HISTORY_SECONDS) return false
        val coverage = slots.filter { it.timestamp >= memory.start && it.timestamp <= end }
        if (coverage.firstOrNull()?.timestamp != memory.start || coverage.lastOrNull()?.timestamp != end) return false
        if (coverage.zipWithNext().any { (first, second) -> second.timestamp - first.timestamp != step }) return false
        return coverage.all(::confirmedDry)
    }

    private val knownConditions = setOf(200, 201, 202, 210, 211, 212, 221, 230, 231, 232,
        300, 301, 302, 310, 311, 312, 313, 314, 321, 500, 501, 502, 503, 504, 511, 520, 521, 522, 531,
        600, 601, 602, 611, 612, 613, 615, 616, 620, 621, 622, 701, 711, 721, 731, 741, 751, 761, 762,
        771, 781, 800, 801, 802, 803, 804)

    private fun known(slot: ForecastSlot): Boolean = slot.condition in knownConditions &&
        slot.precipitation?.let { it.isFinite() && it in 0.0..100.0 } == true &&
        slot.rainMm.isFinite() && slot.rainMm >= 0

    private fun rainyCode(condition: Int?): Boolean = condition in 300..599 ||
        condition in setOf(200, 201, 202, 230, 231, 232, 615, 616)

    private fun confirmedDry(slot: ForecastSlot): Boolean = known(slot) && !rainyCode(slot.condition) &&
        slot.rainMm == 0.0 && requireNotNull(slot.precipitation) <= 30.0

    private fun rainLikelihood(slot: ForecastSlot): RainLikelihood? {
        val chance = slot.precipitation?.takeIf { it.isFinite() && it > 30.0 && it <= 100.0 } ?: return null
        if (!rainyCode(slot.condition) && (!slot.rainMm.isFinite() || slot.rainMm <= 0.0)) return null
        return when {
            slot.condition in setOf(202, 502, 503, 504, 522) -> RainLikelihood.HEAVY
            chance >= 70 -> RainLikelihood.EXPECTED
            else -> RainLikelihood.POSSIBLE
        }
    }
}
