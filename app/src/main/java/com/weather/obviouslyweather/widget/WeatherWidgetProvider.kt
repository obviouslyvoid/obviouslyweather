// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.res.Configuration
import android.util.SizeF
import android.widget.RemoteViews
import com.weather.obviouslyweather.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** Cache-only launcher updates; one shared background worker refreshes weather and rain alerts. */
class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runCatching {
                            WeatherWidgets.updateAll(context)
                            val settings = WeatherStore(context).settings()
                            BackgroundRefresh.schedule(context, settings.background.enabled && settings.key != null && settings.location != null)
                        }
                    } finally { pending.finish() }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetStore(context)
        appWidgetIds.forEach(store::delete)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val settings = WeatherStore(context).settings()
                    val remaining = AppWidgetManager.getInstance(context)
                        .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
                        .any { it !in appWidgetIds }
                    BackgroundRefresh.schedule(context, settings.background.enabled && settings.key != null && settings.location != null,
                        hasWidgets = remaining)
                }
            } finally { pending.finish() }
        }
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val store = WidgetStore(context)
        oldWidgetIds.zip(newWidgetIds).forEach { (old, new) -> store.restore(old, new) }
        newWidgetIds.forEach { AppWidgetManager.getInstance(context).updateAppWidgetOptions(it,
            Bundle().apply { putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED, true) }) }
        WeatherWidgets.requestUpdate(context)
    }
}

object WeatherWidgets {
    private val lock = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { updateAll(app) } }
    }
    suspend fun updateAll(context: Context) = lock.withLock {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, WeatherWidgetProvider::class.java)
        publishPickerPreview(context, manager, component)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return@withLock
        val settings = WeatherStore(context).settings()
        val report = settings.location?.let { WeatherStore(context).cached(it, settings.units) }
        val store = WidgetStore(context)
        ids.forEach { id -> runCatching { manager.updateAppWidget(id, responsive(context, id, store.read(id), settings, report)) } }
    }
    suspend fun update(context: Context, id: Int) = lock.withLock {
        val settings = WeatherStore(context).settings()
        val report = settings.location?.let { WeatherStore(context).cached(it, settings.units) }
        AppWidgetManager.getInstance(context).updateAppWidget(id, responsive(context, id, WidgetStore(context).read(id), settings, report))
    }
    private suspend fun responsive(context: Context, id: Int, preferences: WidgetPreferences, settings: StoredSettings, report: WeatherReport?): RemoteViews {
        val sizes = hostSizes(context, id)
        val renderSizes = renderSizes(context, sizes)
        val layouts = sizes.mapIndexed { index, size ->
            size to WeatherWidgetRenderer.create(context, size, preferences, settings, report, renderSizes[index])
        }.toMap()
        return if (layouts.size == 1) layouts.values.first() else RemoteViews(layouts)
    }
    internal fun previewSize(context: Context, id: Int): SizeF = previewSize(context, id, hostSizes(context, id))
    internal fun previewRenderSize(context: Context, id: Int): WidgetRenderSize {
        val sizes = hostSizes(context, id)
        return renderSizes(context, sizes)[sizes.indexOf(previewSize(context, id, sizes))]
    }
    private fun renderSizes(context: Context, sizes: List<SizeF>): List<WidgetRenderSize> {
        val metrics = context.resources.displayMetrics
        return WidgetRenderSize.forHosts(sizes.map { it.width to it.height }, metrics.density,
            metrics.widthPixels, metrics.heightPixels)
    }
    private fun previewSize(context: Context, id: Int, sizes: List<SizeF>): SizeF {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
        val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220)
        return sizes.minBy { abs(it.width - width) + abs(it.height - height) }
    }
    private fun hostSizes(context: Context, id: Int): List<SizeF> {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            .orEmpty().filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
            .distinct().take(4).ifEmpty {
                listOf(SizeF(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320).coerceAtLeast(220).toFloat(),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220).coerceAtLeast(110).toFloat()))
            }
        return sizes
    }
    private suspend fun publishPickerPreview(context: Context, manager: AppWidgetManager, component: ComponentName) {
        val prefs = context.getSharedPreferences("weather_widget_preview", Context.MODE_PRIVATE)
        val fontRevision = WidgetFonts.revision(context)
        if (prefs.getInt("version", 0) == BuildConfig.VERSION_CODE && prefs.getLong("font_revision", -1) == fontRevision) return
        // Illustrative data lives only in the picker XML, never in a placed widget.
        if (runCatching { manager.setWidgetPreview(component, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                WeatherWidgetRenderer.pickerPreview(context)) }.getOrDefault(false)) prefs.edit()
                    .putInt("version", BuildConfig.VERSION_CODE).putLong("font_revision", fontRevision).apply()
    }
}
