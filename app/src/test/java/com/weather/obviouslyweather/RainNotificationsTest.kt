// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.Security
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RainNotificationsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val store get() = WeatherStore(app)
    private val three = Instant.parse("2026-10-13T12:00:00Z").epochSecond
    private val hour = 3600L
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private fun wet(at: Long) = ForecastSlot(at, 12.0, null, null, 500, "Rain", false, 80.0, 0.5, 0.0)
    private fun dry(at: Long) = wet(at).copy(condition = 804, precipitation = 0.0, rainMm = 0.0)
    private fun report(at: Long, vararg slots: ForecastSlot, raining: Boolean = false) = WeatherReport(
        city, WeatherUnits.METRIC, CurrentWeather(at, 12.0, null, if (raining) 500 else 804,
            if (raining) "Rain" else "Overcast clouds", false, null, null, null, null, null, null, null, null, null),
        slots.toList(), 3 * 3600, at, forecastStepSeconds = 3600, zoneId = "Europe/Kyiv")
    private fun deliver(weather: WeatherReport) = runBlocking {
        RainNotifications.deliver(app, weather, weather.fetchedAt, ZoneId.of("Europe/Kyiv"))
    }
    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Before fun setup() {
        Security.addProvider(TestKeyStoreProvider())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store.saveSettings("test-key", WeatherUnits.METRIC, Appearance.SYSTEM)
        store.saveLocation(city)
        store.saveDisplay(DisplayPreferences(clock = ClockStyle.TWENTY_FOUR_HOUR))
        store.saveBackground(BackgroundPreferences(rainAlerts = true, quietHours = QuietHoursPreferences(enabled = false)))
    }
    @After fun teardown() {
        Security.removeProvider("AndroidKeyStore")
        TestKeyStore.keys.clear()
    }

    @Test fun theReportedPastStartAndDryEndingCasesDoNotPostNotifications() {
        val original = report(three - hour, wet(three + hour))
        val state = RainForecastPolicy.evaluate(original, original.fetchedAt,
            quietHours = QuietHoursPreferences(enabled = false)).state
        assertTrue(store.saveRainAlertState(state, emptySet()))
        deliver(report(three + 20 * 60, wet(three), dry(three + hour), dry(three + 2 * hour)))
        assertTrue(posted().isEmpty())

        val second = report(three - hour, wet(three), wet(three + hour))
        assertTrue(store.saveRainAlertState(RainForecastPolicy.evaluate(second, second.fetchedAt,
            quietHours = QuietHoursPreferences(enabled = false)).state, emptySet()))
        deliver(report(three + hour + 42 * 60, wet(three + hour), dry(three + 2 * hour), dry(three + 3 * hour)))
        assertTrue(posted().isEmpty())
    }

    @Test fun freshDryConditionsRemoveAPreviousAlertOnceItsRainStartHasPassed() {
        deliver(report(three - hour, wet(three), wet(three + hour), dry(three + 2 * hour), dry(three + 3 * hour)))
        assertEquals(1, posted().size)
        deliver(report(three + 20 * 60, wet(three), dry(three + hour), dry(three + 2 * hour)))
        assertTrue(posted().isEmpty())
        assertEquals(1, store.rainAlertState().events.size)
    }

    @Test fun dryConditionsDoNotRemoveAWarningForRainStillInTheFuture() {
        val report = report(three - hour, wet(three), wet(three + hour), dry(three + 2 * hour), dry(three + 3 * hour))
        deliver(report)
        val text = posted().single().extras.getCharSequence(Notification.EXTRA_TEXT)
        deliver(report.copy(fetchedAt = three - 30 * 60, current = report.current.copy(timestamp = three - 30 * 60)))
        assertEquals(text, posted().single().extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun alertsFromThePreviousAppVersionCanAlsoBeClearedUsingTheirSavedEvent() {
        deliver(report(three - hour, wet(three), wet(three + hour), dry(three + 2 * hour), dry(three + 3 * hour)))
        posted().single().extras.remove("rain_event_start")
        deliver(report(three + 20 * 60, wet(three), dry(three + hour), dry(three + 2 * hour)))
        assertTrue(posted().isEmpty())
    }

    @Test fun staleOrUnknownObservationsCannotClearAPreviouslyPostedAlert() {
        deliver(report(three - hour, wet(three), wet(three + hour), dry(three + 2 * hour), dry(three + 3 * hour)))
        val fresh = report(three + 20 * 60, wet(three), wet(three + hour), dry(three + 2 * hour), dry(three + 3 * hour))
        deliver(fresh.copy(current = fresh.current.copy(timestamp = three - 2 * hour)))
        assertEquals(1, posted().size)
        deliver(fresh.copy(current = fresh.current.copy(condition = null)))
        assertEquals(1, posted().size)
    }

    @Test fun realRainPostsAnEndingUsingTheSelectedClockAndDoesNotRepeatIt() {
        val weather = report(three + 20 * 60, wet(three), dry(three + hour), dry(three + 2 * hour), raining = true)
        deliver(weather)
        val notice = posted().single()
        assertEquals("Kyiv | Rain", notice.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Rain may end around 16:00.", notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val notified = store.rainAlertState().events.single().lastNotifiedAt
        deliver(weather.copy(fetchedAt = weather.fetchedAt + 60))
        assertEquals(notified, store.rainAlertState().events.single().lastNotifiedAt)
        store.saveDisplay(store.display().copy(clock = ClockStyle.TWELVE_HOUR))
        RainNotifications.cancel(app)
        assertTrue(store.saveRainAlertState(RainAlertState(), emptySet()))
        deliver(weather)
        assertEquals("Rain may end around 04:00 PM.", posted().single().extras
            .getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun quietHoursAndDeniedPermissionsStillSuppressAlerts() {
        val weather = report(three - hour, wet(three))
        store.saveBackground(store.background().copy(quietHours = QuietHoursPreferences(startMinutes = 0, endMinutes = 0)))
        deliver(weather)
        assertTrue(posted().isEmpty())
        assertTrue(store.rainAlertState().events.isEmpty())
        store.saveBackground(store.background().copy(quietHours = QuietHoursPreferences(enabled = false)))
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        deliver(weather)
        assertTrue(posted().isEmpty())
        assertTrue(store.rainAlertState().events.isEmpty())
    }
}
