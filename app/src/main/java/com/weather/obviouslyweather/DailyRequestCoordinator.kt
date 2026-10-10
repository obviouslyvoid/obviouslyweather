// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Share only pending daily calls; each caller owns a cancellable subscription. */
internal class DailyRequestCoordinator(
    private val transport: suspend (String, Map<String, String>, String) -> String
) {
    private data class Context(val path: String, val parameters: Map<String, String>, val keyFingerprint: String)
    private class Pending(val result: Deferred<String>, var subscribers: Int = 1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val pending = mutableMapOf<Context, Pending>()

    suspend fun request(path: String, parameters: Map<String, String>, key: String): String {
        if (!DailyForecastProvider.isDailyPath(path)) return transport(path, parameters, key)
        val context = Context(path, parameters.toMap(), WeatherCacheCodec.fingerprint(key))
        val entry = lock.withLock {
            val current = pending[context]
            if (current != null && !current.result.isCompleted) {
                current.subscribers++
                current
            } else {
                val result = scope.async(start = CoroutineStart.LAZY) { transport(path, context.parameters, key) }
                Pending(result).also { pending[context] = it; result.start() }
            }
        }
        try {
            return entry.result.await()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    entry.subscribers--
                    if (entry.subscribers == 0) {
                        if (pending[context] === entry) pending.remove(context)
                        // Cancel the native request only after its final subscriber leaves.
                        if (!entry.result.isCompleted) entry.result.cancel()
                    }
                }
            }
        }
    }

    internal fun close() { scope.cancel() }

}

private val coordinatedTransport by lazy { DailyRequestCoordinator(OpenWeatherTransport()::request) }

internal suspend fun requestCoordinatedOpenWeather(path: String, parameters: Map<String, String>, key: String): String =
    coordinatedTransport.request(path, parameters, key)
