// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real local HTTP sockets exercise deadlines and cancellation without paid API requests. */
class OpenWeatherTransportTest {
    private val key = "LOCAL_TRANSPORT_TEST_KEY_SENTINEL"
    private val dailyPath = "/data/4.0/onecall/timeline/1day"
    private val currentPath = "/data/2.5/weather"

    private fun localServer(block: suspend CoroutineScope.(MockWebServer) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private suspend fun failure(block: suspend () -> String): WeatherFailure {
        try {
            block()
        } catch (error: WeatherFailure) {
            return error
        }
        throw AssertionError("Expected a safe weather failure")
    }

    private fun elapsedMillis(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun assertSafe(error: WeatherFailure) {
        // Coroutine stack-trace recovery can attach another copy of the safe exception.
        // Every cause must still be sanitized; a raw IOException is never acceptable.
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && visited.add(current)) {
            assertTrue("Raw connection exceptions must not expose request details", current is WeatherFailure)
            val message = current.message.orEmpty()
            assertTrue("A readable error must be returned", message.isNotBlank())
            assertFalse(message.contains(key))
            assertFalse(message.contains("appid="))
            assertFalse(message.contains("127.0.0.1"))
            assertFalse(message.contains("localhost"))
            assertFalse(message.contains("/data/"))
            current = current.cause
        }
    }

    @Test fun unresponsiveDailyRequestStopsAtItsWholeCallDeadline() = localServer { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val transport = OpenWeatherTransport(origin = server.url("/"), dailyTimeoutMillis = 250, otherTimeoutMillis = 3_000)
        val started = System.nanoTime()
        val error = withTimeout(2_000) { failure { transport.request(dailyPath, emptyMap(), key) } }
        assertTrue("A nonresponding daily API must stop within two seconds", elapsedMillis(started) < 2_000)
        assertSafe(error)
        assertEquals(1, server.requestCount)
    }

    @Test fun oca3DailyUsesTheDailyDeadlineAndSafeForecastErrors() = localServer { server ->
        val oca3Path = "/data/3.0/onecall"
        val transport = OpenWeatherTransport(origin = server.url("/"), dailyTimeoutMillis = 250, otherTimeoutMillis = 3_000)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        val timeout = withTimeout(2_000) { failure { transport.request(oca3Path, emptyMap(), key) } }
        assertTrue(elapsedMillis(started) < 2_000)
        assertSafe(timeout)
        server.enqueue(MockResponse().setResponseCode(401).setBody("$key appid=$key"))
        val denied = withTimeout(2_000) { failure { transport.request(oca3Path, emptyMap(), key) } }
        assertSafe(denied)
        assertEquals(serviceError(401, oneCall = true), denied.message)
        assertEquals(2, server.requestCount)
    }

    @Test fun slowlyTricklingBodyCannotRestartTheDailyDeadline() = localServer { server ->
        // Individual reads succeed frequently; a read timeout alone would take many seconds.
        server.enqueue(MockResponse().setBody("x".repeat(200)).throttleBody(1, 70, TimeUnit.MILLISECONDS))
        val transport = OpenWeatherTransport(origin = server.url("/"), dailyTimeoutMillis = 250, otherTimeoutMillis = 3_000)
        val started = System.nanoTime()
        val error = withTimeout(2_000) { failure { transport.request(dailyPath, emptyMap(), key) } }
        assertTrue("The deadline must cover the full response body", elapsedMillis(started) < 2_000)
        assertSafe(error)
        assertEquals(1, server.requestCount)
    }

    @Test fun completeJsonBodyAndEncodedParametersAreDeliveredUnchanged() = localServer { server ->
        val body = "{\"name\":\"Київ\",\"data\":[{\"temp\":17.5}]}"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(body))
        val transport = OpenWeatherTransport(origin = server.url("/"), dailyTimeoutMillis = 1_500)
        val encodedKey = "$key a+b?&=value"
        assertEquals(body, withTimeout(2_000) {
            transport.request(dailyPath, mapOf("lat" to "50.45", "lon" to "30.52", "units" to "metric", "q" to "Київ & city"), encodedKey)
        })
        val request = server.takeRequest(1, TimeUnit.SECONDS) ?: throw AssertionError("Expected a local request")
        val url = request.requestUrl ?: throw AssertionError("Expected a local URL")
        assertEquals(dailyPath, url.encodedPath)
        assertEquals("50.45", url.queryParameter("lat"))
        assertEquals("Київ & city", url.queryParameter("q"))
        assertEquals(encodedKey, url.queryParameter("appid"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertTrue(request.getHeader("User-Agent").orEmpty().startsWith("obviouslyweather/"))
    }

    @Test fun currentConditionsKeepTheirSeparateDeadline() = localServer { server ->
        server.enqueue(MockResponse().setHeadersDelay(400, TimeUnit.MILLISECONDS).setBody("{\"temp\":18}"))
        val transport = OpenWeatherTransport(origin = server.url("/"), dailyTimeoutMillis = 150, otherTimeoutMillis = 1_500)
        assertEquals("{\"temp\":18}", withTimeout(2_000) { transport.request(currentPath, emptyMap(), key) })
        assertEquals(1, server.requestCount)
    }

    @Test fun dailyReadBudgetIsNotCutShortByTheCoreClientReadTimeout() = localServer { server ->
        server.enqueue(MockResponse().setHeadersDelay(250, TimeUnit.MILLISECONDS).setBody("{\"data\":[]}"))
        val client = OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).build()
        try {
            val transport = OpenWeatherTransport(client = client, origin = server.url("/"),
                dailyTimeoutMillis = 1_000, otherTimeoutMillis = 50)
            assertEquals("{\"data\":[]}", withTimeout(2_000) {
                transport.request(dailyPath, emptyMap(), key)
            })
            assertEquals(1, server.requestCount)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun defaultDailyCallHasA30SecondWholeCallBound() = localServer { server ->
        server.enqueue(MockResponse().setBody("{\"data\":[]}"))
        val callReference = AtomicReference<Call>()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).eventListener(object : EventListener() {
            override fun callStart(call: Call) { callReference.set(call) }
        }).build()
        try {
            val transport = OpenWeatherTransport(client = client, origin = server.url("/"))
            transport.request(dailyPath, emptyMap(), key)
            assertEquals(30_000L, TimeUnit.NANOSECONDS.toMillis(callReference.get().timeout().timeoutNanos()))
            assertEquals(1, server.requestCount)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun cancellingTheCoroutineCancelsTheSocketAndRejectsLaterSuccess() = localServer { server ->
        server.enqueue(MockResponse().setBody("{\"data\":[]}").setBodyDelay(600, TimeUnit.MILLISECONDS))
        val bodyStarted = CompletableDeferred<Unit>()
        val actualCall = AtomicReference<Call>()
        val succeeded = AtomicBoolean(false)
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).eventListener(object : EventListener() {
            override fun callStart(call: Call) { actualCall.set(call) }
            override fun responseBodyStart(call: Call) { bodyStarted.complete(Unit) }
        }).build()
        try {
            val transport = OpenWeatherTransport(client = client, origin = server.url("/"), dailyTimeoutMillis = 2_000)
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                transport.request(dailyPath, emptyMap(), key).also { succeeded.set(true) }
            }
            withTimeout(1_500) { bodyStarted.await() }
            val started = System.nanoTime()
            withTimeout(500) { pending.cancelAndJoin() }
            assertTrue("Cancellation must return promptly", elapsedMillis(started) < 500)
            assertTrue(pending.isCancelled)
            assertTrue("The underlying OkHttp call must be cancelled", actualCall.get().isCanceled())
            delay(650)
            assertFalse("An obsolete request must never publish late success", succeeded.get())
            assertEquals(1, server.requestCount)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun serviceErrorsNeverCopyProviderBodiesOrRequestKeysIntoMessages() = localServer { server ->
        val transport = OpenWeatherTransport(origin = server.url("/"), dailyTimeoutMillis = 1_500)
        for (status in listOf(401, 403, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"message\":\"$key appid=$key /data/private\"}"))
            val error = withTimeout(2_000) { failure { transport.request(dailyPath, emptyMap(), key) } }
            assertEquals(serviceError(status, oneCall = true), error.message)
            assertSafe(error)
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun redirectsAreNotFollowedAndDoNotSendTheKeyToAnotherEndpoint() = localServer { server ->
        val target = MockWebServer()
        target.start()
        try {
            target.enqueue(MockResponse().setBody("{\"unexpected\":true}"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/redirect-target")))
            val transport = OpenWeatherTransport(origin = server.url("/"), dailyTimeoutMillis = 1_500)
            val error = withTimeout(2_000) { failure { transport.request(dailyPath, emptyMap(), key) } }
            assertEquals(serviceError(302, oneCall = true), error.message)
            assertSafe(error)
            assertEquals(1, server.requestCount)
            assertEquals("The shared production client must not follow redirects", 0, target.requestCount)
        } finally {
            target.shutdown()
        }
    }

    @Test fun brokenConnectionsReturnSafeErrorsWithoutRetryingPaidRequests() = localServer { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        // If the client retries, this deliberately successful next response exposes it.
        server.enqueue(MockResponse().setBody("{\"unexpectedRetry\":true}"))
        val transport = OpenWeatherTransport(origin = server.url("/"), dailyTimeoutMillis = 1_500)
        val error = withTimeout(2_000) { failure { transport.request(dailyPath, emptyMap(), key) } }
        assertSafe(error)
        assertEquals("The shared production client must make one request only", 1, server.requestCount)
    }
}
