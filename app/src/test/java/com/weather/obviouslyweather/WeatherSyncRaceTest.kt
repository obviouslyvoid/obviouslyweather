// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherSyncRaceTest {
    @Before fun setupKeyStore() { Security.addProvider(TestKeyStoreProvider()) }
    @After fun removeKeyStore() { Security.removeProvider("AndroidKeyStore"); TestKeyStore.keys.clear() }

    private fun await(check: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the refresh interleaving")
    }

    @Test fun olderForegroundCompletionCannotReplaceANewerWorkerUpdateFromTheSameSecond() {
        val app = RuntimeEnvironment.getApplication()
        val store = WeatherStore(app)
        val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
        store.saveSettings("test-key", WeatherUnits.METRIC, Appearance.SYSTEM)
        store.saveLocation(city)
        store.saveDisplay(DisplayPreferences(refreshOnOpen = false))
        store.saveBackground(BackgroundPreferences(rainAlerts = false))
        WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        val now = System.currentTimeMillis() / 1000
        fun repository(temperature: Int) = WeatherRepository({ path, _, _ ->
            if (path == "/data/2.5/weather")
                """{"dt":$now,"main":{"temp":$temperature},"weather":[{"id":800}],"timezone":0}"""
            else """{"timezone":"UTC","data":[]}"""
        }, { now })
        val afterGate = CompletableDeferred<Long?>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<RefreshResult>()
        val model = WeatherViewModel(app) { context, intent, background, coreReady ->
            WeatherSync.refresh(context, intent, background, coreReady, repository(20),
                deliverRain = { ctx, report, sequence ->
                    afterGate.complete(sequence)
                    release.await()
                    RainNotifications.deliver(ctx, report, expectedSequence = sequence)
                }).also { returned.complete(it) }
        }
        val models = ViewModelStore().apply { put("weather", model) }
        val workers = CoroutineScope(Dispatchers.IO)
        try {
            await { !model.state.value.initializing }
            model.requestManualRefresh()
            await { afterGate.isCompleted && model.state.value.report?.current?.temperature == 20.0 }
            val worker = workers.async {
                WeatherSync.refresh(app, RefreshIntent.FORCE, background = true, repository = repository(25))
            }
            await { worker.isCompleted && model.state.value.report?.current?.temperature == 25.0 }
            val newer = runBlocking { worker.await() } as RefreshResult.Updated
            assertEquals(now, newer.response.report.fetchedAt)
            release.complete(Unit)
            await { returned.isCompleted && !model.state.value.loading }
            val older = runBlocking { returned.await() } as RefreshResult.Updated
            assertTrue(newer.sequence!! > older.sequence!!)
            assertEquals(25.0, model.state.value.report!!.current.temperature, 0.0)
            assertEquals(25.0, store.cached(city, WeatherUnits.METRIC)!!.current.temperature, 0.0)
        } finally {
            release.complete(Unit)
            models.clear()
            workers.cancel()
            WorkManager.getInstance(app).cancelAllWork()
        }
    }
}
