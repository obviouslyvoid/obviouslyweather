// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Uses the actual staged repository, guard, coordinator and cache codec together. */
class WeatherStagedIntegrationTest {
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private val key = "LOCAL_STAGED_INTEGRATION_SENTINEL"
    private val start = Instant.parse("2026-10-07T08:10:00Z").epochSecond
    private val firstHour = Instant.parse("2026-10-07T09:00:00Z").epochSecond
    private val dayStart = Instant.parse("2026-10-07T00:00:00Z").epochSecond
    private val currentPath = "/data/2.5/weather"
    private val hourlyPath = "/data/4.0/onecall/timeline/1h"
    private val dailyPath = "/data/4.0/onecall/timeline/1day"

    private fun current(temperature: Double): String = JSONObject().put("dt", start - 600)
        .put("timezone", 10800).put("main", JSONObject().put("temp", temperature).put("feels_like", temperature))
        .put("weather", JSONArray().put(JSONObject().put("id", 801).put("icon", "02d"))).toString()

    private fun envelope(records: JSONArray): JSONObject = JSONObject().put("lat", city.lat)
        .put("lon", city.lon).put("timezone", "Europe/Kyiv").put("timezone_offset", 10800).put("data", records)

    private fun hours(): String = envelope(JSONArray().apply {
        repeat(4) { index -> put(JSONObject().put("dt", firstHour + index * 3600L).put("temp", 20.0)
            .put("pop", 0.0).put("weather", JSONArray().put(JSONObject().put("id", 801).put("icon", "02d")))) }
    }).toString()

    private fun days(high: Double = 22.0): String = envelope(JSONArray().apply {
        repeat(10) { index -> put(JSONObject().put("dt", dayStart + index * 86400L)
            .put("temp", JSONObject().put("min", 11.0).put("max", high)).put("pop", 0.0)
            .put("weather", JSONArray().put(JSONObject().put("id", 801).put("icon", "02d")))) }
    }).toString()

    private fun savedResponse(sourceAt: Long): WeatherResponse {
        val json = days()
        val report = HybridWeatherParser.report(city, WeatherUnits.METRIC, current(18.0), hours(), null,
            start - 3600, dailyJson = json, dailyFetchedAt = sourceAt)
        return WeatherResponse(report, current(18.0), hours(), null, json, sourceAt)
    }

    @Test fun acceptedCorePersistsSavedDaysAndRecentGuardReturnsBeforeDailyFailure() = runBlocking {
        val clock = AtomicLong(start)
        val sourceAt = start - 86400
        val cache = AtomicReference(WeatherCacheCodec.encode(savedResponse(sourceAt), key))
        val dailyEntered = CompletableDeferred<Unit>()
        val releaseDaily = CompletableDeferred<Unit>()
        val coreAccepted = CompletableDeferred<WeatherResponse>()
        val calls = CopyOnWriteArrayList<String>()
        val accepts = CopyOnWriteArrayList<WeatherResponse>()
        val coordinator = DailyRequestCoordinator { path, _, _ ->
            calls += path
            when (path) {
                dailyPath -> {
                    dailyEntered.complete(Unit)
                    releaseDaily.await()
                    throw WeatherFailure("OpenWeather took too long to respond. Please try again.")
                }
                currentPath -> { dailyEntered.await(); current(19.0) }
                hourlyPath -> { dailyEntered.await(); hours() }
                else -> error("Unexpected endpoint $path")
            }
        }
        val repository = WeatherRepository(coordinator::request, { clock.get() })
        val gate = WeatherRefreshGate { clock.get() }
        suspend fun refresh(intent: RefreshIntent): RefreshResult = gate.refreshStaged(intent,
            load = {
                val stored = WeatherCacheCodec.decode(cache.get(), 4, city, WeatherUnits.METRIC, key, clock.get())!!
                RefreshRequest(city, WeatherUnits.METRIC, key, stored.report, cachedResponse = stored.response)
            },
            fetch = { request, publish -> repository.weather(request.location, request.units, request.key,
                onCoreResponse = { check(publish(it)) }, previous = request.cachedResponse) },
            accepted = {
                accepts += it
                cache.set(WeatherCacheCodec.encode(it, key))
                if (it.report.dailyLoading) coreAccepted.complete(it)
            })

        val pending = async(start = CoroutineStart.UNDISPATCHED) { refresh(RefreshIntent.FORCE) }
        try {
            val core = withTimeout(2_000) { coreAccepted.await() }
            assertFalse("Daily work remains pending after core acceptance", pending.isCompleted)
            assertEquals(19.0, core.report.current.temperature, 0.0)
            assertEquals(4, core.report.slots.size)
            assertEquals(7, core.report.daily.size)
            assertTrue(core.report.dailyFromCache)
            assertEquals(sourceAt, core.dailyFetchedAt)
            val coreDisk = WeatherCacheCodec.decode(cache.get(), 4, city, WeatherUnits.METRIC, key, clock.get())!!
            assertEquals(19.0, coreDisk.report.current.temperature, 0.0)
            assertEquals(4, coreDisk.report.slots.size)
            assertEquals(7, coreDisk.report.daily.size)
            assertEquals(sourceAt, coreDisk.response!!.dailyFetchedAt)
            assertFalse("A reloaded cache must not persist an in-flight marker", coreDisk.report.dailyLoading)

            val recent = async(start = CoroutineStart.UNDISPATCHED) { refresh(RefreshIntent.MANUAL) }
            val recentResult = withTimeout(500) { recent.await() }
            assertTrue(recentResult is RefreshResult.Recent)
            assertEquals(core.report.fetchedAt, (recentResult as RefreshResult.Recent).report.fetchedAt)
            assertEquals(3, calls.size)
            assertEquals(1, calls.count { it == dailyPath })
            assertFalse(pending.isCompleted)

            clock.set(start + 20)
            releaseDaily.complete(Unit)
            val result = withTimeout(2_000) { pending.await() }
            assertTrue(result is RefreshResult.Updated)
            val final = (result as RefreshResult.Updated).response
            assertEquals(core.report.fetchedAt, final.report.fetchedAt)
            assertEquals(sourceAt, final.dailyFetchedAt)
            assertEquals(7, final.report.daily.size)
            assertTrue(final.report.dailyFromCache)
            assertFalse(final.report.dailyLoading)
            assertTrue(final.report.forecastNote!!.contains("saved forecast"))
            val finalDisk = WeatherCacheCodec.decode(cache.get(), 4, city, WeatherUnits.METRIC, key, clock.get())!!
            assertEquals(core.report.fetchedAt, finalDisk.report.fetchedAt)
            assertEquals(sourceAt, finalDisk.response!!.dailyFetchedAt)
            assertEquals(7, finalDisk.report.daily.size)
            assertEquals(2, accepts.size)
            assertEquals(3, calls.size)
        } finally {
            pending.cancelAndJoin()
            coordinator.close()
        }
    }

    @Test fun overlappingForceRefreshJoinsDailyAndOnlyNewerCoreCanAcceptFinalEnrichment() = runBlocking {
        val clock = AtomicLong(start)
        val sourceAt = start - 86400
        val cache = AtomicReference(WeatherCacheCodec.encode(savedResponse(sourceAt), key))
        val dailyEntered = CompletableDeferred<Unit>()
        val releaseDaily = CompletableDeferred<String>()
        val firstJoined = CompletableDeferred<Unit>()
        val secondJoined = CompletableDeferred<Unit>()
        val firstCore = CompletableDeferred<WeatherResponse>()
        val secondCore = CompletableDeferred<WeatherResponse>()
        val currentCalls = AtomicInteger()
        val dailySubscriptions = AtomicInteger()
        val calls = CopyOnWriteArrayList<String>()
        val accepts = CopyOnWriteArrayList<WeatherResponse>()
        val coordinator = DailyRequestCoordinator { path, _, _ ->
            calls += path
            when (path) {
                dailyPath -> { dailyEntered.complete(Unit); releaseDaily.await() }
                currentPath -> {
                    val number = currentCalls.incrementAndGet()
                    dailyEntered.await()
                    firstJoined.await()
                    if (number == 2) secondJoined.await()
                    current(18.0 + number)
                }
                hourlyPath -> { dailyEntered.await(); hours() }
                else -> error("Unexpected endpoint $path")
            }
        }
        val repository = WeatherRepository({ path, parameters, suppliedKey ->
            if (path != dailyPath) coordinator.request(path, parameters, suppliedKey)
            else coroutineScope {
                // Register the subscriber before allowing the second core to finish.
                // The first accepted core has already released the coordinator lock.
                val number = dailySubscriptions.incrementAndGet()
                val subscribed = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.request(path, parameters, suppliedKey)
                }
                if (number == 1) firstJoined.complete(Unit) else if (number == 2) secondJoined.complete(Unit)
                subscribed.await()
            }
        }, { clock.get() })
        val gate = WeatherRefreshGate { clock.get() }
        suspend fun refresh(): RefreshResult = gate.refreshStaged(RefreshIntent.FORCE,
            load = {
                val stored = WeatherCacheCodec.decode(cache.get(), 4, city, WeatherUnits.METRIC, key, clock.get())!!
                RefreshRequest(city, WeatherUnits.METRIC, key, stored.report, cachedResponse = stored.response)
            },
            fetch = { request, publish -> repository.weather(request.location, request.units, request.key,
                onCoreResponse = { check(publish(it)) }, previous = request.cachedResponse) },
            accepted = {
                accepts += it
                cache.set(WeatherCacheCodec.encode(it, key))
                if (it.report.dailyLoading) {
                    if (it.report.current.temperature == 19.0) firstCore.complete(it) else secondCore.complete(it)
                }
            })

        val first = async(start = CoroutineStart.UNDISPATCHED) { refresh() }
        var second: kotlinx.coroutines.Deferred<RefreshResult>? = null
        try {
            val firstAccepted = withTimeout(2_000) { firstCore.await() }
            clock.set(start + 60)
            val newer = async(start = CoroutineStart.UNDISPATCHED) { refresh() }
            second = newer
            val secondAccepted = withTimeout(2_000) { secondCore.await() }
            assertEquals(start, firstAccepted.report.fetchedAt)
            assertEquals(start + 60, secondAccepted.report.fetchedAt)
            assertEquals(20.0, secondAccepted.report.current.temperature, 0.0)
            assertEquals(sourceAt, secondAccepted.dailyFetchedAt)
            assertEquals(2, dailySubscriptions.get())
            assertEquals(1, calls.count { it == dailyPath })
            assertEquals(5, calls.size)
            assertFalse(first.isCompleted)
            assertFalse(newer.isCompleted)

            clock.set(start + 90)
            releaseDaily.complete(days(high = 24.0))
            assertEquals(RefreshResult.Skipped, withTimeout(2_000) { first.await() })
            val finalResult = withTimeout(2_000) { newer.await() }
            assertTrue(finalResult is RefreshResult.Updated)
            val final = (finalResult as RefreshResult.Updated).response
            assertEquals(20.0, final.report.current.temperature, 0.0)
            assertEquals(start + 60, final.report.fetchedAt)
            assertEquals(start + 90, final.dailyFetchedAt)
            assertEquals(24.0, final.report.daily.first().high!!, 0.0)
            assertFalse(final.report.dailyFromCache)
            assertFalse(final.report.dailyLoading)
            assertEquals(3, accepts.size)
            assertEquals(5, calls.size)
            val disk = WeatherCacheCodec.decode(cache.get(), 4, city, WeatherUnits.METRIC, key, clock.get())!!
            assertEquals(20.0, disk.report.current.temperature, 0.0)
            assertEquals(start + 60, disk.report.fetchedAt)
            assertEquals(start + 90, disk.response!!.dailyFetchedAt)
            assertEquals(24.0, disk.report.daily.first().high!!, 0.0)
        } finally {
            first.cancelAndJoin()
            second?.cancelAndJoin()
            coordinator.close()
        }
    }
}
