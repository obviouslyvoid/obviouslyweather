// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal enum class RefreshIntent { AUTOMATIC, MANUAL, FORCE }

internal class RefreshRequest(
    val location: WeatherLocation,
    val units: WeatherUnits,
    val key: String,
    val cached: WeatherReport?,
    val intervalMinutes: Int = RefreshPolicy.DEFAULT_INTERVAL_MINUTES,
    val cachedSupportsCurrentFormat: Boolean = true,
    val cachedResponse: WeatherResponse? = null,
    val dailyProvider: DailyForecastProvider = DailyForecastProvider.OCA4
)

internal sealed interface RefreshResult {
    data class Updated(val response: WeatherResponse, val sequence: Long? = null) : RefreshResult
    data class Recent(val report: WeatherReport, val sequence: Long? = null) : RefreshResult
    data object Skipped : RefreshResult
}

/** Serialize core refreshes; optional daily enrichment can continue after a core has been accepted. */
internal class WeatherRefreshGate(private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    val lock = Mutex()
    private var lastRequest: RefreshRequest? = null
    private var lastResponse: WeatherResponse? = null
    private var generation = 0L

    suspend fun refresh(
        intent: RefreshIntent,
        load: suspend () -> RefreshRequest?,
        fetch: suspend (RefreshRequest) -> WeatherResponse?,
        accepted: suspend (WeatherResponse) -> Unit
    ): RefreshResult = refreshStaged(intent, load, { request, _ -> fetch(request) }, accepted)

    suspend fun refreshStaged(
        intent: RefreshIntent,
        load: suspend () -> RefreshRequest?,
        fetch: suspend (RefreshRequest, suspend (WeatherResponse) -> Boolean) -> WeatherResponse?,
        accepted: suspend (WeatherResponse) -> Unit
    ): RefreshResult {
        val owner = Any()
        var ownsLock = false
        var stageGeneration: Long? = null
        var request: RefreshRequest? = null
        var completed = false
        lock.lock(owner)
        ownsLock = true
        try {
            val loaded = load() ?: return RefreshResult.Skipped
            coroutineContext.ensureActive()
            val memory = lastResponse?.takeIf { lastRequest?.matches(loaded) == true }
            val checkedAt = now()
            val recent = listOfNotNull(memory?.report, loaded.cached.takeIf { loaded.cachedSupportsCurrentFormat }).filter {
                it.matches(loaded) && RefreshPolicy.isRecent(it, checkedAt, loaded.intervalMinutes)
            }.maxByOrNull { it.fetchedAt }
            if (intent != RefreshIntent.FORCE && recent != null) return RefreshResult.Recent(recent)

            // Preserve the whole response, including independently dated daily JSON,
            // when persistence failed or a newer in-memory core superseded disk data.
            val cachedResponse = listOfNotNull(memory, loaded.cachedResponse).filter { it.report.matches(loaded) }
                .maxWithOrNull(compareBy<WeatherResponse> { it.report.fetchedAt }
                    .thenBy { it.report.dailyFetchedAt ?: Long.MIN_VALUE }
                    .thenBy { !it.report.dailyLoading })
            val active = RefreshRequest(loaded.location, loaded.units, loaded.key,
                listOfNotNull(loaded.cached, cachedResponse?.report).filter { it.matches(loaded) }.maxByOrNull { it.fetchedAt },
                loaded.intervalMinutes, loaded.cachedSupportsCurrentFormat, cachedResponse, loaded.dailyProvider)
            request = active
            val response = fetch(active) { core ->
                coroutineContext.ensureActive()
                if (stageGeneration != null || !ownsLock || !core.report.matches(active)) false
                else {
                    generation++
                    stageGeneration = generation
                    lastRequest = active
                    lastResponse = core
                    accepted(core)
                    // Guarded refreshes can now reuse this accepted core immediately.
                    // A force refresh may replace it while daily loading continues.
                    lock.unlock(owner)
                    ownsLock = false
                    true
                }
            } ?: return RefreshResult.Skipped
            coroutineContext.ensureActive()
            if (!ownsLock) {
                lock.lock(owner)
                ownsLock = true
            }
            if ((stageGeneration != null && stageGeneration != generation) || !response.report.matches(active) ||
                load()?.matches(active) != true) return RefreshResult.Skipped
            if (stageGeneration == null) generation++
            lastRequest = active
            lastResponse = response
            accepted(response)
            completed = true
            return RefreshResult.Updated(response)
        } finally {
            // Canceling an optional request must never erase an accepted core or
            // leave its daily-loading marker permanently visible. A later core owns
            // its own marker and must not be touched by this session's cleanup.
            try {
                if (!completed && stageGeneration != null) withContext(NonCancellable) {
                    if (!ownsLock) {
                        lock.lock(owner)
                        ownsLock = true
                    }
                    val current = lastResponse
                    if (generation == stageGeneration && current?.report?.dailyLoading == true) {
                        val cleared = current.copy(report = current.report.copy(dailyLoading = false))
                        lastResponse = cleared
                        // Changed or removed credentials/settings reject disk/UI writes.
                        runCatching {
                            if (request?.let { load()?.matches(it) } == true) accepted(cleared)
                        }
                    }
                }
            } finally {
                if (ownsLock) lock.unlock(owner)
            }
        }
    }

    private fun RefreshRequest.matches(other: RefreshRequest): Boolean = location.identity == other.location.identity &&
        units == other.units && key == other.key && dailyProvider == other.dailyProvider

    private fun WeatherReport.matches(request: RefreshRequest): Boolean = location.identity == request.location.identity && units == request.units
}
