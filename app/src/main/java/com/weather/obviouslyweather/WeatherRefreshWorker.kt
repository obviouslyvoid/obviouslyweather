// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class WeatherRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        try {
            WeatherSync.refresh(applicationContext, RefreshIntent.AUTOMATIC, background = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the saved report until the next scheduled run; never alert from stale cached weather.
        }
        return Result.success()
    }
}

object BackgroundRefresh {
    // Retain the existing name so upgrades update the job instead of leaving a second worker behind.
    private const val WORK_NAME = "obviouslyweather.hourly-weather"
    @Suppress("UNUSED_PARAMETER")
    fun schedule(context: Context, enabled: Boolean, hasWidgets: Boolean? = null,
        intervalMinutes: Int = WeatherStore(context).background().normalizedIntervalMinutes) {
        val manager = WorkManager.getInstance(context)
        val minutes = RefreshPolicy.scheduledMinutes(enabled, intervalMinutes)
        if (minutes == null) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        // App, widgets and alerts share a single cadence, whether any widget is placed or not.
        val request = PeriodicWorkRequestBuilder<WeatherRefreshWorker>(minutes, TimeUnit.MINUTES)
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // UPDATE changes the cadence without resetting enqueue time or interrupting an active refresh.
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
