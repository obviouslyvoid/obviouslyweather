// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicLong

/** Foreground and background refreshes share one cache writer and reject obsolete settings. */
internal data class WeatherUpdate(val report: WeatherReport, val keyFingerprint: String, val sequence: Long, val background: Boolean,
    val dailyProvider: DailyForecastProvider)

object WeatherSync {
    private val gate = WeatherRefreshGate()
    private val updateSequence = AtomicLong()
    internal val latestUpdateSequence get() = updateSequence.get()
    internal val lock get() = gate.lock
    internal val updates = MutableSharedFlow<WeatherUpdate>(extraBufferCapacity = 1)

    internal suspend fun refresh(context: Context, intent: RefreshIntent, background: Boolean = false,
        onCoreReady: (suspend (WeatherReport) -> Unit)? = null,
        repository: WeatherRepository = WeatherRepository(),
        deliverRain: suspend (Context, WeatherReport, Long?) -> Unit = { app, report, sequence ->
            RainNotifications.deliver(app, report, expectedSequence = sequence)
        }): RefreshResult = withContext(Dispatchers.IO) {
        val store = WeatherStore(context)
        fun stillCurrent(request: RefreshRequest): Boolean {
            val current = store.settings()
            return current.location?.identity == request.location.identity && current.units == request.units &&
                current.key == request.key && current.dailyProvider == request.dailyProvider && (!background || current.background.enabled)
        }
        var activeRequest: RefreshRequest? = null
        var acceptedSequence: Long? = null
        val result = gate.refreshStaged(intent,
            load = {
                acceptedSequence = latestUpdateSequence
                val settings = store.settings()
                val location = settings.location
                val key = settings.key
                if ((background && !settings.background.enabled) || location == null || key == null) null
                else {
                    val cached = store.cachedSnapshot(location, settings.units, key)
                    RefreshRequest(location, settings.units, key, cached?.report, settings.background.normalizedIntervalMinutes,
                        cachedSupportsCurrentFormat = cached?.needsUpgrade != true, cachedResponse = cached?.response,
                        dailyProvider = settings.dailyProvider)
                }
            },
            fetch = { request, acceptCore ->
                activeRequest = request
                val response = repository.weather(request.location, request.units, request.key,
                    previous = request.cachedResponse, dailyProvider = request.dailyProvider, onCoreResponse = { core ->
                        coroutineContext.ensureActive()
                        if (stillCurrent(request) && acceptCore(core) && stillCurrent(request)) onCoreReady?.invoke(core.report)
                    })
                if (stillCurrent(request)) response else null
            },
            accepted = { response ->
                // Revalidate cancellation cleanup too: a key or location may have changed.
                activeRequest?.takeIf { stillCurrent(it) }?.let { request ->
                    // A private-storage failure must not hide successfully retrieved weather.
                    runCatching { store.saveCache(response, request.key) }
                    com.weather.obviouslyweather.widget.WeatherWidgets.requestUpdate(context)
                    // Stages must be delivered in order, including the final loading-marker clear.
                    val sequence = updateSequence.incrementAndGet()
                    acceptedSequence = sequence
                    updates.emit(WeatherUpdate(response.report, WeatherCacheCodec.fingerprint(request.key), sequence, background,
                        request.dailyProvider))
                }
            })
        if (result is RefreshResult.Recent && background) {
            // Clock/forecast rows may advance without spending another API call.
            com.weather.obviouslyweather.widget.WeatherWidgets.requestUpdate(context)
        }
        val notificationReport = when (result) {
            is RefreshResult.Updated -> result.response.report
            is RefreshResult.Recent -> result.report.takeIf { background }
            RefreshResult.Skipped -> null
        }
        // Delivery takes the same lock; call it only after the refresh gate releases it.
        if (notificationReport != null) {
            try {
                deliverRain(context, notificationReport, acceptedSequence)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A blocked notification or storage failure must not fail a weather refresh.
            }
        }
        when (result) {
            is RefreshResult.Updated -> result.copy(sequence = acceptedSequence)
            is RefreshResult.Recent -> result.copy(sequence = acceptedSequence)
            RefreshResult.Skipped -> result
        }
    }
}
