// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.weather.obviouslyweather.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Runs the complete widget renderer with Android's resource drawables. */
class WidgetRenderingChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val renders = runBlocking { checkDailyIcons() }
            result.putString("stream", "PASS: $renders Android widget renders contain day and night artwork in all four rows.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private suspend fun checkDailyIcons(): Int {
        val now = System.currentTimeMillis() / 1000
        val today = Instant.ofEpochSecond(now).atOffset(ZoneOffset.UTC).toLocalDate()
        val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
        val days = listOf(804, 500, 804, 500).mapIndexed { index, code ->
            DailyWeather(today.plusDays(index.toLong()), 9.0, 23.0, code, "Daily condition", 0.0, 0.0, 0.0)
        }
        val slots = listOf(800, 804).mapIndexed { index, code ->
            val time = today.plusDays(index.toLong()).atTime(23, 0).toEpochSecond(ZoneOffset.UTC)
            ForecastSlot(time, 13.0, null, null, code, "Night condition", true, 0.0, 0.0, 0.0)
        }
        val report = WeatherReport(city, WeatherUnits.METRIC,
            CurrentWeather(now, 16.0, null, 800, "Clear sky", true,
                null, null, null, null, null, null, null, null, null),
            slots, 0, now, providerDaily = days, forecastStepSeconds = 3600)
        val settings = StoredSettings("test-rendering-key", city, WeatherUnits.METRIC, Appearance.LIGHT, false)
        val errors = mutableListOf<String>()
        var renders = 0
        val legacySlots = days.flatMap { day ->
            val daytime = day.date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC)
            listOf(ForecastSlot(daytime, 20.0, day.low, day.high, day.condition, day.description,
                false, 0.0, 0.0, 0.0)) + slots.filter {
                Instant.ofEpochSecond(it.timestamp).atOffset(ZoneOffset.UTC).toLocalDate() == day.date
            }
        }
        fun condition(code: Int?, night: Boolean) = JSONArray().put(JSONObject().put("id", code)
            .put("icon", if (night) "04n" else "04d").put("description", "Test condition"))
        val current = JSONObject().put("dt", now).put("timezone", 0)
            .put("main", JSONObject().put("temp", 16)).put("weather", condition(800, true)).toString()
        val hours = JSONObject().put("timezone", "UTC").put("data", JSONArray(slots.map {
            JSONObject().put("dt", it.timestamp).put("temp", it.temperature).put("weather", condition(it.condition, true))
        })).toString()
        val daily = JSONObject().put("timezone", "UTC").put("data", JSONArray(days.map {
            JSONObject().put("dt", it.date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC))
                .put("temp", JSONObject().put("min", it.low).put("max", it.high))
                .put("weather", condition(it.condition, false))
        })).toString()
        val legacy = JSONObject().put("list", JSONArray(legacySlots.map {
            JSONObject().put("dt", it.timestamp).put("weather", condition(it.condition, it.night))
                .put("main", JSONObject().put("temp", it.temperature).put("temp_min", it.low).put("temp_max", it.high))
        })).toString()
        val reports = mutableMapOf("daily" to report)
        val store = WeatherStore(targetContext)
        for (source in listOf("oca4-cache", "oca3-cache", "legacy-cache")) {
            val dailyJson = when (source) {
                "legacy-cache" -> null
                "oca3-cache" -> DailyForecastProvider.OCA3.cacheJson(JSONObject(daily)
                    .apply { put("daily", remove("data")) }.toString())
                else -> daily
            }
            val forecast = legacy.takeIf { source == "legacy-cache" }
            val parsed = HybridWeatherParser.report(city, WeatherUnits.METRIC, current, hours, forecast,
                now, dailyJson = dailyJson)
            store.saveCache(WeatherResponse(parsed, current, hours, forecast, dailyJson), settings.key)
            reports[source] = checkNotNull(store.cached(city, WeatherUnits.METRIC)) { "Cache did not load: $source" }
        }
        for ((source, weather) in reports) for (color in WidgetTextColor.entries) for (background in listOf(false, true)) {
            val preferences = WidgetPreferences(WidgetForecastMode.DAILY, background, color)
            val size = WidgetRenderSize(360, 220, 3f)
            checkRender(targetContext, "$source-$color-$background", preferences, settings, weather,
                size, now, errors, export = true)
            renders++
        }

        val fixtures = listOf(
            "screenshot" to listOf(804 to 800, 500 to 801, 804 to 804, 500 to 500),
            "repeated-moons" to List(4) { 804 to 800 },
            "repeated-clouds" to List(4) { 804 to 804 }
        )
        val hosts = listOf(320f to 200f, 360f to 220f, 411f to 220f)
        val sizes = listOf(WidgetRenderSize(280, 110, 3f), WidgetRenderSize(320, 200, 2.75f), WidgetRenderSize(360, 220, 3f),
            WidgetRenderSize(411, 220, 2.75f)) + WidgetRenderSize.forHosts(hosts, 3f, 720, 1280)
        check(sizes.last().density < 2.75f && sizes.last().density % 0.25f != 0f) {
            "The responsive fixture must exercise a constrained, non-quarter density"
        }
        for ((fixture, conditions) in fixtures) {
            val fixtureDays = conditions.mapIndexed { index, codes ->
                DailyWeather(today.plusDays(index.toLong()), listOf(13.0, 11.0, 9.0, 11.0)[index],
                    listOf(23.0, 18.0, 15.0, 21.0)[index], codes.first, "Daily condition", 0.0, 0.0, 0.0)
            }
            val fixtureSlots = conditions.flatMapIndexed { index, codes ->
                val date = today.plusDays(index.toLong())
                listOf(ForecastSlot(date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC), 20.0,
                    null, null, codes.first, "Day condition", false, 0.0, 0.0, 0.0),
                    ForecastSlot(date.atTime(23, 0).toEpochSecond(ZoneOffset.UTC), 13.0,
                        null, null, codes.second, "Night condition", true, 0.0, 0.0, 0.0))
            }
            val weather = report.copy(providerDaily = fixtureDays, slots = fixtureSlots)
            for (size in sizes) for (fontScale in listOf(1f, 1.15f)) {
                val context = targetContext.createConfigurationContext(
                    Configuration(targetContext.resources.configuration).apply {
                        this.fontScale = fontScale
                        densityDpi = (size.density * 160).roundToInt()
                    })
                for (color in WidgetTextColor.entries) for (background in listOf(false, true)) {
                    val preferences = WidgetPreferences(WidgetForecastMode.DAILY, background, color)
                    val themedContext = context.createConfigurationContext(
                        Configuration(context.resources.configuration).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (color == WidgetTextColor.WHITE) Configuration.UI_MODE_NIGHT_YES
                                else Configuration.UI_MODE_NIGHT_NO
                        })
                    for (pass in 0..1) {
                        val name = "$fixture-${size.width}x${size.height}-${size.density}-font$fontScale-$color-$background-$pass"
                        checkRender(themedContext, name, preferences, settings.copy(appearance =
                            if (color == WidgetTextColor.WHITE) Appearance.DARK else Appearance.LIGHT),
                            weather, size, now, errors, export = fixture == "screenshot" &&
                                size == sizes.first() && fontScale == 1f && pass == 0)
                        renders++
                    }
                }
            }
        }
        check(errors.isEmpty()) { errors.joinToString("\n") }
        return renders
    }

    private suspend fun checkRender(context: Context, name: String, preferences: WidgetPreferences,
                                    settings: StoredSettings, weather: WeatherReport, size: WidgetRenderSize,
                                    now: Long, errors: MutableList<String>, export: Boolean = false) {
        val artwork = WeatherWidgetRenderer.artwork(context, SizeF(size.width.toFloat(), size.height.toFloat()),
            preferences, settings, weather, size)
        val drawing = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { densityDpi = (size.density * 160).roundToInt() })
        val source = WeatherWidgetViews.create(drawing, preferences, settings, weather,
            now, false, R.layout.weather_widget_daily)
        val (view, density) = WeatherWidgetRenderer.layout(drawing, source, size)
        val forecast = view.findViewById<View>(R.id.widget_forecast)
        val summary = view.findViewById<android.view.ViewGroup>(R.id.widget_summary)
        for (index in 0 until summary.childCount) {
            val child = summary.getChildAt(index)
            if (child.top < 0 || child.bottom > summary.height ||
                child is TextView && child.height < child.layout.height) errors += "$name: clipped summary child ${child.id}"
        }
        val font = WidgetFonts.current(drawing).typeface
        val blank = ColorDrawable(Color.TRANSPARENT)
        val rows = widgetForecastRows(weather, WidgetForecastMode.DAILY, now, true).map {
            ForecastArtwork.Row(it.label, it.value, null, blank, blank, null)
        }
        val scratch = Bitmap.createBitmap(forecast.width, forecast.height, Bitmap.Config.ARGB_8888)
        val cells = ForecastArtwork.draw(Canvas(scratch), forecast.width, forecast.height, density,
            view.findViewById<TextView>(R.id.widget_label_0).textSize,
            Typeface.create(font, 400, false), Typeface.create(font, 500, false),
            if (preferences.textColor == WidgetTextColor.WHITE) Color.WHITE else Color.BLACK, rows, false)
        val errorsBefore = errors.size
        cells.forEachIndexed { index, cell ->
            val day = cell.iconRegion
            val night = RectF(day.right, day.top, day.right + day.width(), day.bottom)
            listOf(day, night).forEachIndexed { side, region ->
                // These regions stop before text and above the divider. Count the whole cell,
                // including crescent edges that a small central sampling window can miss.
                val x0 = ceil(forecast.left + region.left).toInt().coerceAtLeast(0)
                val x1 = floor(forecast.left + region.right).toInt().coerceAtMost(artwork.bitmap.width)
                val y0 = ceil(forecast.top + region.top + density).toInt().coerceAtLeast(0)
                val y1 = floor(forecast.top + region.bottom - density).toInt().coerceAtMost(artwork.bitmap.height)
                var pixels = 0
                var left = x1; var top = y1; var right = x0 - 1; var bottom = y0 - 1
                for (y in y0 until y1) for (x in x0 until x1) {
                    val pixel = artwork.bitmap.getPixel(x, y)
                    val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                    val visible = Color.alpha(pixel) >= 128 &&
                        if (preferences.textColor == WidgetTextColor.WHITE) brightness >= 200 else brightness <= 70
                    if (visible) {
                        pixels++
                        left = minOf(left, x); top = minOf(top, y)
                        right = maxOf(right, x); bottom = maxOf(bottom, y)
                    }
                }
                val minimumPixels = maxOf(12, (4 * density * density).roundToInt())
                val minimumExtent = maxOf(3, (2 * density).roundToInt())
                if (pixels < minimumPixels || right - left + 1 < minimumExtent || bottom - top + 1 < minimumExtent) {
                    errors += "$name row=$index side=$side: $pixels icon pixels, bounds=($left,$top)-($right,$bottom), cell=($x0,$y0)-($x1,$y1)"
                }
            }
        }
        if (export || errors.size > errorsBefore) {
            File(targetContext.filesDir, "$name.png").outputStream().use {
                artwork.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        scratch.recycle()
        artwork.bitmap.recycle()
    }
}
