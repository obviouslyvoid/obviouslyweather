// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Exercises the hybrid request flow without spending API calls or using a real key. */
class WeatherRepositoryTest {
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private val key = "LOCAL_TEST_SENTINEL"
    private val start = epoch("2026-10-05T09:17:00Z")
    private val firstHour = epoch("2026-10-05T10:00:00Z")
    private val currentPath = "/data/2.5/weather"
    private val hourlyPath = "/data/4.0/onecall/timeline/1h"
    private val dailyPath = "/data/4.0/onecall/timeline/1day"

    private data class Call(val path: String, val parameters: Map<String, String>, val key: String)

    private inner class Harness(
        private val startedAt: Long = start,
        private val completedAt: Long = startedAt + 2,
        handler: suspend (Call) -> String
    ) {
        val calls = CopyOnWriteArrayList<Call>()
        private val clockReads = AtomicInteger()
        val repository = WeatherRepository({ path, parameters, suppliedKey ->
            Call(path, parameters.toMap(), suppliedKey).let { call -> calls += call; handler(call) }
        }, { if (clockReads.getAndIncrement() == 0) startedAt else completedAt })

        fun fetch(units: WeatherUnits = WeatherUnits.METRIC, previous: WeatherResponse? = null): WeatherResponse = runBlocking {
            repository.weather(city, units, key, previous = previous)
        }
    }

    private fun epoch(value: String): Long = Instant.parse(value).epochSecond

    private fun envelope(data: JSONArray, zone: String = "Europe/Kyiv", offset: Int = 10800): JSONObject =
        JSONObject().put("lat", city.lat).put("lon", city.lon).put("timezone", zone)
            .put("timezone_offset", offset).put("data", data)

    private fun current(time: Long = start - 600): String = JSONObject().put("dt", time).put("timezone", 10800)
        .put("main", JSONObject().put("temp", 17.5).put("feels_like", 16.0).put("humidity", 75))
        .put("weather", JSONArray().put(JSONObject().put("id", 801).put("icon", "02d")))
        .put("wind", JSONObject().put("speed", 2.5)).toString()

    private fun hours(first: Int = 0, count: Int = 4, next: String? = null): String {
        val data = JSONArray()
        repeat(count) { index ->
            val number = first + index
            data.put(JSONObject().put("dt", firstHour + number * 3600L).put("temp", 10.0 + number / 10.0)
                .put("pop", 0.5).put("rain", JSONObject().put("1h", 0.9))
                .put("weather", JSONArray().put(JSONObject().put("id", 500).put("icon", "10d"))))
        }
        return envelope(data).apply { next?.let { put("next", it) } }.toString()
    }

    // The default daily envelope uses consecutive UTC-midnight records, including today.
    private fun days(count: Int = 10, firstDay: Long = epoch("2026-10-05T00:00:00Z")): String {
        val data = JSONArray()
        repeat(count) { index ->
            data.put(JSONObject().put("dt", firstDay + index * 86400L)
                .put("temp", JSONObject().put("day", 15.0).put("min", 6.25).put("max", 22.75))
                .put("pop", 0.1).put("weather", JSONArray().put(JSONObject().put("id", 801).put("icon", "02d"))))
        }
        return envelope(data).toString()
    }

    private fun next(cursor: Long): String =
        "https://api.openweathermap.org$hourlyPath?start=$cursor&appid=$key"

    private fun normal(call: Call): String = when (call.path) {
        currentPath -> current()
        hourlyPath -> hours()
        dailyPath -> days()
        else -> error("Unexpected endpoint ${call.path}")
    }

    @Test fun selectedOca3ProvidesTodayAndSixMoreDaysWhileHourlyStaysOnOca4() {
        val oca3Path = "/data/3.0/onecall"
        val harness = Harness { call ->
            if (call.path == oca3Path) {
                val root = JSONObject(days(count = 8))
                root.put("daily", root.remove("data")).put("appid", key).toString()
            } else normal(call)
        }
        val response = runBlocking {
            harness.repository.weather(city, WeatherUnits.IMPERIAL, key, dailyProvider = DailyForecastProvider.OCA3)
        }
        assertEquals(listOf("2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08",
            "2026-10-09", "2026-10-10", "2026-10-11"), response.report.daily.map { it.date.toString() })
        assertEquals(setOf(currentPath, hourlyPath, oca3Path), harness.calls.map { it.path }.toSet())
        assertEquals(3, harness.calls.size)
        val dailyCall = harness.calls.single { it.path == oca3Path }
        assertEquals(mapOf("lat" to city.lat.toString(), "lon" to city.lon.toString(), "units" to "imperial",
            "exclude" to "current,minutely,hourly,alerts"), dailyCall.parameters)
        assertFalse(harness.calls.single { it.path == hourlyPath }.parameters.containsKey("exclude"))
        assertEquals(4, response.report.slots.size)
        assertNull(response.report.forecastNote)
        assertFalse(response.dailyJson!!.contains(key))
        val reloaded = WeatherCacheCodec.decode(WeatherCacheCodec.encode(response, key), 4,
            city, WeatherUnits.IMPERIAL, key, start + 10)!!
        assertEquals(response.report.daily, reloaded.report.daily)
    }

    @Test fun dailyFailureNeverRequestsTheOtherProvider() {
        for (provider in DailyForecastProvider.entries) {
            for (status in listOf(401, 429, 503)) {
                val harness = Harness { call ->
                    if (call.path == provider.path) throw WeatherFailure(serviceError(status, oneCall = true)) else normal(call)
                }
                val response = runBlocking {
                    harness.repository.weather(city, WeatherUnits.METRIC, key, dailyProvider = provider)
                }
                assertEquals(setOf(currentPath, hourlyPath, provider.path), harness.calls.map { it.path }.toSet())
                assertEquals(3, harness.calls.size)
                assertTrue(response.report.daily.isEmpty())
                assertTrue(response.report.forecastNote!!.startsWith("Daily forecast:"))
            }
        }
    }

    private fun savedDaily(sourceAt: Long = start - 3600, json: String = days(),
        fetchedAt: Long = sourceAt, location: WeatherLocation = city,
        units: WeatherUnits = WeatherUnits.METRIC): WeatherResponse {
        val report = HybridWeatherParser.report(location, units, current(), hours(), null,
            fetchedAt, dailyJson = json, dailyFetchedAt = sourceAt)
        return WeatherResponse(report, current(), hours(), null, json, sourceAt)
    }

    @Test fun twoHourlyPagesCover24HoursWithinFourCallsAndPreserveParameters() {
        val page = AtomicInteger()
        val harness = Harness { call ->
            if (call.path == hourlyPath) {
                val first = page.getAndIncrement() * 20
                hours(first, 20, next(firstHour + (first + 20) * 3600L))
            } else normal(call)
        }
        val response = harness.fetch(WeatherUnits.IMPERIAL)
        assertEquals(4, harness.calls.size)
        assertEquals(2, harness.calls.count { it.path == hourlyPath })
        assertEquals(1, harness.calls.count { it.path == currentPath })
        assertEquals(1, harness.calls.count { it.path == dailyPath })
        assertFalse(harness.calls.any { it.path == "/data/4.0/onecall/current" || it.path == "/data/2.5/forecast" })
        assertEquals(24, response.report.slots.size)
        assertEquals(24, response.report.next24Hours(response.report.fetchedAt).size)
        assertEquals(7, response.report.providerDaily!!.size)
        assertEquals(6.25, response.report.daily.first().low!!, 0.0)
        assertEquals(22.75, response.report.daily.first().high!!, 0.0)
        assertEquals(firstHour, response.report.slots.first().timestamp)
        assertEquals(firstHour + 23 * 3600L, response.report.slots.last().timestamp)
        assertNull(response.report.forecastNote)
        harness.calls.forEach { call ->
            assertEquals(city.lat.toString(), call.parameters["lat"])
            assertEquals(city.lon.toString(), call.parameters["lon"])
            assertEquals("imperial", call.parameters["units"])
            assertEquals(key, call.key)
        }
        assertEquals(listOf(start - start % 3600, firstHour + 20 * 3600L),
            harness.calls.filter { it.path == hourlyPath }.map { it.parameters["start"]!!.toLong() })
        assertEquals(setOf("lat", "lon", "units"), harness.calls.single { it.path == dailyPath }.parameters.keys)
    }

    @Test fun shortTimelineWithoutNextDoesNotSpendExtraCalls() {
        val harness = Harness(handler = ::normal)
        val response = harness.fetch()
        assertEquals(3, harness.calls.size)
        assertEquals(4, response.report.slots.size)
        assertNull(response.report.forecastNote)
    }

    @Test fun overlappingPagesProduceSortedUniqueForecastPeriods() {
        val page = AtomicInteger()
        val harness = Harness { call ->
            if (call.path != hourlyPath) normal(call) else {
                val first = page.getAndIncrement() * 19
                hours(first, 20, next(firstHour + (first + 20) * 3600L))
            }
        }
        val response = harness.fetch()
        assertEquals(4, harness.calls.size)
        assertEquals(24, response.report.slots.size)
        assertTrue(response.report.slots.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })
        assertEquals(24, response.report.slots.map { it.timestamp }.toSet().size)
    }

    @Test fun emptyTimelineStopsEvenWhenItAdvertisesAnotherPage() {
        val harness = Harness { call ->
            if (call.path == hourlyPath) hours(count = 0, next = next(firstHour)) else normal(call)
        }
        val response = harness.fetch()
        assertEquals(3, harness.calls.size)
        assertTrue(response.report.slots.isEmpty())
        assertEquals(7, response.report.daily.size)
    }

    @Test fun endlessForwardPaginationStillMakesAtMostTwoHourlyRequests() {
        val harness = Harness { call ->
            if (call.path != hourlyPath) normal(call) else {
                val requested = call.parameters["start"]!!.toLong()
                envelope(JSONArray().put(JSONObject().put("dt", requested + 60).put("temp", 10)))
                    .put("next", next(requested + 3600)).toString()
            }
        }
        val response = harness.fetch()
        assertEquals(4, harness.calls.size)
        assertEquals(2, harness.calls.count { it.path == hourlyPath })
        assertEquals(2, response.report.slots.size)
    }

    @Test fun currentAuthorizationFailureFailsTheRefreshWithoutRetrying() {
        val harness = Harness { call ->
            if (call.path == currentPath) throw WeatherFailure(serviceError(401)) else normal(call)
        }
        try {
            harness.fetch()
            fail("Rejected current conditions must fail the refresh")
        } catch (failure: WeatherFailure) {
            assertTrue(failure.message!!.contains("API key"))
        }
        assertEquals(1, harness.calls.count { it.path == currentPath })
        assertTrue(harness.calls.size <= 3)
    }

    @Test fun unreadableEssentialCurrentDataFailsEvenWhenForecastsAreValid() {
        val invalidResponses = listOf("not json", "{}", """{"dt":1,"main":{}}""")
        invalidResponses.forEach { invalid ->
            val harness = Harness { call -> if (call.path == currentPath) invalid else normal(call) }
            try {
                harness.fetch()
                fail("Missing essential current data must fail")
            } catch (_: WeatherFailure) { }
            assertEquals(1, harness.calls.count { it.path == currentPath })
            assertTrue(harness.calls.size <= 3)
        }
    }

    @Test fun firstHourlyFailureKeepsFreshCurrentAndIndependentDailyData() {
        val harness = Harness { call ->
            if (call.path == hourlyPath) throw WeatherFailure(serviceError(429)) else normal(call)
        }
        val response = harness.fetch()
        assertEquals(setOf(currentPath, hourlyPath, dailyPath), harness.calls.map { it.path }.toSet())
        assertEquals(17.5, response.report.current.temperature, 0.0)
        assertTrue(response.report.slots.isEmpty())
        assertEquals(7, response.report.daily.size)
        assertTrue(response.report.forecastNote!!.contains("request limit"))
        assertNull(response.hourlyJson)
        assertNotNull(response.dailyJson)
    }

    @Test fun laterHourlyFailureKeepsEarlierGenuineSlotsWithoutRetrying() {
        val page = AtomicInteger()
        val harness = Harness { call ->
            if (call.path != hourlyPath) normal(call)
            else if (page.getAndIncrement() == 0) hours(0, 20, next(firstHour + 20 * 3600L))
            else throw WeatherFailure(serviceError(503))
        }
        val response = harness.fetch()
        assertEquals(4, harness.calls.size)
        assertEquals(20, response.report.slots.size)
        assertEquals(7, response.report.daily.size)
        assertNotNull(response.report.forecastNote)
        assertEquals(20, OneCall4Parser.hourly(response.hourlyJson!!).size)
    }

    @Test fun malformedLaterHourlyPageDoesNotEraseTheEarlierPage() {
        val page = AtomicInteger()
        val harness = Harness { call ->
            if (call.path != hourlyPath) normal(call)
            else if (page.getAndIncrement() == 0) hours(0, 20, next(firstHour + 20 * 3600L))
            else """{"data":"invalid"}"""
        }
        val response = harness.fetch()
        assertEquals(4, harness.calls.size)
        assertEquals(20, response.report.slots.size)
        assertNotNull(response.report.forecastNote)
    }

    @Test fun dailyFailureDoesNotFabricateDailyRangesFromHourlyTemperatures() {
        val harness = Harness { call ->
            if (call.path == dailyPath) throw WeatherFailure(serviceError(503)) else normal(call)
        }
        val response = harness.fetch()
        assertEquals(4, response.report.slots.size)
        assertNotNull(response.report.providerDaily)
        assertTrue(response.report.daily.isEmpty())
        assertNull(response.dailyJson)
        assertTrue(response.report.forecastNote!!.contains("Daily forecast"))
    }

    @Test fun unusablePaginationLinksRebuildTheSecondRequestFromValidatedHourlyData() {
        val later = firstHour + 20 * 3600L
        val invalid = listOf(
            "http://api.openweathermap.org$hourlyPath?start=$later",
            "https://evil.example$hourlyPath?start=$later",
            "https://api.openweathermap.org/data/4.0/onecall/timeline/1day?start=$later",
            "https://attacker@api.openweathermap.org$hourlyPath?start=$later",
            "https://api.openweathermap.org:444$hourlyPath?start=$later",
            "https://api.openweathermap.org$hourlyPath?start=$later#fragment",
            "https://api.openweathermap.org$hourlyPath?start=$later&start=$later",
            "https://api.openweathermap.org$hourlyPath?start=not-a-number",
            "https://api.openweathermap.org$hourlyPath?start=$start",
            "https://api.openweathermap.org$hourlyPath?start=${start - 1}",
            "https://api.openweathermap.org$hourlyPath?start=253402300800",
            "$hourlyPath?start=$later",
            "https://api.openweathermap.org$hourlyPath?start=$later&appid={API key}",
            "https://api.openweathermap.org$hourlyPath?start=%malformed"
        )
        invalid.forEach { link ->
            val page = AtomicInteger()
            val harness = Harness { call ->
                if (call.path != hourlyPath) normal(call)
                else if (page.getAndIncrement() == 0) hours(0, 20, next = link) else hours(20, 20)
            }
            val response = harness.fetch(WeatherUnits.IMPERIAL)
            assertEquals(4, harness.calls.size)
            assertEquals(24, response.report.slots.size)
            assertNull(response.report.forecastNote)
            val followUp = harness.calls.filter { it.path == hourlyPath }[1]
            assertEquals(later.toString(), followUp.parameters["start"])
            assertEquals(city.lat.toString(), followUp.parameters["lat"])
            assertEquals(city.lon.toString(), followUp.parameters["lon"])
            assertEquals("imperial", followUp.parameters["units"])
            assertEquals(key, followUp.key)
            assertEquals(setOf("lat", "lon", "units", "start"), followUp.parameters.keys)
            assertNull(nextHourlyStart(link, firstHour + 19 * 3600L))
        }
    }

    @Test fun overlappingCursorIsAdvancedToTheNextGenuineHourlyPeriod() {
        val page = AtomicInteger()
        val harness = Harness { call ->
            if (call.path != hourlyPath) normal(call)
            else if (page.getAndIncrement() == 0) hours(0, 20, next(firstHour + 19 * 3600L))
            else hours(20, 20)
        }
        val response = harness.fetch()
        assertEquals(4, harness.calls.size)
        assertEquals(24, response.report.slots.size)
        assertNull(response.report.forecastNote)
        assertEquals((firstHour + 20 * 3600L).toString(),
            harness.calls.filter { it.path == hourlyPath }[1].parameters["start"])
    }

    @Test fun documentedPaginationShapeUsesItsForwardUnixCursorWithoutCopyingOtherParameters() {
        val page = AtomicInteger()
        val cursor = firstHour + 20 * 3600L
        // Shape shown in OpenWeather's One Call 4.0 hourly response example.
        val link = "https://api.openweathermap.org$hourlyPath?cnt=20&lat=51.5000&lon=-0.1000&start=$cursor&appid=$key"
        val harness = Harness { call ->
            if (call.path != hourlyPath) normal(call)
            else if (page.getAndIncrement() == 0) hours(0, 20, link) else hours(20, 20)
        }
        val response = harness.fetch()
        assertEquals(24, response.report.next24Hours(response.report.fetchedAt).size)
        assertNull(response.report.forecastNote)
        val followUp = harness.calls.filter { it.path == hourlyPath }[1]
        assertEquals(cursor.toString(), followUp.parameters["start"])
        assertFalse(followUp.parameters.containsKey("cnt"))
        assertFalse(followUp.parameters.containsKey("appid"))
        assertFalse(response.hourlyJson!!.contains(key))
        assertFalse(JSONObject(response.hourlyJson).has("next"))
    }

    @Test fun malformedUnusedThirdPageLinkCannotTurnTheSecondPageIntoAnError() {
        val page = AtomicInteger()
        val harness = Harness { call ->
            if (call.path != hourlyPath) normal(call)
            else if (page.getAndIncrement() == 0) hours(0, 4, next(firstHour + 4 * 3600L))
            else hours(4, 4, "malformed third-page URL")
        }
        val response = harness.fetch()
        assertEquals(4, harness.calls.size)
        assertEquals(8, response.report.slots.size)
        assertNull(response.report.forecastNote)
    }

    @Test fun nullOrNonStringNextMeansThereIsNoAdvertisedPage() {
        listOf(JSONObject.NULL, 123L, JSONObject()).forEach { value ->
            val harness = Harness { call ->
                if (call.path == hourlyPath) JSONObject(hours()).put("next", value).toString() else normal(call)
            }
            val response = harness.fetch()
            assertEquals(3, harness.calls.size)
            assertEquals(4, response.report.slots.size)
            assertNull(response.report.forecastNote)
        }
    }

    @Test fun reconstructedCursorOutsideThe24HourWindowDoesNotSpendAnotherCall() {
        val harness = Harness { call ->
            if (call.path == hourlyPath) hours(23, 1, "unreadable cursor") else normal(call)
        }
        val response = harness.fetch()
        assertEquals(3, harness.calls.size)
        assertEquals(1, response.report.slots.size)
        assertNull(response.report.forecastNote)
    }

    @Test fun failureAfterAReconstructedCursorStillReportsTheEndpointFailure() {
        val page = AtomicInteger()
        val harness = Harness { call ->
            if (call.path != hourlyPath) normal(call)
            else if (page.getAndIncrement() == 0) hours(0, 20, "unreadable cursor")
            else throw WeatherFailure(serviceError(503))
        }
        val response = harness.fetch()
        assertEquals(4, harness.calls.size)
        assertEquals(20, response.report.slots.size)
        assertEquals("The rest of the hourly forecast is unavailable.", response.report.forecastNote)
    }

    @Test fun cursorOutsideThe24HourWindowDoesNotSpendAnotherCall() {
        val harness = Harness { call ->
            if (call.path == hourlyPath) hours(0, 20, next(start + 24 * 3600L)) else normal(call)
        }
        val response = harness.fetch()
        assertEquals(3, harness.calls.size)
        assertEquals(1, harness.calls.count { it.path == hourlyPath })
        assertEquals(20, response.report.slots.size)
    }

    @Test fun paginationAcceptsOnlyTheCursorAndPreservesSavedUnitsCoordinatesAndKey() {
        val page = AtomicInteger()
        val cursor = firstHour + 4 * 3600L
        val poisoned = "https://api.openweathermap.org$hourlyPath?start=$cursor&appid=OTHER_KEY&units=standard&lat=0&lon=0"
        val harness = Harness { call ->
            if (call.path != hourlyPath) normal(call)
            else if (page.getAndIncrement() == 0) hours(next = poisoned) else hours(4, 4)
        }
        val response = harness.fetch(WeatherUnits.IMPERIAL)
        assertEquals(8, response.report.slots.size)
        assertEquals(4, harness.calls.size)
        val followUp = harness.calls.filter { it.path == hourlyPath }[1]
        assertEquals(cursor.toString(), followUp.parameters["start"])
        assertEquals(city.lat.toString(), followUp.parameters["lat"])
        assertEquals(city.lon.toString(), followUp.parameters["lon"])
        assertEquals("imperial", followUp.parameters["units"])
        assertEquals(key, followUp.key)
        assertFalse(followUp.parameters.containsKey("appid"))
    }

    @Test fun credentialBearingEnvelopeFieldsAreRemovedFromEveryCachedResponse() {
        val harness = Harness { call ->
            JSONObject(normal(call)).put("next", next(start + 60))
                .put("prev", next(start - 60)).put("appid", key).put("extra", key).toString()
        }
        val response = harness.fetch()
        listOfNotNull(response.currentJson, response.hourlyJson, response.dailyJson).forEach { json ->
            assertFalse(json.contains(key))
            val envelope = JSONObject(json)
            assertFalse(envelope.has("next"))
            assertFalse(envelope.has("prev"))
            assertFalse(envelope.has("appid"))
            assertFalse(envelope.has("extra"))
        }
        assertTrue(JSONObject(response.currentJson).has("main"))
        assertTrue(JSONObject(response.hourlyJson!!).has("data"))
        assertTrue(JSONObject(response.dailyJson!!).has("data"))
    }

    @Test fun recordsOutsideRequestedWindowDoNotPolluteHourlyCacheOrReport() {
        val harness = Harness { call ->
            if (call.path != hourlyPath) normal(call) else {
                val json = JSONObject(hours())
                json.getJSONArray("data").put(JSONObject().put("dt", start - 3600).put("temp", 99))
                    .put(JSONObject().put("dt", start + 24 * 3600L).put("temp", 99))
                json.toString()
            }
        }
        val response = harness.fetch()
        assertEquals(4, response.report.slots.size)
        assertEquals(4, JSONObject(response.hourlyJson!!).getJSONArray("data").length())
        assertTrue(response.report.slots.all { it.timestamp >= start && it.timestamp < start + 24 * 3600L })
    }

    @Test fun successfulCompletionTimestampIsIndependentOfProviderObservationTime() {
        val completed = start + 125
        val harness = Harness(completedAt = completed, handler = ::normal)
        val response = harness.fetch()
        assertEquals(completed, response.report.fetchedAt)
        assertEquals(completed, response.dailyFetchedAt)
        assertEquals(completed, response.report.dailyFetchedAt)
        assertFalse(response.report.dailyFromCache)
        assertFalse(response.report.dailyLoading)
        assertEquals(start - 600, response.report.current.timestamp)
        assertEquals(3, harness.calls.size)
    }

    @Test fun threeInitialRequestsRunConcurrentlyInsteadOfWaitingForEachOther() = runBlocking {
        val count = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = Harness { call ->
            if (count.incrementAndGet() == 3) entered.complete(Unit)
            release.await()
            normal(call)
        }
        val pending = async { harness.repository.weather(city, WeatherUnits.METRIC, key) }
        try {
            withTimeout(5000) { entered.await() }
            assertFalse(pending.isCompleted)
            release.complete(Unit)
            val response = withTimeout(5000) { pending.await() }
            assertEquals(17.5, response.report.current.temperature, 0.0)
            assertEquals(setOf(currentPath, hourlyPath, dailyPath), harness.calls.map { it.path }.toSet())
            assertEquals(3, harness.calls.size)
        } finally {
            release.complete(Unit)
            pending.cancelAndJoin()
        }
    }

    @Test fun slowDailyRequestPublishesCoreOnceWithoutAnErrorAndKeepsItsRefreshTime() = runBlocking {
        val clock = AtomicLong(start)
        val calls = CopyOnWriteArrayList<Call>()
        val allEntered = CompletableDeferred<Unit>()
        val releaseCore = CompletableDeferred<Unit>()
        val releaseDaily = CompletableDeferred<Unit>()
        val coreReady = CompletableDeferred<WeatherReport>()
        val callbacks = CopyOnWriteArrayList<WeatherReport>()
        val repository = WeatherRepository({ path, parameters, suppliedKey ->
            val call = Call(path, parameters.toMap(), suppliedKey)
            calls += call
            if (calls.size == 3) allEntered.complete(Unit)
            if (path == dailyPath) releaseDaily.await() else releaseCore.await()
            normal(call)
        }, { clock.get() })
        val pending = async {
            repository.weather(city, WeatherUnits.METRIC, key) { report ->
                callbacks += report
                coreReady.complete(report)
            }
        }
        try {
            withTimeout(5000) { allEntered.await() }
            clock.set(start + 2)
            releaseCore.complete(Unit)
            val core = withTimeout(5000) { coreReady.await() }
            assertFalse("The daily request is deliberately still waiting", pending.isCompleted)
            assertEquals(17.5, core.current.temperature, 0.0)
            assertEquals(4, core.slots.size)
            assertTrue(core.daily.isEmpty())
            assertTrue(core.dailyLoading)
            assertNull("Loading daily data must not display a temporary failure", core.forecastNote)
            assertEquals(start + 2, core.fetchedAt)
            clock.set(start + 125)
            releaseDaily.complete(Unit)
            val response = withTimeout(5000) { pending.await() }
            assertEquals(7, response.report.daily.size)
            assertNull(response.report.forecastNote)
            assertEquals(core.fetchedAt, response.report.fetchedAt)
            assertEquals(start + 125, response.dailyFetchedAt)
            assertFalse(response.report.dailyLoading)
            assertEquals(1, callbacks.size)
            assertEquals(setOf(currentPath, hourlyPath, dailyPath), calls.map { it.path }.toSet())
            assertEquals(3, calls.size)
        } finally {
            releaseCore.complete(Unit)
            releaseDaily.complete(Unit)
            pending.cancelAndJoin()
        }
    }

    @Test fun dailyDeadlineCancelsAStuckRequestAndReturnsUsableCoreWithoutTimestampDrift() = runBlocking {
        val clock = AtomicLong(start)
        val calls = CopyOnWriteArrayList<Call>()
        val dailyEntered = CompletableDeferred<Unit>()
        val canceledDaily = AtomicInteger()
        val callbacks = CopyOnWriteArrayList<WeatherReport>()
        val repository = WeatherRepository({ path, parameters, suppliedKey ->
            val call = Call(path, parameters.toMap(), suppliedKey)
            calls += call
            if (path == dailyPath) {
                dailyEntered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    canceledDaily.incrementAndGet()
                }
            } else {
                dailyEntered.await()
                clock.set(start + 2)
                normal(call)
            }
        }, { clock.get() }, dailyTimeoutMillis = 200)
        val beganAt = System.nanoTime()
        val response = withTimeout(2000) {
            repository.weather(city, WeatherUnits.METRIC, key) { report ->
                callbacks += report
                clock.set(start + 125)
            }
        }
        val elapsedMillis = (System.nanoTime() - beganAt) / 1_000_000
        assertTrue("A stuck daily request must finish within the bounded test allowance", elapsedMillis < 2000)
        assertEquals(17.5, response.report.current.temperature, 0.0)
        assertEquals(4, response.report.slots.size)
        assertTrue(response.report.daily.isEmpty())
        assertNull(response.dailyJson)
        val note = response.report.forecastNote!!
        assertTrue(note.contains("took too long"))
        assertEquals(1, Regex("Daily forecast:").findAll(note).count())
        assertEquals(1, canceledDaily.get())
        assertEquals(1, callbacks.size)
        assertNull(callbacks.single().forecastNote)
        assertEquals(start + 2, callbacks.single().fetchedAt)
        assertEquals(callbacks.single().fetchedAt, response.report.fetchedAt)
        assertEquals(1, calls.count { it.path == dailyPath })
        assertEquals(3, calls.size)
    }

    @Test fun requiredCurrentFailureNeverPublishesCoreAndCancelsBothForecasts() = runBlocking {
        supervisorScope {
            val hourlyEntered = CompletableDeferred<Unit>()
            val dailyEntered = CompletableDeferred<Unit>()
            val neverReleased = CompletableDeferred<Unit>()
            val canceledForecasts = AtomicInteger()
            val callbacks = AtomicInteger()
            val harness = Harness { call ->
                if (call.path == currentPath) {
                    hourlyEntered.await()
                    dailyEntered.await()
                    throw WeatherFailure(serviceError(401))
                }
                if (call.path == hourlyPath) hourlyEntered.complete(Unit) else dailyEntered.complete(Unit)
                try {
                    neverReleased.await()
                    normal(call)
                } finally {
                    canceledForecasts.incrementAndGet()
                }
            }
            val pending = async {
                harness.repository.weather(city, WeatherUnits.METRIC, key) { callbacks.incrementAndGet() }
            }
            try {
                withTimeout(5000) { pending.await() }
                fail("A failed required current response must fail the whole request")
            } catch (failure: WeatherFailure) {
                assertTrue(failure.message!!.contains("API key"))
            } finally {
                pending.cancelAndJoin()
            }
            assertEquals(0, callbacks.get())
            assertEquals(2, canceledForecasts.get())
            assertEquals(3, harness.calls.size)
        }
    }

    @Test fun cancellationBeforeCoreIsReadyPublishesNothingAndCancelsAllRequests() = runBlocking {
        val entered = AtomicInteger()
        val allEntered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        val canceled = AtomicInteger()
        val callbacks = AtomicInteger()
        val harness = Harness { call ->
            if (entered.incrementAndGet() == 3) allEntered.complete(Unit)
            try {
                neverReleased.await()
                normal(call)
            } finally {
                canceled.incrementAndGet()
            }
        }
        val pending = async {
            harness.repository.weather(city, WeatherUnits.METRIC, key) { callbacks.incrementAndGet() }
        }
        try {
            withTimeout(5000) { allEntered.await() }
        } finally {
            pending.cancelAndJoin()
        }
        assertTrue(pending.isCancelled)
        assertEquals(0, callbacks.get())
        assertEquals(3, canceled.get())
        assertEquals(3, harness.calls.size)
    }

    @Test fun cancellationAfterCoreIsReadyCancelsDailyAndNeverReturnsACompletedRefresh() = runBlocking {
        val dailyEntered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        val coreReady = CompletableDeferred<Unit>()
        val callbacks = AtomicInteger()
        val dailyCanceled = AtomicInteger()
        val harness = Harness { call ->
            if (call.path == dailyPath) {
                dailyEntered.complete(Unit)
                try {
                    neverReleased.await()
                    normal(call)
                } finally {
                    dailyCanceled.incrementAndGet()
                }
            } else {
                dailyEntered.await()
                normal(call)
            }
        }
        val pending = async {
            harness.repository.weather(city, WeatherUnits.METRIC, key) {
                callbacks.incrementAndGet()
                coreReady.complete(Unit)
            }
        }
        try {
            withTimeout(5000) { coreReady.await() }
            assertFalse(pending.isCompleted)
        } finally {
            pending.cancelAndJoin()
        }
        assertTrue(pending.isCancelled)
        assertEquals(1, callbacks.get())
        assertEquals(1, dailyCanceled.get())
        assertEquals(3, harness.calls.size)
    }

    @Test fun invalidDailyForecastSchemaIsReportedAsUnavailableRatherThanHealthyEmptyData() {
        listOf("not json", "{}", """{"data":"invalid"}""", """{"data":[{}]}""").forEach { invalid ->
            val harness = Harness { call -> if (call.path == dailyPath) invalid else normal(call) }
            val response = harness.fetch()
            assertEquals(4, response.report.slots.size)
            assertTrue(response.report.daily.isEmpty())
            assertNull(response.dailyJson)
            assertNotNull(response.report.forecastNote)
            assertEquals(3, harness.calls.size)
        }
    }

    @Test fun extraHourlyRecordsBeyond24HoursAreExcludedFromCacheAndReport() {
        val harness = Harness { call ->
            if (call.path == hourlyPath) hours(count = 48) else normal(call)
        }
        val response = harness.fetch()
        assertEquals(24, response.report.slots.size)
        assertEquals(24, JSONObject(response.hourlyJson!!).getJSONArray("data").length())
        assertTrue(response.report.slots.all { it.timestamp >= start && it.timestamp < start + 24 * 3600L })
    }

    @Test fun oneDailyPageIncludesTodayAndTheNextSixDaysWithoutFollowingLinks() {
        val harness = Harness { call ->
            if (call.path == dailyPath) JSONObject(days()).put("next",
                "https://api.openweathermap.org$dailyPath?start=${start + 10 * 86400L}&appid=$key").toString()
            else normal(call)
        }
        val response = harness.fetch()
        val today = response.report.localTime(start).toLocalDate()
        assertEquals((0L..6L).map { today.plusDays(it) }, response.report.daily.map { it.date })
        assertEquals(1, harness.calls.count { it.path == dailyPath })
        assertEquals(3, harness.calls.size)
        assertNull(response.forecastJson)
        assertNull(response.report.dailySlots)
        assertFalse(response.dailyJson!!.contains(key))
        assertNull(response.report.forecastNote)
    }

    @Test fun incompleteDailyPageKeepsGenuineDaysWithoutExtraRequestsOrInventedRanges() {
        val harness = Harness { call -> if (call.path == dailyPath) days(count = 4) else normal(call) }
        val response = harness.fetch()
        assertEquals(4, response.report.daily.size)
        assertEquals(1, harness.calls.count { it.path == dailyPath })
        assertEquals(3, harness.calls.size)
        assertEquals("The rest of the daily forecast is currently unavailable.", response.report.forecastNote)
        assertEquals(6.25, response.report.daily.last().low!!, 0.0)
        assertEquals(22.75, response.report.daily.last().high!!, 0.0)
    }

    @Test fun lateEveningDefaultDailyRequestKeepsTodayAfterItsForecastTimestamp() {
        val evening = epoch("2026-10-05T20:59:00Z")
        val harness = Harness(startedAt = evening) { call ->
            if (call.path == dailyPath) {
                assertEquals(setOf("lat", "lon", "units"), call.parameters.keys)
                days()
            } else normal(call)
        }
        val response = harness.fetch()
        assertEquals(response.report.localTime(evening).toLocalDate(), response.report.daily.first().date)
        assertEquals(7, response.report.daily.size)
    }

    @Test fun defaultDailyPageKeepsSevenCityDatesAtTheEndOfA25HourDstDay() {
        val evening = epoch("2026-10-25T21:59:00Z")
        val firstDay = epoch("2026-10-25T00:00:00Z")
        val harness = Harness(startedAt = evening) { call ->
            if (call.path == dailyPath) {
                assertFalse(call.parameters.containsKey("start"))
                JSONObject(days(firstDay = firstDay)).put("timezone_offset", 7200).toString()
            } else normal(call)
        }
        val response = harness.fetch()
        val today = response.report.localTime(evening).toLocalDate()
        assertEquals("2026-10-25", today.toString())
        assertEquals((0L..6L).map { today.plusDays(it) }, response.report.daily.map { it.date })
        assertEquals(1, harness.calls.count { it.path == dailyPath })
        assertEquals(3, harness.calls.size)
    }

    @Test fun geocodingUsesItsExistingEndpointWithoutFetchingWeather() = runBlocking {
        val harness = Harness { call ->
            assertEquals("/geo/1.0/direct", call.path)
            """[{"name":"Kyiv","country":"UA","lat":50.45,"lon":30.52}]"""
        }
        val locations = harness.repository.search("Kyiv,UA", key)
        assertEquals(1, locations.size)
        assertEquals(city.identity, locations.single().identity)
        assertEquals(mapOf("q" to "Kyiv,UA", "limit" to "5"), harness.calls.single().parameters)
        assertEquals(key, harness.calls.single().key)
    }

    @Test fun slowDailyKeepsSavedDaysVisibleWhileBothCoreCallbacksRunOnce() = runBlocking {
        val clock = AtomicLong(start)
        val dailyEntered = CompletableDeferred<Unit>()
        val releaseDaily = CompletableDeferred<Unit>()
        val coreReady = CompletableDeferred<WeatherResponse>()
        val reports = CopyOnWriteArrayList<WeatherReport>()
        val calls = CopyOnWriteArrayList<Call>()
        val prior = savedDaily()
        val repository = WeatherRepository({ path, parameters, suppliedKey ->
            val call = Call(path, parameters, suppliedKey)
            calls += call
            if (path == dailyPath) {
                dailyEntered.complete(Unit)
                releaseDaily.await()
            } else dailyEntered.await()
            normal(call)
        }, { clock.get() })
        val pending = async {
            repository.weather(city, WeatherUnits.METRIC, key,
                onCoreReady = { reports += it }, previous = prior,
                onCoreResponse = { coreReady.complete(it) })
        }
        try {
            val core = withTimeout(2_000) { coreReady.await() }
            assertFalse(pending.isCompleted)
            assertEquals(7, core.report.daily.size)
            assertTrue(core.report.dailyFromCache)
            assertTrue(core.report.dailyLoading)
            assertEquals(prior.dailyFetchedAt, core.dailyFetchedAt)
            assertEquals(prior.dailyJson, core.dailyJson)
            assertNull(core.report.forecastNote)
            // The legacy callback runs after the response callback returns.
            withTimeout(2_000) { while (reports.isEmpty()) kotlinx.coroutines.yield() }
            assertEquals(core.report, reports.single())
            clock.set(start + 20)
            releaseDaily.complete(Unit)
            val result = withTimeout(2_000) { pending.await() }
            assertEquals(core.report.fetchedAt, result.report.fetchedAt)
            assertEquals(start + 20, result.dailyFetchedAt)
            assertEquals(result.dailyFetchedAt, result.report.dailyFetchedAt)
            assertFalse(result.report.dailyFromCache)
            assertFalse(result.report.dailyLoading)
            assertEquals(1, reports.size)
            assertEquals(3, calls.size)
            assertEquals(1, calls.count { it.path == dailyPath })
        } finally {
            pending.cancelAndJoin()
        }
    }

    @Test fun aFailedDailyRefreshKeepsOriginalSavedSourceTimeRatherThanCurrentRefreshTime() {
        val prior = savedDaily(sourceAt = start - 86400, fetchedAt = start - 20)
        val harness = Harness { call ->
            if (call.path == dailyPath) throw WeatherFailure("OpenWeather took too long to respond. Please try again.")
            else normal(call)
        }
        val result = harness.fetch(previous = prior)
        assertEquals(7, result.report.daily.size)
        assertEquals(prior.dailyJson, result.dailyJson)
        assertEquals(start + 2, result.report.fetchedAt)
        assertEquals(start - 86400, result.dailyFetchedAt)
        assertEquals(result.dailyFetchedAt, result.report.dailyFetchedAt)
        assertTrue(result.report.dailyFromCache)
        assertFalse(result.report.dailyLoading)
        assertTrue(result.report.forecastNote!!.contains("took too long"))
        assertTrue(result.report.forecastNote!!.contains("saved forecast"))
        assertEquals(3, harness.calls.size)
    }

    @Test fun emptyOrUnreadableNewDailyDataDoesNotEraseTheSavedForecast() {
        val unusable = listOf("not json", "{}", """{"data":[]}""", """{"data":[{}]}""",
            envelope(JSONArray().put(JSONObject().put("dt", start))).toString(),
            days(count = 2, firstDay = start - 5 * 86400))
        for (daily in unusable) {
            val prior = savedDaily()
            val harness = Harness { call -> if (call.path == dailyPath) daily else normal(call) }
            val result = harness.fetch(previous = prior)
            assertEquals(7, result.report.daily.size)
            assertEquals(prior.dailyJson, result.dailyJson)
            assertEquals(prior.dailyFetchedAt, result.dailyFetchedAt)
            assertTrue(result.report.dailyFromCache)
            assertTrue(result.report.forecastNote!!.contains("saved forecast"))
            assertEquals(3, harness.calls.size)
        }
    }

    @Test fun dailyReuseRejectsFutureExpiredOrUnknownSourceTimesEvenWhenCoreDataIsNew() {
        val sources = listOf(start + 60, start - 48 * 3600 - 1, -1L, 0L, 253402300800L)
        for (source in sources) {
            val harness = Harness(completedAt = start) { call ->
                if (call.path == dailyPath) throw WeatherFailure(serviceError(503)) else normal(call)
            }
            val result = harness.fetch(previous = savedDaily(sourceAt = source, fetchedAt = start - 10))
            assertTrue(result.report.daily.isEmpty())
            assertFalse(result.report.dailyFromCache)
            assertNull(result.dailyJson)
            assertNull(result.dailyFetchedAt)
            assertFalse(result.report.forecastNote!!.contains("saved forecast"))
        }
        val reportWithoutSource = savedDaily().let { it.copy(report = it.report.copy(dailyFetchedAt = null), dailyFetchedAt = null) }
        val harness = Harness { call -> if (call.path == dailyPath) throw WeatherFailure(serviceError(503)) else normal(call) }
        assertTrue(harness.fetch(previous = reportWithoutSource).report.daily.isEmpty())
    }

    @Test fun dailyReuseAcceptsExactly48HoursAndUsesTheReportSourceTimestampForCompatibility() {
        val source = start - 48 * 3600
        val prior = savedDaily(sourceAt = source).let { it.copy(dailyFetchedAt = null) }
        val harness = Harness(completedAt = start) { call ->
            if (call.path == dailyPath) throw WeatherFailure(serviceError(503)) else normal(call)
        }
        val result = harness.fetch(previous = prior)
        assertEquals(7, result.report.daily.size)
        assertEquals(source, result.dailyFetchedAt)
        assertTrue(result.report.dailyFromCache)
    }

    @Test fun dailyReuseNeverCrossesLocationOrUnitBoundaries() {
        val otherCity = city.copy(name = "Lviv", lat = 49.84, lon = 24.03)
        for (prior in listOf(savedDaily(location = otherCity), savedDaily(units = WeatherUnits.IMPERIAL))) {
            val harness = Harness { call ->
                if (call.path == dailyPath) throw WeatherFailure(serviceError(503)) else normal(call)
            }
            val result = harness.fetch(previous = prior)
            assertTrue(result.report.daily.isEmpty())
            assertNull(result.dailyJson)
            assertFalse(result.report.dailyFromCache)
            assertEquals(3, harness.calls.size)
        }
    }

    @Test fun reusedDailyRecordsAreFilteredByTodayWithoutInventingAReplacementSeventhDay() {
        val nextDay = start + 86400
        val prior = savedDaily(json = days(count = 7))
        val harness = Harness(startedAt = nextDay) { call ->
            if (call.path == dailyPath) throw WeatherFailure(serviceError(503)) else normal(call)
        }
        val result = harness.fetch(previous = prior)
        val today = result.report.localTime(nextDay).toLocalDate()
        assertEquals((0L..5L).map { today.plusDays(it) }, result.report.daily.map { it.date })
        assertEquals(6, result.report.daily.size)
        assertTrue(result.report.dailyFromCache)
        assertEquals(prior.dailyFetchedAt, result.dailyFetchedAt)
        assertEquals(3, harness.calls.size)
    }

    @Test fun unusableSavedJsonAndEntirelyPastDatesAreNeverDisplayedAsCachedForecasts() {
        val prior = savedDaily()
        for (json in listOf("not json", "{}", """{"data":[]}""", days(count = 1, firstDay = start - 3 * 86400))) {
            val harness = Harness { call ->
                if (call.path == dailyPath) throw WeatherFailure(serviceError(503)) else normal(call)
            }
            val result = harness.fetch(previous = prior.copy(dailyJson = json))
            assertTrue(result.report.daily.isEmpty())
            assertNull(result.dailyJson)
            assertNull(result.dailyFetchedAt)
            assertFalse(result.report.dailyFromCache)
        }
    }

    @Test fun timedOutDailyRequestRetainsSavedDataAndCancelsItsOnlyCall() = runBlocking {
        val dailyEntered = CompletableDeferred<Unit>()
        val canceled = AtomicInteger()
        val calls = CopyOnWriteArrayList<Call>()
        val core = CompletableDeferred<WeatherResponse>()
        val prior = savedDaily()
        val repository = WeatherRepository({ path, parameters, suppliedKey ->
            val call = Call(path, parameters, suppliedKey)
            calls += call
            if (path == dailyPath) {
                dailyEntered.complete(Unit)
                try { awaitCancellation() } finally { canceled.incrementAndGet() }
            } else {
                dailyEntered.await()
                normal(call)
            }
        }, { start }, dailyTimeoutMillis = 250)
        val result = withTimeout(2_000) {
            repository.weather(city, WeatherUnits.METRIC, key, previous = prior,
                onCoreResponse = { core.complete(it) })
        }
        assertTrue(core.await().report.dailyLoading)
        assertTrue(core.await().report.dailyFromCache)
        assertEquals(prior.dailyFetchedAt, result.dailyFetchedAt)
        assertEquals(7, result.report.daily.size)
        assertFalse(result.report.dailyLoading)
        assertEquals(1, canceled.get())
        assertEquals(3, calls.size)
        assertEquals(1, calls.count { it.path == dailyPath })
    }

    @Test fun cancelingAfterAReusableCoreResponseCancelsThePendingDailyRequest() = runBlocking {
        val dailyEntered = CompletableDeferred<Unit>()
        val core = CompletableDeferred<WeatherResponse>()
        val canceled = AtomicInteger()
        val completed = AtomicInteger()
        val prior = savedDaily()
        val repository = WeatherRepository({ path, parameters, suppliedKey ->
            if (path == dailyPath) {
                dailyEntered.complete(Unit)
                try { awaitCancellation() } finally { canceled.incrementAndGet() }
            } else {
                dailyEntered.await()
                normal(Call(path, parameters, suppliedKey))
            }
        }, { start })
        val pending = async {
            repository.weather(city, WeatherUnits.METRIC, key, previous = prior,
                onCoreResponse = { core.complete(it) }).also { completed.incrementAndGet() }
        }
        try {
            val cached = withTimeout(2_000) { core.await() }
            assertEquals(7, cached.report.daily.size)
            assertTrue(cached.report.dailyFromCache)
            assertTrue(cached.report.dailyLoading)
        } finally { pending.cancelAndJoin() }
        assertEquals(1, canceled.get())
        assertEquals(0, completed.get())
    }

    @Test fun aFreshDailyPageMissingTodayCannotReplaceASavedForecastThatIncludesToday() {
        val prior = savedDaily()
        val tomorrow = epoch("2026-10-06T00:00:00Z")
        val harness = Harness { call -> if (call.path == dailyPath) days(firstDay = tomorrow) else normal(call) }
        val result = harness.fetch(previous = prior)
        assertEquals(prior.dailyJson, result.dailyJson)
        assertEquals(7, result.report.daily.size)
        assertEquals(result.report.localTime(start).toLocalDate(), result.report.daily.first().date)
        assertTrue(result.report.dailyFromCache)
        assertEquals(prior.dailyFetchedAt, result.dailyFetchedAt)
        assertTrue(result.report.forecastNote!!.contains("saved forecast"))
        assertEquals(3, harness.calls.size)
    }

    @Test fun savedDailyPageMissingTodayIsRejectedRatherThanLabeledAsASevenDayOutlook() {
        val prior = savedDaily(json = days(firstDay = epoch("2026-10-06T00:00:00Z")))
        val harness = Harness { call ->
            if (call.path == dailyPath) throw WeatherFailure(serviceError(503)) else normal(call)
        }
        val result = harness.fetch(previous = prior)
        assertTrue(result.report.daily.isEmpty())
        assertNull(result.dailyJson)
        assertNull(result.dailyFetchedAt)
        assertFalse(result.report.dailyFromCache)
        assertFalse(result.report.forecastNote!!.contains("saved forecast"))
    }

    @Test fun dayRolloverWhileDailyWaitsRevalidatesAndDropsForecastThatEndedYesterday() = runBlocking {
        val beforeMidnight = epoch("2026-10-05T20:59:59Z")
        val afterMidnight = epoch("2026-10-05T21:00:01Z")
        val clock = AtomicLong(beforeMidnight)
        val source = beforeMidnight - 3600
        val prior = savedDaily(sourceAt = source, json = days(count = 1))
        val dailyEntered = CompletableDeferred<Unit>()
        val releaseDaily = CompletableDeferred<Unit>()
        val core = CompletableDeferred<WeatherResponse>()
        val repository = WeatherRepository({ path, parameters, suppliedKey ->
            if (path == dailyPath) {
                dailyEntered.complete(Unit)
                releaseDaily.await()
                throw WeatherFailure(serviceError(503))
            }
            dailyEntered.await()
            normal(Call(path, parameters, suppliedKey))
        }, { clock.get() })
        val pending = async {
            repository.weather(city, WeatherUnits.METRIC, key, previous = prior,
                onCoreResponse = { core.complete(it) })
        }
        try {
            val before = withTimeout(2_000) { core.await() }
            assertEquals(1, before.report.daily.size)
            assertTrue(before.report.dailyFromCache)
            clock.set(afterMidnight)
            releaseDaily.complete(Unit)
            val after = withTimeout(2_000) { pending.await() }
            assertEquals(before.report.fetchedAt, after.report.fetchedAt)
            assertTrue(after.report.daily.isEmpty())
            assertNull(after.dailyJson)
            assertNull(after.dailyFetchedAt)
            assertFalse(after.report.dailyFromCache)
            assertFalse(after.report.forecastNote!!.contains("saved forecast"))
        } finally { pending.cancelAndJoin() }
    }
}
