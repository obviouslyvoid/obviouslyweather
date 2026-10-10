// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.weather.obviouslyweather.R

enum class ActionSymbol { SEARCH, SETTINGS, REFRESH, BACK, CHEVRON, EYE, EYE_OFF, KEY, CLOCK, UP, DOWN }
enum class DetailSymbol { WIND, HUMIDITY, PRESSURE, VISIBILITY, CLOUDS, SUN }

@Composable
fun ActionGlyph(symbol: ActionSymbol, modifier: Modifier = Modifier) {
    val resource = when (symbol) {
        ActionSymbol.SEARCH -> R.drawable.action_magnifying_glass
        ActionSymbol.SETTINGS -> R.drawable.action_sliders_horizontal
        ActionSymbol.REFRESH -> R.drawable.action_arrow_clockwise
        ActionSymbol.BACK -> R.drawable.action_arrow_left
        ActionSymbol.CHEVRON -> R.drawable.action_caret_right
        ActionSymbol.EYE -> R.drawable.action_eye
        ActionSymbol.EYE_OFF -> R.drawable.action_eye_slash
        ActionSymbol.KEY -> R.drawable.action_key
        ActionSymbol.CLOCK -> R.drawable.action_clock
        ActionSymbol.UP -> R.drawable.action_arrow_up
        ActionSymbol.DOWN -> R.drawable.action_arrow_down
    }
    Icon(painterResource(resource), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.size(24.dp))
}

@Composable
fun DetailGlyph(symbol: DetailSymbol, modifier: Modifier = Modifier) {
    val resource = when (symbol) {
        DetailSymbol.WIND -> R.drawable.glyph_wind
        DetailSymbol.HUMIDITY -> R.drawable.glyph_drop
        DetailSymbol.PRESSURE -> R.drawable.glyph_gauge
        DetailSymbol.VISIBILITY -> R.drawable.glyph_eye
        DetailSymbol.CLOUDS -> R.drawable.glyph_cloud
        DetailSymbol.SUN -> R.drawable.glyph_sun_horizon
    }
    Icon(painterResource(resource), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.size(28.dp))
}

@Composable
fun WeatherGlyph(condition: Int?, night: Boolean = false, description: String? = null, modifier: Modifier = Modifier) {
    val resource = when {
        condition == 800 -> if (night) R.drawable.glyph_moon_stars else R.drawable.glyph_sun
        condition == 801 || condition == 802 -> if (night) R.drawable.glyph_cloud_moon else R.drawable.glyph_cloud_sun
        condition in 200..299 -> R.drawable.glyph_cloud_lightning
        condition in 300..599 -> R.drawable.glyph_cloud_rain
        condition in 600..699 -> R.drawable.glyph_cloud_snow
        condition in 700..799 -> R.drawable.glyph_cloud_fog
        condition in 803..899 -> R.drawable.glyph_cloud
        else -> R.drawable.glyph_question
    }
    Crossfade(resource, modifier = modifier.size(48.dp), animationSpec = tween(160), label = "Weather icon") {
        Icon(painterResource(it), contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun AppMark(modifier: Modifier = Modifier) {
    val resource = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) R.drawable.app_mark_light else R.drawable.app_mark
    Image(painterResource(resource), contentDescription = null, modifier = modifier.size(28.dp))
}
