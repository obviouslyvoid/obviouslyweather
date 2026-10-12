// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

object RainNotifications {
    const val OPEN_WEATHER_ACTION = "com.weather.obviouslyweather.OPEN_WEATHER"
    const val CHANNEL_ID = "rain_alerts"
    private const val NOTIFICATION_ID = 1001
    private const val MAX_FORECAST_AGE_SECONDS = 3600L
    private val deliveryMonitor = Any()

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Rain alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Upcoming rain, estimated ending times and important forecast changes."
                setShowBadge(false)
            })
    }

    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun cancel(context: Context) = synchronized(deliveryMonitor) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    internal suspend fun deliver(context: Context, report: WeatherReport,
        now: Long? = null, phoneZone: ZoneId? = null,
        expectedSequence: Long? = null) = withContext(Dispatchers.IO) {
        WeatherSync.lock.withLock {
            val checkedAt = now ?: (System.currentTimeMillis() / 1000)
            val quietZone = phoneZone ?: ZoneId.systemDefault()
            if (expectedSequence != null && expectedSequence != WeatherSync.latestUpdateSequence) return@withLock
            if (report.fetchedAt <= 0 || report.fetchedAt > checkedAt || checkedAt - report.fetchedAt > MAX_FORECAST_AGE_SECONDS) return@withLock
            val store = WeatherStore(context)
            val settings = store.settings()
            if (!settings.background.enabled || !settings.background.rainAlerts || settings.location?.identity != report.location.identity ||
                settings.units != report.units || settings.key == null) return@withLock
            if (store.cached(report.location, report.units)?.fetchedAt?.let { it > report.fetchedAt } == true) return@withLock
            createChannel(context)
            if (!allowed(context)) return@withLock
            val previousHistory = store.sentRainAlerts()
            val history = RainAlertPolicy.rememberEpisodes(report, checkedAt, previousHistory)
            val previousState = store.rainAlertState()
            val decision = RainForecastPolicy.evaluate(report, checkedAt, previousState, settings.background.quietHours, quietZone, history)
            val alert = decision.notification
            if (alert == null) {
                if (history != previousHistory || decision.state != previousState) store.saveRainAlertState(decision.state, history)
                return@withLock
            }
            val use24Hour = settings.display.use24Hour(DateFormat.is24HourFormat(context))
            val intent = Intent(context, MainActivity::class.java).setAction(OPEN_WEATHER_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val message = alert.message(report, use24Hour)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification_rain)
                .setContentTitle("${report.location.name} | Rain")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setTimeoutAfter((alert.expiresAt(checkedAt) - checkedAt).coerceAtLeast(1) * 1000)
                .build()
            synchronized(deliveryMonitor) {
                val latest = store.settings()
                val deliveryTime = now ?: (System.currentTimeMillis() / 1000)
                if (latest.background != settings.background || latest.display != settings.display ||
                    latest.location?.identity != report.location.identity || latest.units != report.units || latest.key != settings.key ||
                    latest.background.quietHours.isQuiet(deliveryTime, phoneZone ?: ZoneId.systemDefault()) ||
                    report.fetchedAt > deliveryTime || deliveryTime - report.fetchedAt > MAX_FORECAST_AGE_SECONDS ||
                    !allowed(context)) return@synchronized
                val remembered = decision.state.events.flatMap { event ->
                    event.timestamps.map { "${event.locationIdentity}|$it" }
                }.toSet()
                // Persist before posting so another worker or process restart cannot repeat the same notice.
                if (!store.saveRainAlertState(decision.state, RainAlertPolicy.pruneHistory(history + remembered, checkedAt))) return@synchronized
                try {
                    context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                } catch (_: Exception) {
                    store.restoreRainAlertState(previousState, previousHistory)
                }
            }
        }
    }
}
