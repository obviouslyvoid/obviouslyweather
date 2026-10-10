// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.service.notification.StatusBarNotification
import androidx.work.WorkManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Uses fixture weather and Android's real notification service on an emulator. */
class RainNotificationChecks : Instrumentation() {
    private val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private val zone = ZoneId.of("UTC")
    private val fixtureKey = "TEST_LOCAL_NOTIFICATION_KEY"
    private lateinit var store: WeatherStore
    private lateinit var manager: NotificationManager
    private var permissionDeniedOnly = false
    private var prepareSettingsOnly = false
    private val now = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate()
        .atTime(12, 0).toEpochSecond(ZoneOffset.UTC)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        permissionDeniedOnly = arguments?.getString("permissionDeniedOnly") == "true"
        prepareSettingsOnly = arguments?.getString("prepareSettingsOnly") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val oldLocale = Locale.getDefault()
        try {
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
                "These fixture checks must run on an emulator."
            }
            Locale.setDefault(Locale.US)
            store = WeatherStore(targetContext)
            manager = targetContext.getSystemService(NotificationManager::class.java)
            WorkManager.getInstance(targetContext).cancelAllWork().result.get()
            RainNotifications.createChannel(targetContext)
            val checks = runBlocking {
                if (prepareSettingsOnly) {
                    reset(ClockStyle.TWELVE_HOUR)
                    store.saveBackground(store.background().copy(quietHours = QuietHoursPreferences()))
                    saveCache(fixture(System.currentTimeMillis() / 1000, rainyHours = emptySet()))
                    1
                } else if (permissionDeniedOnly) {
                    reset()
                    val weather = fixture(now, rainyHours = setOf(1, 2))
                    saveCache(weather)
                    check(!RainNotifications.allowed(targetContext)) { "Revoke POST_NOTIFICATIONS before this pass" }
                    RainNotifications.deliver(targetContext, weather, now, zone)
                    check(active() == null && store.sentRainAlerts().isEmpty()) {
                        "Missing notification permission posted or recorded a warning"
                    }
                    1
                } else {
                    shell("pm grant ${targetContext.packageName} android.permission.POST_NOTIFICATIONS")
                    check(RainNotifications.allowed(targetContext)) { "Notification permission or channel is blocked" }
                    checks()
                }
            }
            result.putString("stream", "PASS: $checks Android rain-notification checks; fixture data only, no API calls.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            Locale.setDefault(oldLocale)
            if (::manager.isInitialized) RainNotifications.cancel(targetContext)
            if (::store.isInitialized && !prepareSettingsOnly) store.saveBackground(BackgroundPreferences(enabled = false))
        }
    }

    private suspend fun checks(): Int {
        var count = 0
        reset(ClockStyle.TWELVE_HOUR)
        val weather = fixture(now, rainyHours = setOf(1, 2))
        saveCache(weather)
        RainNotifications.deliver(targetContext, weather, now, zone)
        val first = posted()
        check(first.notification.extras.getString(Notification.EXTRA_TITLE) == "Kyiv | Rain")
        val firstText = first.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        check("01:00 PM" in firstText) { "Initial warning did not use the app's 12-hour clock: $firstText" }
        check("03:00 PM" in firstText) { "A complete rain spell did not include its estimated end: $firstText" }
        count++

        val savedState = store.rainAlertState()
        check(savedState == WeatherStore(targetContext).rainAlertState()) { "Alert state did not persist" }
        val savedHistory = store.sentRainAlerts()
        check(savedHistory.isNotEmpty() && savedHistory == WeatherStore(targetContext).sentRainAlerts())
        count++

        coroutineScope {
            List(4) { async { RainNotifications.deliver(targetContext, weather, now + 60, zone) } }.awaitAll()
        }
        val repeated = posted()
        check(manager.activeNotifications.count { it.notification.channelId == RainNotifications.CHANNEL_ID } == 1)
        check(repeated.postTime == first.postTime) { "Identical checks reposted the same warning" }
        check(savedState.events.map { it.copy(lastSeenAt = 0) } ==
            store.rainAlertState().events.map { it.copy(lastSeenAt = 0) }) {
            "A duplicate changed communicated forecast state"
        }
        count++

        RainNotifications.cancel(targetContext)
        store = WeatherStore(targetContext)
        RainNotifications.deliver(targetContext, weather, now + 120, zone)
        check(active() == null) { "A new store instance repeated an already communicated event" }
        count++

        reset(ClockStyle.TWENTY_FOUR_HOUR)
        saveCache(weather)
        RainNotifications.deliver(targetContext, weather, now, zone)
        val clockText = posted().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        check("13:00" in clockText && "15:00" in clockText && "PM" !in clockText && "AM" !in clockText) {
            "Notification did not use the selected 24-hour clock: $clockText"
        }
        count++

        reset(ClockStyle.TWELVE_HOUR)
        val incomplete = weather.copy(slots = weather.slots.take(3))
        saveCache(incomplete)
        RainNotifications.deliver(targetContext, incomplete, now, zone)
        check("end around" !in posted().notification.extras
            .getCharSequence(Notification.EXTRA_TEXT).toString()) { "Missing dry coverage invented an ending time" }
        val updated = weather.copy(fetchedAt = now + 3600,
            current = weather.current.copy(timestamp = now + 3600, condition = 500, description = "Rain"))
        saveCache(updated)
        RainNotifications.deliver(targetContext, updated, now + 3600, zone)
        val endingText = posted("Rain may end around 03:00 PM.").notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        check(endingText == "Rain may end around 03:00 PM.") { "Ending update was not posted: $endingText" }
        val endingTime = posted().postTime
        RainNotifications.deliver(targetContext, updated, now + 3660, zone)
        check(posted().postTime == endingTime) { "Ending update repeated" }
        count++

        reset()
        val original = fixture(now, rainyHours = setOf(2, 3))
        saveCache(original)
        RainNotifications.deliver(targetContext, original, now, zone)
        posted()
        val cancelled = fixture(now, rainyHours = emptySet()).copy(fetchedAt = now + 3600)
        saveCache(cancelled)
        RainNotifications.deliver(targetContext, cancelled, now + 3600, zone)
        val cancelledText = posted("Rain is no longer expected around 14:00.").notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        check(cancelledText == "Rain is no longer expected around 14:00.") {
            "Forecast cancellation was not posted: $cancelledText"
        }
        count++

        reset(ClockStyle.TWELVE_HOUR)
        saveCache(original)
        RainNotifications.deliver(targetContext, original, now, zone)
        posted()
        val moved = fixture(now, rainyHours = setOf(4, 5)).copy(fetchedAt = now + 3600)
        saveCache(moved)
        RainNotifications.deliver(targetContext, moved, now + 3600, zone)
        val movedText = posted("Rain expected around 04:00 PM (previously 02:00 PM).")
            .notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        check("04:00 PM" in movedText && "02:00 PM" in movedText)
        count++

        reset()
        val quiet = QuietHoursPreferences(enabled = true, startMinutes = 11 * 60, endMinutes = 13 * 60)
        store.saveBackground(store.background().copy(quietHours = quiet))
        check(WeatherStore(targetContext).background().quietHours == quiet) { "Quiet-hour preferences did not persist" }
        saveCache(weather)
        RainNotifications.deliver(targetContext, weather, now, zone)
        check(active() == null) { "A warning was posted during quiet hours" }
        store.saveBackground(store.background().copy(quietHours = quiet.copy(enabled = false)))
        RainNotifications.deliver(targetContext, weather, now, zone)
        posted()
        count++

        reset()
        store.saveBackground(store.background().copy(rainAlerts = false))
        saveCache(weather)
        RainNotifications.deliver(targetContext, weather, now, zone)
        check(active() == null) { "Disabled rain alerts still posted" }
        store.saveBackground(store.background().copy(enabled = false, rainAlerts = true))
        RainNotifications.deliver(targetContext, weather, now, zone)
        check(active() == null) { "Disabled background refresh still posted" }
        count++

        reset()
        saveCache(weather)
        val otherCity = weather.copy(location = city.copy(name = "Lviv", lon = 24.03))
        RainNotifications.deliver(targetContext, otherCity, now, zone)
        check(active() == null) { "A report for an obsolete city posted" }
        store.saveLocation(otherCity.location)
        saveCache(otherCity)
        RainNotifications.deliver(targetContext, otherCity, now, zone)
        check(posted().notification.extras.getString(Notification.EXTRA_TITLE) == "Lviv | Rain")
        count++

        reset()
        val newer = weather.copy(fetchedAt = now + 60)
        saveCache(newer)
        RainNotifications.deliver(targetContext, weather, now + 60, zone)
        check(active() == null) { "A superseded report posted after a newer cached report" }
        RainNotifications.deliver(targetContext, newer, now + 60, zone)
        posted()
        count++

        reset()
        val old = weather.copy(fetchedAt = now - 3601)
        saveCache(old)
        RainNotifications.deliver(targetContext, old, now, zone)
        check(active() == null) { "An old report generated a notification" }
        count++

        reset()
        val future = weather.copy(fetchedAt = now + 1)
        saveCache(future)
        RainNotifications.deliver(targetContext, future, now, zone)
        check(active() == null) { "A future-dated report generated a notification" }
        count++

        reset()
        val legacy = weather.slots.filter { it.precipitation == 70.0 }
            .mapTo(mutableSetOf()) { "${city.identity}|${it.timestamp}" }
        check(store.saveSentRainAlerts(legacy))
        saveCache(weather)
        RainNotifications.deliver(targetContext, weather, now, zone)
        check(active() == null) { "Legacy sent history repeated the initial warning" }
        check(store.rainAlertState().events.isNotEmpty()) { "Legacy sent history was not carried into forecast tracking" }
        count++

        // Keep this last: an app can lower channel importance but cannot raise it again.
        reset()
        saveCache(weather)
        manager.createNotificationChannel(NotificationChannel(RainNotifications.CHANNEL_ID, "Rain alerts",
            NotificationManager.IMPORTANCE_NONE))
        check(manager.getNotificationChannel(RainNotifications.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            "Blocked-channel fixture needs fresh app data without user-locked channel settings"
        }
        check(!RainNotifications.allowed(targetContext)) { "The blocked rain channel was still allowed" }
        RainNotifications.deliver(targetContext, weather, now, zone)
        check(active() == null) { "A blocked rain channel did not suppress delivery" }
        check(store.sentRainAlerts().isEmpty() && store.rainAlertState().events.isEmpty()) {
            "A blocked warning was marked as sent"
        }
        count++
        return count
    }

    private fun reset(clock: ClockStyle = ClockStyle.TWENTY_FOUR_HOUR) {
        RainNotifications.cancel(targetContext)
        var attempts = 0
        while (active() != null && attempts++ < 40) Thread.sleep(25)
        check(active() == null) { "The previous fixture notification did not clear" }
        check(targetContext.getSharedPreferences("weather_settings", Context.MODE_PRIVATE).edit().clear().commit())
        store = WeatherStore(targetContext)
        store.saveSettings(fixtureKey, WeatherUnits.METRIC, Appearance.LIGHT)
        store.saveLocation(city)
        store.saveDisplay(DisplayPreferences(clock = clock, refreshOnOpen = false))
        store.saveBackground(BackgroundPreferences(enabled = true, rainAlerts = true,
            quietHours = QuietHoursPreferences(enabled = false)))
        check(store.saveRainAlertState(RainAlertState(), emptySet()))
    }

    private fun fixture(at: Long, rainyHours: Set<Int>): WeatherReport {
        val current = CurrentWeather(at, 16.0, null, 800, "Clear sky", false,
            null, null, null, null, null, null, null, null, null)
        val hours = (0..6).map { hour ->
            val rainy = hour in rainyHours
            ForecastSlot(at + hour * 3600L, 16.0, null, null, if (rainy) 500 else 800,
                if (rainy) "Rain" else "Clear sky", false, if (rainy) 70.0 else 0.0,
                if (rainy) 1.0 else 0.0, 0.0)
        }
        return WeatherReport(city, WeatherUnits.METRIC, current, hours, 0, at,
            forecastStepSeconds = 3600)
    }

    private fun saveCache(report: WeatherReport) {
        fun condition(code: Int?) = JSONArray().put(JSONObject().put("id", code)
            .put("icon", "10d").put("description", if (code == 500) "Rain" else "Clear sky"))
        val current = JSONObject().put("dt", report.current.timestamp).put("timezone", report.offset)
            .put("main", JSONObject().put("temp", report.current.temperature))
            .put("weather", condition(report.current.condition)).toString()
        val hourly = JSONObject().put("data", JSONArray(report.slots.map { slot ->
            JSONObject().put("dt", slot.timestamp).put("temp", slot.temperature)
                .put("pop", slot.precipitation?.div(100.0)).put("weather", condition(slot.condition))
                .put("rain", JSONObject().put("1h", slot.rainMm))
        })).toString()
        store.saveCache(WeatherResponse(report, current, hourly, null), fixtureKey)
    }

    private fun active(): StatusBarNotification? = manager.activeNotifications
        .singleOrNull { it.notification.channelId == RainNotifications.CHANNEL_ID }

    private fun posted(expectedText: String? = null): StatusBarNotification {
        repeat(40) {
            active()?.let {
                if (expectedText == null || it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() == expectedText)
                    return it
            }
            Thread.sleep(25)
        }
        error("The expected rain notification was not posted")
    }

    private fun shell(command: String) {
        uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
    }
}
