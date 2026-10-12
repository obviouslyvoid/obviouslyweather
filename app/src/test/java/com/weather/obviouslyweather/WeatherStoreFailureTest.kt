// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.content.SharedPreferences
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherStoreFailureTest {
    @Before fun setupKeyStore() { Security.addProvider(TestKeyStoreProvider()) }
    @After fun removeKeyStore() { Security.removeProvider("AndroidKeyStore"); TestKeyStore.keys.clear() }

    @Test fun failedCommitsKeepThePreviousCityKeyUnitsAndRainHistory() {
        val app = RuntimeEnvironment.getApplication()
        val store = WeatherStore(app)
        val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
        store.saveLocation(city)
        store.saveSettings("old-test-key", WeatherUnits.METRIC, Appearance.LIGHT)
        val before = store.settings()
        val preferences = WeatherStore::class.java.getDeclaredField("preferences").apply { isAccessible = true }
            .get(store) as SharedPreferences
        val blocker = File(app.cacheDir, "prefs-blocker").apply { writeText("Not a directory") }
        for (name in listOf("mFile", "mBackupFile")) {
            preferences.javaClass.getDeclaredField(name).apply { isAccessible = true }
                .set(preferences, File(blocker, name))
        }
        assertThrows(WeatherFailure::class.java) { store.saveLocation(city.copy(name = "London", lat = 51.5, lon = -0.1)) }
        assertEquals(before, WeatherStore(app).settings())
        assertThrows(WeatherFailure::class.java) { store.saveSettings("new-test-key", WeatherUnits.IMPERIAL, Appearance.DARK) }
        assertEquals(before, WeatherStore(app).settings())
        val now = System.currentTimeMillis() / 1000
        val wet = ForecastSlot(now + 3600, 20.0, null, null, 500, "Rain", false, 80.0, 1.0, 0.0)
        val weather = WeatherReport(city, WeatherUnits.METRIC, CurrentWeather(now, 20.0, null, 800, "Clear", false,
            null, null, null, null, null, null, null, null, null), listOf(wet), 0, now, forecastStepSeconds = 3600)
        val quiet = QuietHoursPreferences(enabled = false)
        val first = RainForecastPolicy.evaluate(weather, now, quietHours = quiet)
        assertNotNull(first.notification)
        assertFalse(store.saveRainAlertState(first.state, setOf("${city.identity}|${wet.timestamp}")))
        assertEquals(RainAlertState(), WeatherStore(app).rainAlertState())
        assertTrue(store.sentRainAlerts().isEmpty())
        assertNotNull(RainForecastPolicy.evaluate(weather, now + 60, store.rainAlertState(), quiet,
            legacySent = store.sentRainAlerts()).notification)
        // A failed rollback after notify() throws must still restore the old in-memory history.
        preferences.edit().putString("rain_alert_state", RainAlertStateCodec.encode(first.state))
            .putStringSet("sent_rain_alerts", setOf("notice-never-posted")).commit()
        store.restoreRainAlertState(RainAlertState(), emptySet())
        assertEquals(RainAlertState(), store.rainAlertState())
        assertTrue(store.sentRainAlerts().isEmpty())
        assertEquals(before.key, store.settings().key)
    }
}
