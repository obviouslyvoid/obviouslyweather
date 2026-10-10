// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Exercises the production age decision and shared request lock without Android or API calls. */
class WeatherRefreshGateTest {
    private val now = 1_791_187_200L
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private val otherCity = WeatherLocation("London", "GB", null, 51.51, -0.13)
    private val key = "LOCAL_GATE_TEST_SENTINEL"

    private fun report(
        fetchedAt: Long = now,
        location: WeatherLocation = city,
        units: WeatherUnits = WeatherUnits.METRIC,
        observedAt: Long = now - 7200
    ): WeatherReport = WeatherReport(
        location, units,
        CurrentWeather(observedAt, 17.0, null, 800, "Clear", false,
            null, null, null, null, null, null, null, null, null),
        emptyList(), 0, fetchedAt
    )

    private fun response(report: WeatherReport): WeatherResponse = WeatherResponse(report, "{}", null, null)

    private fun request(
        cached: WeatherReport? = null,
        location: WeatherLocation = city,
        units: WeatherUnits = WeatherUnits.METRIC,
        suppliedKey: String = key,
        intervalMinutes: Int = RefreshPolicy.DEFAULT_INTERVAL_MINUTES,
        cachedSupportsCurrentFormat: Boolean = true,
        cachedResponse: WeatherResponse? = null,
        dailyProvider: DailyForecastProvider = DailyForecastProvider.OCA4
    ): RefreshRequest = RefreshRequest(location, units, suppliedKey, cached, intervalMinutes, cachedSupportsCurrentFormat, cachedResponse, dailyProvider)

    private inner class Harness(var loaded: RefreshRequest? = request()) {
        var clock = now
        val gate = WeatherRefreshGate { clock }
        var fetches = 0
        var accepts = 0
        var fetch: suspend (RefreshRequest) -> WeatherResponse? = {
            response(report(clock, it.location, it.units))
        }
        var onAccepted: suspend (WeatherResponse) -> Unit = {}

        suspend fun refresh(intent: RefreshIntent = RefreshIntent.AUTOMATIC): RefreshResult = gate.refresh(
            intent,
            load = { loaded },
            fetch = { fetches++; fetch(it) },
            accepted = { accepts++; onAccepted(it) }
        )
    }

    @Test fun providerChangeRejectsPendingDailyAndPropagatesNewSelection() = runBlocking {
        val gate = WeatherRefreshGate { now }
        var loaded = request()
        val coreReady = CompletableDeferred<Unit>()
        val releaseDaily = CompletableDeferred<Unit>()
        val oldCore = response(report().copy(dailyLoading = true))
        val oldFinal = response(oldCore.report.copy(dailyLoading = false))
        val accepted = mutableListOf<WeatherResponse>()
        val pending = async {
            gate.refreshStaged(RefreshIntent.FORCE, { loaded }, { active, publish ->
                assertEquals(DailyForecastProvider.OCA4, active.dailyProvider)
                assertTrue(publish(oldCore))
                coreReady.complete(Unit)
                releaseDaily.await()
                oldFinal
            }, { accepted += it })
        }
        try {
            withTimeout(2000) { coreReady.await() }
            loaded = request(dailyProvider = DailyForecastProvider.OCA3)
            releaseDaily.complete(Unit)
            assertEquals(RefreshResult.Skipped, withTimeout(2000) { pending.await() })
            assertEquals(listOf(oldCore), accepted)
            val result = gate.refresh(RefreshIntent.AUTOMATIC, { loaded }, { active ->
                assertEquals(DailyForecastProvider.OCA3, active.dailyProvider)
                assertNull(active.cachedResponse)
                response(report())
            }, { accepted += it })
            assertTrue(result is RefreshResult.Updated)
            assertEquals(2, accepted.size)
        } finally {
            releaseDaily.complete(Unit)
            pending.cancelAndJoin()
        }
    }

    @Test fun concurrentGuardedRequestsMakeOneFetchAndReuseItsCompletionTime() = runBlocking {
        val harness = Harness()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fresh = report()
        harness.fetch = {
            entered.complete(Unit)
            release.await()
            response(fresh)
        }
        val first = async { harness.refresh() }
        entered.await()
        // UNDISPATCHED attempts the mutex before returning, without a scheduler delay.
        val second = async(start = CoroutineStart.UNDISPATCHED) { harness.refresh(RefreshIntent.MANUAL) }
        try {
            assertFalse("The second decision must wait for the in-flight fetch", second.isCompleted)
            assertEquals(1, harness.fetches)
            release.complete(Unit)
            assertTrue(first.await() is RefreshResult.Updated)
            val reused = second.await()
            assertTrue(reused is RefreshResult.Recent)
            assertSame(fresh, (reused as RefreshResult.Recent).report)
            assertEquals(1, harness.fetches)
            assertEquals(1, harness.accepts)
        } finally {
            release.complete(Unit)
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test fun manualAndBackgroundRequestsDoNotFetchDataYoungerThanThirtyMinutes() = runBlocking {
        for (intent in listOf(RefreshIntent.AUTOMATIC, RefreshIntent.MANUAL)) {
            val cached = report(now - 1799)
            val harness = Harness(request(cached))
            val result = harness.refresh(intent)
            assertTrue(result is RefreshResult.Recent)
            assertSame(cached, (result as RefreshResult.Recent).report)
            assertEquals(0, harness.fetches)
            assertEquals(0, harness.accepts)
        }
    }

    @Test fun dataAtExactlyThirtyMinutesRefreshesNormallyForBothGuardedIntents() = runBlocking {
        for (intent in listOf(RefreshIntent.AUTOMATIC, RefreshIntent.MANUAL)) {
            val harness = Harness(request(report(now - 1800)))
            assertTrue(harness.refresh(intent) is RefreshResult.Updated)
            assertEquals(1, harness.fetches)
            assertEquals(1, harness.accepts)
        }
    }

    @Test fun confirmedManualUpdatesBypassBothRecentDiskAndRecentMemoryReports() = runBlocking {
        val harness = Harness(request(report()))
        assertTrue(harness.refresh(RefreshIntent.FORCE) is RefreshResult.Updated)
        assertTrue(harness.refresh(RefreshIntent.FORCE) is RefreshResult.Updated)
        assertEquals(2, harness.fetches)
        assertEquals(2, harness.accepts)
    }

    @Test fun providerObservationTimeNeverControlsTheGate() = runBlocking {
        val recentlyFetched = Harness(request(report(now - 60, observedAt = now - 86400)))
        assertTrue(recentlyFetched.refresh(RefreshIntent.MANUAL) is RefreshResult.Recent)
        assertEquals(0, recentlyFetched.fetches)

        val oldFetch = Harness(request(report(now - 1800, observedAt = now)))
        assertTrue(oldFetch.refresh() is RefreshResult.Updated)
        assertEquals(1, oldFetch.fetches)
    }

    @Test fun successfulMemoryDataSurvivesAPrivateCacheWriteFailure() = runBlocking {
        val stale = report(now - 3600)
        val fresh = report()
        val harness = Harness(request(stale))
        var attemptedWrites = 0
        harness.fetch = { response(fresh) }
        // WeatherSync deliberately catches persistence failures while publishing the response.
        harness.onAccepted = {
            attemptedWrites++
            runCatching { throw IOException("Local test cache is unavailable") }
        }
        assertTrue(harness.refresh() is RefreshResult.Updated)
        assertSame(stale, harness.loaded!!.cached)
        harness.clock++
        val reused = harness.refresh()
        assertTrue(reused is RefreshResult.Recent)
        assertSame(fresh, (reused as RefreshResult.Recent).report)
        assertEquals(1, harness.fetches)
        assertEquals(1, harness.accepts)
        assertEquals(1, attemptedWrites)
    }

    @Test fun newerPersistedDataWinsOverAnOlderInMemorySuccess() = runBlocking {
        val olderMemory = report(now - 100)
        val newerDisk = report(now - 20)
        val harness = Harness()
        harness.fetch = { response(olderMemory) }
        assertTrue(harness.refresh() is RefreshResult.Updated)
        harness.loaded = request(newerDisk)
        val result = harness.refresh(RefreshIntent.MANUAL)
        assertTrue(result is RefreshResult.Recent)
        assertSame(newerDisk, (result as RefreshResult.Recent).report)
        assertEquals(1, harness.fetches)
    }

    @Test fun memorySuccessDoesNotSuppressAChangedCityUnitsOrKey() = runBlocking {
        val changedRequests = listOf(
            request(location = otherCity),
            request(units = WeatherUnits.IMPERIAL),
            request(suppliedKey = "OTHER_LOCAL_GATE_TEST_SENTINEL")
        )
        for (changed in changedRequests) {
            val harness = Harness()
            assertTrue(harness.refresh() is RefreshResult.Updated)
            harness.loaded = changed
            val result = harness.refresh()
            assertTrue(result is RefreshResult.Updated)
            val updated = (result as RefreshResult.Updated).response.report
            assertEquals(changed.location.identity, updated.location.identity)
            assertEquals(changed.units, updated.units)
            assertEquals(2, harness.fetches)
            assertEquals(2, harness.accepts)
        }
    }

    @Test fun cacheFromAnotherCityOrUnitCannotSuppressARefresh() = runBlocking {
        for (mismatch in listOf(report(location = otherCity), report(units = WeatherUnits.IMPERIAL))) {
            val harness = Harness(request(mismatch))
            assertTrue(harness.refresh() is RefreshResult.Updated)
            assertEquals(1, harness.fetches)
        }
    }

    @Test fun missingCredentialsOrLocationSkipEvenWithRememberedRecentData() = runBlocking {
        val harness = Harness()
        assertTrue(harness.refresh() is RefreshResult.Updated)
        harness.loaded = null
        for (intent in RefreshIntent.entries) assertEquals(RefreshResult.Skipped, harness.refresh(intent))
        assertEquals(1, harness.fetches)
        assertEquals(1, harness.accepts)
    }

    @Test fun obsoleteFetchIsNeverAcceptedOrRememberedAndAllowsANewAttempt() = runBlocking {
        val harness = Harness()
        harness.fetch = { null }
        assertEquals(RefreshResult.Skipped, harness.refresh())
        assertEquals(0, harness.accepts)
        harness.fetch = { response(report()) }
        assertTrue(harness.refresh() is RefreshResult.Updated)
        assertEquals(2, harness.fetches)
        assertEquals(1, harness.accepts)
    }

    @Test fun rejectedForcedFetchDoesNotEraseTheLastAcceptedReport() = runBlocking {
        val fresh = report()
        val harness = Harness()
        harness.fetch = { response(fresh) }
        assertTrue(harness.refresh() is RefreshResult.Updated)
        harness.fetch = { null }
        assertEquals(RefreshResult.Skipped, harness.refresh(RefreshIntent.FORCE))
        val result = harness.refresh()
        assertTrue(result is RefreshResult.Recent)
        assertSame(fresh, (result as RefreshResult.Recent).report)
        assertEquals(2, harness.fetches)
        assertEquals(1, harness.accepts)
    }

    @Test fun failedFetchDoesNotMarkDataRecentOrPreventRetrying() = runBlocking {
        val harness = Harness()
        val failure = WeatherFailure("Local test service is unavailable")
        harness.fetch = { throw failure }
        try {
            harness.refresh()
            fail("A transport failure must be propagated")
        } catch (actual: WeatherFailure) {
            assertSame(failure, actual)
        }
        assertEquals(0, harness.accepts)
        harness.fetch = { response(report()) }
        assertTrue(harness.refresh() is RefreshResult.Updated)
        assertEquals(2, harness.fetches)
        assertEquals(1, harness.accepts)
    }

    @Test fun cancellationReleasesTheLockWithoutRememberingAnUnfinishedFetch() = runBlocking {
        val harness = Harness()
        val entered = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        harness.fetch = {
            entered.complete(Unit)
            neverCompletes.await()
            response(report())
        }
        val cancelled = async { harness.refresh() }
        entered.await()
        cancelled.cancelAndJoin()
        assertEquals(0, harness.accepts)
        harness.fetch = { response(report()) }
        assertTrue(harness.refresh() is RefreshResult.Updated)
        assertEquals(2, harness.fetches)
        assertEquals(1, harness.accepts)
    }

    @Test fun futureFetchTimeCannotBlockRefreshAfterThePhoneClockMovesBack() = runBlocking {
        val harness = Harness(request(report(now + 60)))
        assertTrue(harness.refresh(RefreshIntent.MANUAL) is RefreshResult.Updated)
        assertEquals(1, harness.fetches)
        assertEquals(1, harness.accepts)
    }

    @Test fun appAndBackgroundGuardedRequestsUseTheChosenInterval() = runBlocking {
        for (intent in listOf(RefreshIntent.AUTOMATIC, RefreshIntent.MANUAL)) {
            val shorter = Harness(request(report(now - 900), intervalMinutes = 15))
            assertTrue(shorter.refresh(intent) is RefreshResult.Updated)
            val longer = Harness(request(report(now - 3600), intervalMinutes = 120))
            assertTrue(longer.refresh(intent) is RefreshResult.Recent)
            assertEquals(0, longer.fetches)
        }
    }

    @Test fun changingIntervalReevaluatesSuccessfullyRememberedData() = runBlocking {
        val harness = Harness()
        assertTrue(harness.refresh() is RefreshResult.Updated)
        harness.clock += 20 * 60
        harness.loaded = request(intervalMinutes = 60)
        assertTrue(harness.refresh() is RefreshResult.Recent)
        harness.loaded = request(intervalMinutes = 15)
        assertTrue(harness.refresh() is RefreshResult.Updated)
        assertEquals(2, harness.fetches)
    }

    @Test fun aYoungLegacyCacheIsDisplayedButCannotBlockSevenDayUpgradeFetch() = runBlocking {
        val legacy = report(now - 60)
        val harness = Harness(request(legacy, cachedSupportsCurrentFormat = false))
        assertTrue(harness.refresh() is RefreshResult.Updated)
        assertEquals(1, harness.fetches)
        // Successful new-format memory wins even if a cache write failed and disk stayed legacy.
        assertTrue(harness.refresh() is RefreshResult.Recent)
        assertEquals(1, harness.fetches)
    }

    @Test fun aPartialNewFormatForecastRemainsEligibleForTheRecentDataGuard() = runBlocking {
        val partial = report(now - 60).copy(forecastNote = "The daily forecast is currently unavailable.")
        val harness = Harness(request(partial, cachedSupportsCurrentFormat = true))
        assertTrue(harness.refresh() is RefreshResult.Recent)
        assertEquals(0, harness.fetches)
    }

    @Test fun acceptedCoreLetsGuardedRefreshesReturnBeforeDailyFinishes() = runBlocking {
        val gate = WeatherRefreshGate { now }
        val coreReady = CompletableDeferred<Unit>()
        val releaseDaily = CompletableDeferred<Unit>()
        val core = response(report().copy(dailyLoading = true))
        val final = response(core.report.copy(dailyLoading = false))
        val accepted = mutableListOf<WeatherResponse>()
        var fetches = 0
        val first = async {
            gate.refreshStaged(RefreshIntent.AUTOMATIC, { request() }, { _, publish ->
                fetches++
                assertTrue(publish(core))
                assertFalse("A core can only be accepted once", publish(core))
                coreReady.complete(Unit)
                releaseDaily.await()
                final
            }, { accepted += it })
        }
        try {
            withTimeout(2000) { coreReady.await() }
            assertFalse(first.isCompleted)
            for (intent in listOf(RefreshIntent.AUTOMATIC, RefreshIntent.MANUAL)) {
                val recent = withTimeout(2000) {
                    gate.refreshStaged(intent, { request() }, { _, _ ->
                        fail("An accepted core must prevent another guarded network request")
                        null
                    }, { fail("A cache hit must not be accepted again") })
                }
                assertSame(core.report, (recent as RefreshResult.Recent).report)
            }
            assertEquals(listOf(core), accepted)
            assertEquals(1, fetches)
            releaseDaily.complete(Unit)
            val result = withTimeout(2000) { first.await() } as RefreshResult.Updated
            assertSame(final, result.response)
            assertEquals(listOf(core, final), accepted)
            assertEquals(core.report.fetchedAt, final.report.fetchedAt)
        } finally {
            releaseDaily.complete(Unit)
            first.cancelAndJoin()
        }
    }

    @Test fun laterForcedCorePreventsOlderDailyEnrichmentFromOverwritingIt() = runBlocking {
        val gate = WeatherRefreshGate { now + 60 }
        val oldCoreReady = CompletableDeferred<Unit>()
        val releaseOldDaily = CompletableDeferred<Unit>()
        val oldCore = response(report().copy(dailyLoading = true))
        val oldFinal = response(oldCore.report.copy(dailyLoading = false, dailyFetchedAt = now + 120))
        val newerCore = response(report(now + 30).copy(dailyLoading = true))
        val newerFinal = response(newerCore.report.copy(dailyLoading = false, dailyFetchedAt = now + 35))
        val accepted = mutableListOf<WeatherResponse>()
        val old = async {
            gate.refreshStaged(RefreshIntent.FORCE, { request() }, { _, publish ->
                assertTrue(publish(oldCore))
                oldCoreReady.complete(Unit)
                releaseOldDaily.await()
                oldFinal
            }, { accepted += it })
        }
        try {
            withTimeout(2000) { oldCoreReady.await() }
            val newer = withTimeout(2000) {
                gate.refreshStaged(RefreshIntent.FORCE, { request() }, { active, publish ->
                    assertSame(oldCore, active.cachedResponse)
                    assertTrue(publish(newerCore))
                    newerFinal
                }, { accepted += it })
            }
            assertSame(newerFinal, (newer as RefreshResult.Updated).response)
            releaseOldDaily.complete(Unit)
            assertEquals(RefreshResult.Skipped, withTimeout(2000) { old.await() })
            val latest = gate.refresh(RefreshIntent.AUTOMATIC, { request() }, { fail("Latest core is recent"); null }, {})
            assertSame(newerFinal.report, (latest as RefreshResult.Recent).report)
            assertEquals(listOf(oldCore, newerCore, newerFinal), accepted)
        } finally {
            releaseOldDaily.complete(Unit)
            old.cancelAndJoin()
        }
    }

    @Test fun failedForcedRequestWithoutCoreDoesNotInvalidateEarlierPendingEnrichment() = runBlocking {
        val gate = WeatherRefreshGate { now }
        val coreReady = CompletableDeferred<Unit>()
        val releaseDaily = CompletableDeferred<Unit>()
        val core = response(report().copy(dailyLoading = true))
        val final = response(core.report.copy(dailyLoading = false, dailyFetchedAt = now + 10))
        val accepted = mutableListOf<WeatherResponse>()
        val first = async {
            gate.refreshStaged(RefreshIntent.FORCE, { request() }, { _, publish ->
                assertTrue(publish(core))
                coreReady.complete(Unit)
                releaseDaily.await()
                final
            }, { accepted += it })
        }
        try {
            withTimeout(2000) { coreReady.await() }
            try {
                gate.refreshStaged(RefreshIntent.FORCE, { request() }, { _, _ -> throw WeatherFailure("Local test failure") }, {})
                fail("A required transport failure must propagate")
            } catch (_: WeatherFailure) { }
            releaseDaily.complete(Unit)
            assertSame(final, (withTimeout(2000) { first.await() } as RefreshResult.Updated).response)
            assertEquals(listOf(core, final), accepted)
        } finally {
            releaseDaily.complete(Unit)
            first.cancelAndJoin()
        }
    }

    @Test fun cancelingAfterCoreRetainsItsTimestampAndClearsLoadingWithoutAnotherFetch() = runBlocking {
        val gate = WeatherRefreshGate { now + 1 }
        val coreReady = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val core = response(report().copy(dailyLoading = true))
        val accepted = mutableListOf<WeatherResponse>()
        val pending = async {
            gate.refreshStaged(RefreshIntent.FORCE, { request() }, { _, publish ->
                assertTrue(publish(core))
                coreReady.complete(Unit)
                never.await()
                null
            }, { accepted += it })
        }
        withTimeout(2000) { coreReady.await() }
        withTimeout(2000) { pending.cancelAndJoin() }
        assertEquals(2, accepted.size)
        val cleared = accepted.last()
        assertFalse(cleared.report.dailyLoading)
        assertEquals(core.report.fetchedAt, cleared.report.fetchedAt)
        assertEquals(core.currentJson, cleared.currentJson)
        val recent = withTimeout(2000) {
            gate.refresh(RefreshIntent.AUTOMATIC, { request() }, { fail("Cancellation keeps accepted core recent"); null }, {})
        }
        assertSame(cleared.report, (recent as RefreshResult.Recent).report)
    }

    @Test fun oldCancellationCannotClearNewerCoreDailyLoading() = runBlocking {
        val gate = WeatherRefreshGate { now + 60 }
        val oldCoreReady = CompletableDeferred<Unit>()
        val newCoreReady = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val oldCore = response(report().copy(dailyLoading = true))
        val newCore = response(report(now + 30).copy(dailyLoading = true))
        val accepted = mutableListOf<WeatherResponse>()
        suspend fun stage(core: WeatherResponse, ready: CompletableDeferred<Unit>): RefreshResult =
            gate.refreshStaged(RefreshIntent.FORCE, { request() }, { _, publish ->
                assertTrue(publish(core))
                ready.complete(Unit)
                never.await()
                null
            }, { accepted += it })
        val old = async { stage(oldCore, oldCoreReady) }
        var newer: kotlinx.coroutines.Deferred<RefreshResult>? = null
        try {
            withTimeout(2000) { oldCoreReady.await() }
            newer = async { stage(newCore, newCoreReady) }
            withTimeout(2000) { newCoreReady.await() }
            withTimeout(2000) { old.cancelAndJoin() }
            assertEquals(listOf(oldCore, newCore), accepted)
            val recent = gate.refresh(RefreshIntent.AUTOMATIC, { request() }, { fail("Newest core is recent"); null }, {})
            assertSame(newCore.report, (recent as RefreshResult.Recent).report)
            assertTrue(recent.report.dailyLoading)
        } finally {
            old.cancelAndJoin()
            newer?.cancelAndJoin()
        }
    }

    @Test fun canceledCoreFromChangedSettingsIsNotRepublishedDuringCleanup() = runBlocking {
        for (changed in listOf<RefreshRequest?>(null, request(location = otherCity), request(units = WeatherUnits.IMPERIAL),
            request(suppliedKey = "OTHER_LOCAL_GATE_TEST_SENTINEL"), request(dailyProvider = DailyForecastProvider.OCA3))) {
            val gate = WeatherRefreshGate { now }
            var loaded: RefreshRequest? = request()
            val coreReady = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            val core = response(report().copy(dailyLoading = true))
            val accepted = mutableListOf<WeatherResponse>()
            val pending = async {
                gate.refreshStaged(RefreshIntent.FORCE, { loaded }, { _, publish ->
                    assertTrue(publish(core))
                    coreReady.complete(Unit)
                    never.await()
                    null
                }, { accepted += it })
            }
            withTimeout(2000) { coreReady.await() }
            loaded = changed
            withTimeout(2000) { pending.cancelAndJoin() }
            assertEquals(listOf(core), accepted)
            assertFalse(gate.lock.isLocked)
        }
    }

    @Test fun newestMatchingFullResponseIsProvidedToFetchForDailyReuse() = runBlocking {
        val memory = response(report(now - 100).copy(dailyFetchedAt = now - 110))
        val newerDisk = response(report(now - 20).copy(dailyFetchedAt = now - 30))
        val gate = WeatherRefreshGate { now }
        gate.refresh(RefreshIntent.FORCE, { request() }, { memory }, {})
        gate.refreshStaged(RefreshIntent.FORCE, { request(newerDisk.report, cachedResponse = newerDisk) }, { active, _ ->
            assertSame(newerDisk, active.cachedResponse)
            assertSame(newerDisk.report, active.cached)
            newerDisk
        }, {})
        for (changed in listOf(request(location = otherCity), request(units = WeatherUnits.IMPERIAL),
            request(suppliedKey = "OTHER_LOCAL_GATE_TEST_SENTINEL"))) {
            gate.refreshStaged(RefreshIntent.FORCE, { changed }, { active, _ ->
                assertNull("In-memory responses must not cross key/city/unit boundaries", active.cachedResponse)
                null
            }, {})
        }
    }
}
