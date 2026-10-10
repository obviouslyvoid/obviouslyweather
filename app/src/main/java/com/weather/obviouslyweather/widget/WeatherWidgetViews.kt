// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import com.weather.obviouslyweather.*

object WeatherWidgetViews {
    private val labels = intArrayOf(R.id.widget_label_0, R.id.widget_label_1, R.id.widget_label_2, R.id.widget_label_3)
    private val values = intArrayOf(R.id.widget_value_0, R.id.widget_value_1, R.id.widget_value_2, R.id.widget_value_3)
    private val chances = intArrayOf(R.id.widget_chance_0, R.id.widget_chance_1, R.id.widget_chance_2, R.id.widget_chance_3)
    private val drops = intArrayOf(R.id.widget_drop_0, R.id.widget_drop_1, R.id.widget_drop_2, R.id.widget_drop_3)
    private val icons = intArrayOf(R.id.widget_icon_0, R.id.widget_icon_1, R.id.widget_icon_2, R.id.widget_icon_3)
    private val nights = intArrayOf(R.id.widget_night_0, R.id.widget_night_1, R.id.widget_night_2, R.id.widget_night_3)
    private val dividers = intArrayOf(R.id.widget_divider_0, R.id.widget_divider_1, R.id.widget_divider_2, R.id.widget_divider_3)
    private val darkIcons = mapOf(
        R.drawable.widget_clear_day to R.drawable.widget_clear_day_dark,
        R.drawable.widget_clear_night to R.drawable.widget_clear_night_dark,
        R.drawable.widget_partly_cloudy_day to R.drawable.widget_partly_cloudy_day_dark,
        R.drawable.widget_partly_cloudy_night to R.drawable.widget_partly_cloudy_night_dark,
        R.drawable.widget_overcast to R.drawable.widget_overcast_dark,
        R.drawable.widget_overcast_rain to R.drawable.widget_overcast_rain_dark,
        R.drawable.widget_overcast_snow to R.drawable.widget_overcast_snow_dark,
        R.drawable.widget_thunderstorms to R.drawable.widget_thunderstorms_dark,
        R.drawable.widget_mist to R.drawable.widget_mist_dark,
        R.drawable.widget_not_available to R.drawable.widget_not_available_dark
    )

    fun create(context: Context, preferences: WidgetPreferences, settings: StoredSettings, report: WeatherReport?,
               now: Long = System.currentTimeMillis() / 1000, interactive: Boolean = true,
               layout: Int = R.layout.weather_widget): RemoteViews {
        val darkArtwork = preferences.textColor == WidgetTextColor.BLACK
        val color = if (darkArtwork) Color.BLACK else Color.WHITE
        val usable = report?.takeIf { settings.key != null && it.location.identity == settings.location?.identity && it.units == settings.units }
        return RemoteViews(context.packageName, layout).apply {
            setInt(android.R.id.background, "setBackgroundResource", when {
                !preferences.background -> 0
                preferences.textColor == WidgetTextColor.WHITE -> R.drawable.widget_background_dark
                else -> R.drawable.widget_background_light
            })
            val summaryIds = intArrayOf(R.id.widget_temperature, R.id.widget_description, R.id.widget_range, R.id.widget_city)
            (summaryIds + labels + values + chances).forEach { setTextColor(it, color) }
            dividers.forEach { setInt(it, "setBackgroundColor", if (color == Color.WHITE) 0x40FFFFFF else 0x33000000) }
            drops.forEach { setImageViewIcon(it, Icon.createWithResource(context, R.drawable.glyph_drop).setTint(color)) }
            setTextViewText(R.id.widget_city, settings.location?.name ?: "Choose a city")
            setTextViewText(R.id.widget_temperature, usable?.current?.temperature?.let(::widgetTemperature) ?: "—°")
            setTextViewText(R.id.widget_description, usable?.current?.description ?: when {
                settings.key == null || settings.location == null -> "Set up obviouslyweather"
                else -> "Open to load weather"
            })
            setTextViewText(R.id.widget_range, usable?.let { widgetTodayRange(it, now) } ?: "— / —")
            setImageViewResource(R.id.widget_current_icon, weatherIcon(usable?.current?.condition, usable?.current?.night ?: false, darkArtwork))
            setContentDescription(R.id.widget_current_icon, usable?.current?.description ?: "Weather unavailable")
            val rows = usable?.let { widgetForecastRows(it, preferences.forecast, now,
                settings.display.use24Hour(DateFormat.is24HourFormat(context)), preferences.hideAmPm) }.orEmpty()
            for (i in 0..3) {
                val row = rows.getOrNull(i)
                val hourly = preferences.forecast == WidgetForecastMode.THREE_HOUR
                setTextViewText(labels[i], row?.label ?: "—")
                setTextViewText(values[i], row?.value ?: "—")
                setTextViewText(chances[i], row?.chance ?: "—")
                setViewVisibility(chances[i], if (hourly) View.VISIBLE else View.GONE)
                setViewVisibility(drops[i], if (hourly) View.VISIBLE else View.GONE)
                setImageViewResource(icons[i], weatherIcon(row?.condition, row?.night ?: false, darkArtwork))
                setContentDescription(icons[i], row?.description ?: "Forecast unavailable")
                setViewVisibility(nights[i], if (!hourly && row?.secondCondition != null) View.VISIBLE else View.GONE)
                setImageViewResource(nights[i], weatherIcon(row?.secondCondition, row?.secondNight ?: true, darkArtwork))
            }
            setContentDescription(android.R.id.background, buildString {
                append(settings.location?.name ?: "obviouslyweather")
                if (usable != null) {
                    append(", ${widgetTemperature(usable.current.temperature)}, ${usable.current.description}. ")
                    append(rows.joinToString(". ") { "${it.label}, ${it.description}, ${it.value}" + (it.chance?.let { chance -> ", rain chance $chance" } ?: "") })
                } else append(", open the app to set up or load weather")
            })
            if (interactive) {
                attachOpenApp(context, this)
            }
        }
    }

    internal fun attachOpenApp(context: Context, views: RemoteViews) {
        val intent = Intent(context, MainActivity::class.java).setAction(RainNotifications.OPEN_WEATHER_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        views.setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(context, 2000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    private fun weatherIcon(condition: Int?, night: Boolean, darkArtwork: Boolean): Int {
        val icon = when {
            condition == 800 -> if (night) R.drawable.widget_clear_night else R.drawable.widget_clear_day
            condition == 801 || condition == 802 -> if (night) R.drawable.widget_partly_cloudy_night else R.drawable.widget_partly_cloudy_day
            condition in 200..299 -> R.drawable.widget_thunderstorms
            condition in 300..599 -> R.drawable.widget_overcast_rain
            condition in 600..699 -> R.drawable.widget_overcast_snow
            condition in 700..799 -> R.drawable.widget_mist
            condition in 803..899 -> R.drawable.widget_overcast
            else -> R.drawable.widget_not_available
        }
        return if (darkArtwork) darkIcons.getValue(icon) else icon
    }
}
