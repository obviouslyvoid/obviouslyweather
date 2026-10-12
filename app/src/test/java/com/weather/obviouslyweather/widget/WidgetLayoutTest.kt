// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.weather.obviouslyweather.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetLayoutTest {
    @Test fun summaryAndForecastFitSmallHostsWithoutLosingCityPixels(): Unit = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis() / 1000
        val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
        val settings = StoredSettings("test-key", city, WeatherUnits.METRIC, Appearance.DARK, false)
        val report = WeatherReport(city, WeatherUnits.METRIC, CurrentWeather(now, 15.0, null,
            804, "Overcast clouds", false, null, null, null, null, null, null, null, null, null),
            (1..24).map { ForecastSlot(now + it * 3600, 15.0, 9.0, 18.0, 801, "Few clouds",
                it % 2 == 0, 0.0, 0.0, 0.0) }, 0, now, forecastStepSeconds = 3600)
        val hosts = listOf(384 to 220, 350 to 190, 320 to 170, 280 to 110, 220 to 110)
        for ((width, height) in hosts) for (density in listOf(1f, 2f, 3f))
            for (fontScale in listOf(1f, 1.3f, 2f)) for (mode in WidgetForecastMode.entries) {
                val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                    densityDpi = (density * 160).toInt()
                    this.fontScale = fontScale
                    smallestScreenWidthDp = 384
                })
                val size = WidgetRenderSize(width, height, density)
                val preferences = WidgetPreferences(forecast = mode, background = false)
                val layout = if (mode == WidgetForecastMode.DAILY) R.layout.weather_widget_daily else R.layout.weather_widget
                val source = WeatherWidgetViews.create(context, preferences, settings, report, now, false, layout)
                val root = WeatherWidgetRenderer.layout(context, source, size).first
                val summary = root.findViewById<ViewGroup>(R.id.widget_summary)
                val name = "$width-$height-density$density-font$fontScale-$mode"
                for (index in 0 until summary.childCount) {
                    val child = summary.getChildAt(index)
                    assertTrue("Summary clipped: $name, child=${child.id}", child.top >= 0 && child.bottom <= summary.height)
                }
                val cityView = root.findViewById<TextView>(R.id.widget_city)
                assertEquals("Kyiv was truncated: $name", 0, cityView.layout.getEllipsisCount(0))
                val artwork = WeatherWidgetRenderer.artwork(context, SizeF(width.toFloat(), height.toFloat()),
                    preferences, settings, report, size)
                val cityLeft = summary.left + cityView.left
                val cityTop = summary.top + cityView.top
                var ink = 0
                for (y in cityTop until cityTop + cityView.height) for (x in cityLeft until cityLeft + cityView.width) {
                    if (artwork.bitmap.getPixel(x, y).ushr(24) > 128) ink++
                }
                if (ink <= 5 * density * density) {
                    println("CITY: $name ink=$ink box=$cityLeft,$cityTop ${cityView.width}x${cityView.height} textSize=${cityView.textSize} metrics=${context.resources.displayMetrics}")
                    File("build/reports/widget-layout").mkdirs()
                    File("build/reports/widget-layout/failure.png").outputStream().use {
                        artwork.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val manual = Bitmap.createBitmap(size.pixelWidth, size.pixelHeight, Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(manual))
                    File("build/reports/widget-layout/failure-manual.png").outputStream().use {
                        manual.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                assertTrue("City has no visible pixels: $name", ink > 5 * density * density)
                if (density == 3f && fontScale == 1f) {
                    val output = File("build/reports/widget-layout/$name.png")
                    output.parentFile!!.mkdirs()
                    output.outputStream().use { artwork.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                artwork.bitmap.recycle()
            }
    }

    @Test fun changingMinimumWidthKeepsTextAndIconsAtTheWorkingPhysicalSize() {
        val app = RuntimeEnvironment.getApplication()
        var temperatureSize: Float? = null
        var iconHeight: Int? = null
        for (minimumWidth in listOf(384, 350, 320)) {
            val densityDpi = (1080f / minimumWidth * 160).toInt()
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                this.densityDpi = densityDpi
                smallestScreenWidthDp = minimumWidth
                fontScale = 1f
            })
            val size = WidgetRenderSize(minimumWidth - 32, (220f * minimumWidth / 384).toInt(), densityDpi / 160f)
            val city = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
            val settings = StoredSettings("test-key", city, WeatherUnits.METRIC, Appearance.DARK, false)
            val source = WeatherWidgetViews.create(context, WidgetPreferences(), settings, null, interactive = false)
            val root = WeatherWidgetRenderer.layout(context, source, size).first
            val temperature = root.findViewById<TextView>(R.id.widget_temperature)
            val icon = root.findViewById<View>(R.id.widget_current_icon)
            if (temperatureSize == null) {
                temperatureSize = temperature.textSize
                iconHeight = icon.height
                assertEquals(32 * context.resources.displayMetrics.scaledDensity, temperature.textSize, 0.01f)
            } else {
                assertEquals("Temperature grew at minimum width $minimumWidth", temperatureSize, temperature.textSize, 0.5f)
                assertEquals("Icon grew at minimum width $minimumWidth", iconHeight!!, icon.height)
            }
            val label = root.findViewById<TextView>(R.id.widget_city)
            assertTrue(label.bottom <= root.findViewById<View>(R.id.widget_summary).height)
            assertTrue(label.height >= label.layout.height)
        }
    }
}
