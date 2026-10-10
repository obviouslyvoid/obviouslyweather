// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import com.weather.obviouslyweather.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class WidgetArtwork(val bitmap: Bitmap, val description: CharSequence)

/** Render in our own process so launchers cannot replace the bundled font or resize individual text. */
object WeatherWidgetRenderer {
    private val dispatcher by lazy {
        val thread = HandlerThread("weather-widget-artwork").apply { start() }
        Handler(thread.looper).asCoroutineDispatcher("Weather widget artwork")
    }

    suspend fun artwork(context: Context, size: SizeF, preferences: WidgetPreferences,
                        settings: StoredSettings, report: WeatherReport?, renderSize: WidgetRenderSize? = null): WidgetArtwork = withContext(dispatcher) {
        val source = WeatherWidgetViews.create(context, preferences, settings, report,
            interactive = false, layout = if (preferences.forecast == WidgetForecastMode.DAILY)
                R.layout.weather_widget_daily else R.layout.weather_widget)
        render(context, source, size, renderSize)
    }

    suspend fun create(context: Context, size: SizeF, preferences: WidgetPreferences,
                       settings: StoredSettings, report: WeatherReport?, renderSize: WidgetRenderSize? = null): RemoteViews =
        hostViews(context, artwork(context, size, preferences, settings, report, renderSize), interactive = true)

    suspend fun pickerPreview(context: Context): RemoteViews = withContext(dispatcher) {
        hostViews(context, render(context, RemoteViews(context.packageName, R.layout.weather_widget_preview), SizeF(360f, 220f)), interactive = false)
    }

    private fun render(context: Context, source: RemoteViews, hostSize: SizeF, renderSize: WidgetRenderSize? = null): WidgetArtwork {
        val deviceMetrics = context.resources.displayMetrics
        val size = renderSize ?: WidgetRenderSize.forHost(hostSize.width, hostSize.height, deviceMetrics.density,
            WidgetRenderSize.bitmapPixelBudget(deviceMetrics.widthPixels, deviceMetrics.heightPixels))
        val drawingContext = if (size.density == deviceMetrics.density) context else context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { densityDpi = (size.density * 160).roundToInt() })
        val metrics = drawingContext.resources.displayMetrics
        val view = source.apply(drawingContext, FrameLayout(drawingContext))
        val font = WidgetFonts.current(drawingContext).typeface
        fun assignFont(child: View) {
            if (child is TextView) {
                val weight = when (child.id) {
                    R.id.widget_temperature, R.id.widget_value_0, R.id.widget_value_1, R.id.widget_value_2, R.id.widget_value_3 -> 500
                    else -> 400
                }
                child.typeface = Typeface.create(font, weight, false)
                child.fontVariationSettings = "'wght' $weight"
                if (child.id == R.id.widget_city) child.alpha = 0.82f
            }
            if (child is ViewGroup) for (index in 0 until child.childCount) assignFont(child.getChildAt(index))
        }
        assignFont(view)
        // Four rows use the full 4x2 body. Taller manual sizes keep a bounded forecast group.
        view.findViewById<View>(R.id.widget_forecast).let { forecast ->
            forecast.layoutParams = forecast.layoutParams.apply {
                height = ((size.height - 32).coerceIn(78, 220) * metrics.density).roundToInt()
            }
        }
        val width = size.pixelWidth
        val height = size.pixelHeight
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val forecast = view.findViewById<View>(R.id.widget_forecast)
        val rows = forecastRows(view)
        forecast.alpha = 0f
        val bitmap = Bitmap.createBitmap(size.pixelWidth, size.pixelHeight, Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(bitmap)
        // Draw directly at the host's pixel density to preserve text and vector detail.
        view.draw(canvas)
        canvas.save()
        canvas.translate(forecast.left.toFloat(), forecast.top.toFloat())
        val label = view.findViewById<TextView>(R.id.widget_label_0)
        ForecastArtwork.draw(canvas, forecast.width, forecast.height, metrics.density, label.textSize,
            Typeface.create(font, 400, false), Typeface.create(font, 500, false), label.currentTextColor, rows,
            view.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        canvas.restore()
        return WidgetArtwork(bitmap, view.contentDescription ?: "Weather and forecast")
    }

    private fun forecastRows(root: View): List<ForecastArtwork.Row> {
        val labels = intArrayOf(R.id.widget_label_0, R.id.widget_label_1, R.id.widget_label_2, R.id.widget_label_3)
        val values = intArrayOf(R.id.widget_value_0, R.id.widget_value_1, R.id.widget_value_2, R.id.widget_value_3)
        val chances = intArrayOf(R.id.widget_chance_0, R.id.widget_chance_1, R.id.widget_chance_2, R.id.widget_chance_3)
        val drops = intArrayOf(R.id.widget_drop_0, R.id.widget_drop_1, R.id.widget_drop_2, R.id.widget_drop_3)
        val icons = intArrayOf(R.id.widget_icon_0, R.id.widget_icon_1, R.id.widget_icon_2, R.id.widget_icon_3)
        val nights = intArrayOf(R.id.widget_night_0, R.id.widget_night_1, R.id.widget_night_2, R.id.widget_night_3)
        return values.indices.map { i ->
            val chance = root.findViewById<TextView>(chances[i])
            val night = root.findViewById<ImageView>(nights[i])
            ForecastArtwork.Row(root.findViewById<TextView>(labels[i]).text.toString(),
                root.findViewById<TextView>(values[i]).text.toString(),
                chance.text.toString().takeIf { chance.visibility == View.VISIBLE },
                requireNotNull(root.findViewById<ImageView>(icons[i]).drawable),
                night.drawable.takeIf { night.visibility == View.VISIBLE },
                root.findViewById<ImageView>(drops[i]).drawable)
        }
    }

    private fun hostViews(context: Context, artwork: WidgetArtwork, interactive: Boolean) =
        RemoteViews(context.packageName, R.layout.weather_widget_host).apply {
            setImageViewBitmap(R.id.widget_artwork, artwork.bitmap)
            setViewVisibility(R.id.widget_loading, View.GONE)
            setContentDescription(android.R.id.background, artwork.description)
            if (interactive) WeatherWidgetViews.attachOpenApp(context, this)
        }
}
