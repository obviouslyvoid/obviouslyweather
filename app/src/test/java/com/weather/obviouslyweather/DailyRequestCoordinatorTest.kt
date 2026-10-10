// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DailyRequestCoordinatorTest {
    private val path = "/data/4.0/onecall/timeline/1day"
    private val parameters = mapOf("lat" to "50.45", "lon" to "30.52", "units" to "metric")
    private val key = "LOCAL_COORDINATOR_TEST_SENTINEL"

    @Test fun concurrentSameContextUsesOnePendingCall() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        val calls = AtomicInteger()
        val coordinator = DailyRequestCoordinator { _, _, _ -> calls.incrementAndGet(); entered.complete(Unit); release.await() }
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(path, parameters, key) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(path, parameters.toSortedMap(), key) }
            withTimeout(2000) { entered.await() }
            assertEquals(1, calls.get())
            release.complete("daily")
            assertEquals("daily", withTimeout(2000) { first.await() })
            assertEquals("daily", withTimeout(2000) { second.await() })
        } finally { coordinator.close() }
    }

    @Test fun oca3CallsShareOnlyWithOtherOca3Calls() = runBlocking {
        val bothEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val oca3Path = "/data/3.0/onecall"
        val coordinator = DailyRequestCoordinator { requestedPath, _, _ ->
            if (calls.incrementAndGet() == 2) bothEntered.complete(Unit)
            release.await()
            requestedPath
        }
        try {
            val oca4 = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(path, parameters, key) }
            val oca3 = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(oca3Path, parameters, key) }
            val joined = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(oca3Path, parameters, key) }
            withTimeout(2000) { bothEntered.await() }
            release.complete(Unit)
            assertEquals(path, withTimeout(2000) { oca4.await() })
            assertEquals(oca3Path, withTimeout(2000) { oca3.await() })
            assertEquals(oca3Path, withTimeout(2000) { joined.await() })
            assertEquals(2, calls.get())
        } finally { coordinator.close() }
    }

    @Test fun cancelingOneSubscriberKeepsTheOtherRequestAlive() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        val canceled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator = DailyRequestCoordinator { _, _, _ ->
            calls.incrementAndGet(); entered.complete(Unit)
            try { release.await() } finally { canceled.complete(Unit) }
        }
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(path, parameters, key) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(path, parameters, key) }
            withTimeout(2000) { entered.await(); first.cancelAndJoin() }
            assertFalse(canceled.isCompleted)
            assertFalse(second.isCompleted)
            release.complete("daily")
            assertEquals("daily", withTimeout(2000) { second.await() })
            assertEquals(1, calls.get())
        } finally { coordinator.close() }
    }

    @Test fun lastSubscriberCancelsNativeCallAndNextRequestStartsAgain() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator = DailyRequestCoordinator { _, _, _ ->
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { canceled.complete(Unit) }
            } else "new daily"
        }
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(path, parameters, key) }
            withTimeout(2000) { entered.await(); first.cancelAndJoin(); canceled.await() }
            assertEquals("new daily", withTimeout(2000) { coordinator.request(path, parameters, key) })
            assertEquals(2, calls.get())
        } finally { coordinator.close() }
    }

    @Test fun coordinatesUnitsAndKeysNeverShareCalls() = runBlocking {
        val allEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        val calls = AtomicInteger()
        val coordinator = DailyRequestCoordinator { _, _, _ ->
            if (calls.incrementAndGet() == 4) allEntered.complete(Unit)
            release.await()
        }
        try {
            val requests = listOf(parameters to key, (parameters + ("lat" to "51.0")) to key,
                (parameters + ("units" to "imperial")) to key, parameters to "OTHER_LOCAL_TEST_SENTINEL")
                .map { (params, credential) -> async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(path, params, credential) } }
            withTimeout(2000) { allEntered.await() }
            release.complete("daily")
            requests.forEach { assertEquals("daily", withTimeout(2000) { it.await() }) }
            assertEquals(4, calls.get())
        } finally { coordinator.close() }
    }

    @Test fun successfulResultsAreNotCachedOrReusedForAnotherRefresh() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = DailyRequestCoordinator { _, _, _ -> "daily ${calls.incrementAndGet()}" }
        try {
            assertEquals("daily 1", coordinator.request(path, parameters, key))
            assertEquals("daily 2", coordinator.request(path, parameters, key))
        } finally { coordinator.close() }
    }

    @Test fun failedPendingCallReleasesItsSubscribers() = runBlocking {
        supervisorScope {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val coordinator = DailyRequestCoordinator { _, _, _ ->
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit); release.await(); throw WeatherFailure("Daily unavailable")
                } else "recovered"
            }
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(path, parameters, key) }
                val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(path, parameters, key) }
                withTimeout(2000) { entered.await() }; release.complete(Unit)
                for (request in listOf(first, second)) {
                    val failure = runCatching { withTimeout(2000) { request.await() } }.exceptionOrNull()
                    assertTrue(failure is WeatherFailure)
                    assertFalse(failure!!.message.orEmpty().contains(key))
                }
                assertEquals("recovered", coordinator.request(path, parameters, key))
                assertEquals(2, calls.get())
            } finally { coordinator.close() }
        }
    }

    @Test fun currentAndHourlyRequestsRemainIndependent() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = DailyRequestCoordinator { _, _, _ -> "response ${calls.incrementAndGet()}" }
        try {
            for (endpoint in listOf("/data/2.5/weather", "/data/4.0/onecall/timeline/1h")) {
                assertNotEquals(coordinator.request(endpoint, parameters, key), coordinator.request(endpoint, parameters, key))
            }
            assertEquals(4, calls.get())
        } finally { coordinator.close() }
    }
}
