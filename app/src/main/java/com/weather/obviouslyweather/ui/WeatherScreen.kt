// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.obviouslyweather.*
import com.weather.obviouslyweather.ui.theme.ObviouslyweatherTheme
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun weatherNumber(value: Double?, suffix: String = "", decimals: Int = 0): String =
    if (value == null || !value.isFinite()) "—" else String.format(Locale.getDefault(), "%.${decimals}f", value) + suffix

@Composable
internal fun WeatherScreen(state: WeatherState, model: WeatherViewModel) {
    val haptics = LocalAppHaptic.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val location = state.location
        if (location == null) {
            WeatherPanel {
                WeatherGlyph(801, modifier = Modifier.size(90.dp))
                Text("Choose your city", style = MaterialTheme.typography.headlineMedium)
                Text("See the weather where you are, or somewhere you're heading.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { haptics.perform(); model.openSheet(AppSheet.CITY) }, modifier = Modifier.fillMaxWidth()) { Text("Find a city") }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).clickable { haptics.perform(); model.openSheet(AppSheet.CITY) }.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(location.name, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f, fill = false))
                    ActionGlyph(ActionSymbol.CHEVRON, Modifier.padding(start = 5.dp).size(18.dp))
                }
                Text(location.detail.ifBlank { "Tap to change city" }, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RefreshButton(state.loading) { haptics.perform(); model.requestManualRefresh() }
        }
        state.error?.let {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MessageCard(if (state.report != null) "$it Showing your saved report." else it, error = true)
                Row {
                    TextButton(onClick = { haptics.perform(); model.requestManualRefresh() }, enabled = !state.loading) { Text("Try again") }
                    TextButton(onClick = { haptics.perform(); model.openSheet(AppSheet.SETTINGS) }) { Text("Settings") }
                }
            }
        }
        AnimatedContent(targetState = state.report, contentKey = { if (it == null) "loading" else "report" },
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "Weather report") { report ->
            if (report != null) {
                WeatherContent(report, state.fromCache, state.display)
            } else if (state.loading) {
                WeatherPanel {
                    WeatherGlyph(801, modifier = Modifier.size(100.dp))
                    Text("Getting your weather…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else if (state.error == null) {
                WeatherPanel {
                    WeatherGlyph(801, modifier = Modifier.size(100.dp))
                    Text("No weather loaded yet", style = MaterialTheme.typography.titleMedium)
                    Text("Tap the update button to load your forecast.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WeatherPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(28.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun WeatherContent(report: WeatherReport, fromCache: Boolean = false, display: DisplayPreferences = DisplayPreferences()) {
    val current = report.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(System.currentTimeMillis() / 1000, report, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis() / 1000
                delay(60_000 - System.currentTimeMillis() % 60_000)
            }
        }
    }
    val nextSlots = remember(report, now) { report.next24Hours(now) }
    val today = report.localTime(now).toLocalDate()
    val daily = remember(report, today) { report.daily.filter { it.date >= today }.take(7) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val use24 = display.use24Hour(DateFormat.is24HourFormat(LocalContext.current))
    val clock = clockFormatter(use24)
    val wind = windDisplay(current.windSpeed, report.units, display.metricWind)
    val gust = windDisplay(current.gust, report.units, display.metricWind)
    val visibility = current.visibility?.div(if (report.units == WeatherUnits.IMPERIAL) 1609.344 else 1000.0)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        WeatherPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (fromCache) "Saved conditions" else "Current conditions", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(report.localTime(current.timestamp).format(DateTimeFormatter.ofPattern("EEE, d MMM")),
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AnimatedContent(weatherNumber(current.temperature, "°"),
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "Temperature") { temperature ->
                        Text(temperature, fontSize = 76.sp, lineHeight = 80.sp, fontWeight = FontWeight.Medium, letterSpacing = (-3).sp)
                    }
                    Text(current.description, style = MaterialTheme.typography.titleLarge)
                }
                WeatherGlyph(current.condition, current.night, current.description, Modifier.size(114.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Feels like ${weatherNumber(current.feelsLike, report.units.temperature)}",
                    style = MaterialTheme.typography.bodyMedium, color = muted)
                val todayForecast = daily.firstOrNull { it.date == today }
                if (todayForecast != null) {
                    Text("·", color = muted)
                    Text("${weatherNumber(todayForecast.low, "°")} – ${weatherNumber(todayForecast.high, "°")}",
                        style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ActionGlyph(ActionSymbol.CLOCK, Modifier.size(16.dp))
                Text((if (fromCache) "Last refreshed " else "Updated ") + refreshClock(report.fetchedAt, use24),
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        if (nextSlots.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading(if (fromCache) "Saved forecast" else "Next 24 hours",
                    if (report.forecastStepSeconds == 3600) "City time · Hourly" else "City time · 3h")
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
                        Row {
                            nextSlots.forEach { slot ->
                                Column(Modifier.width(74.dp).padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Text(report.localTime(slot.timestamp).format(clock), style = MaterialTheme.typography.labelMedium, color = muted)
                                    WeatherGlyph(slot.condition, slot.night, slot.description, Modifier.size(38.dp))
                                    Text(weatherNumber(slot.temperature, "°"), style = MaterialTheme.typography.titleLarge)
                                    Text(weatherNumber(slot.precipitation, "%"), style = MaterialTheme.typography.bodySmall, color = muted,
                                        modifier = Modifier.semantics { contentDescription = "Chance of rain or snow ${weatherNumber(slot.precipitation, "%")}" })
                                }
                            }
                        }
                        TemperatureTrend(nextSlots, Modifier.width((nextSlots.size * 74).dp).height(40.dp))
                    }
                }
            }
        }
        if (daily.isNotEmpty() || report.dailyLoading) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading("Daily outlook", when {
                    report.dailyLoading && report.dailyFromCache -> "Saved · Updating…"
                    report.dailyLoading -> "Updating…"
                    report.dailyFromCache -> "Saved forecast"
                    else -> "Rain / snow"
                })
                if (report.dailyFromCache) report.dailyFetchedAt?.let { sourceTime ->
                    Text("Forecast saved " + java.time.Instant.ofEpochSecond(sourceTime)
                        .atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · ")) +
                        refreshClock(sourceTime, use24), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                if (report.dailyLoading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                if (daily.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val days = daily
                        val minimum = days.mapNotNull { it.low }.minOrNull() ?: 0.0
                        val maximum = days.mapNotNull { it.high }.maxOrNull() ?: minimum + 1.0
                        days.forEachIndexed { index, day ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp).clearAndSetSemantics {
                                contentDescription = "${day.date}, ${day.description}, low ${weatherNumber(day.low, report.units.temperature)}, high ${weatherNumber(day.high, report.units.temperature)}, chance of rain or snow ${weatherNumber(day.precipitation, "%")}"
                            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WeatherGlyph(day.condition, modifier = Modifier.size(36.dp))
                                Column(Modifier.width(55.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(if (day.date == today) "Today" else day.date.format(DateTimeFormatter.ofPattern("EEE")),
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(day.date.format(DateTimeFormatter.ofPattern("d MMM")), style = MaterialTheme.typography.labelSmall, color = muted)
                                }
                                Text(weatherNumber(day.low, "°"), color = muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(29.dp))
                                TemperatureRange(day.low, day.high, minimum, maximum, Modifier.weight(1f))
                                Text(weatherNumber(day.high, "°"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.width(29.dp))
                                Text(weatherNumber(day.precipitation, "%"), style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.End,
                                    modifier = Modifier.width(34.dp))
                            }
                            if (index < days.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
        report.forecastNote?.let { MessageCard(it) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading("Weather details")
            val direction = windDirection(current.windDegrees)?.let { " · $it" }.orEmpty()
            val metrics = listOf(
                WeatherMetric(DetailSymbol.WIND, "Wind", weatherNumber(wind.first, " " + wind.second, 1) + direction,
                    current.gust?.let { "Gusts ${weatherNumber(gust.first, " " + gust.second, 1)}" }),
                WeatherMetric(DetailSymbol.HUMIDITY, "Humidity", weatherNumber(current.humidity, "%")),
                WeatherMetric(DetailSymbol.PRESSURE, "Pressure", weatherNumber(current.pressure, " hPa")),
                WeatherMetric(DetailSymbol.VISIBILITY, "Visibility", weatherNumber(visibility, if (report.units == WeatherUnits.METRIC) " km" else " mi", 1)),
                WeatherMetric(DetailSymbol.CLOUDS, "Cloud cover", weatherNumber(current.clouds, "%"))
            )
            metrics.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { metric -> MetricCard(metric, Modifier.weight(1f)) }
                    if (row.size == 1) {
                        MetricCard(WeatherMetric(DetailSymbol.SUN, "Feels like", weatherNumber(current.feelsLike, report.units.temperature)), Modifier.weight(1f))
                    }
                }
            }
        }
        WeatherPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sun & daylight", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                DetailGlyph(DetailSymbol.SUN)
            }
            val sunrise = current.sunrise
            val sunset = current.sunset
            if (sunrise != null && sunset != null && sunset > sunrise) {
                val fraction = ((System.currentTimeMillis() / 1000.0 - sunrise) / (sunset - sunrise)).toFloat().coerceIn(0f, 1f)
                DaylightArc(fraction, Modifier.fillMaxWidth().height(52.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Sunrise", style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(sunrise?.let { report.localTime(it).format(clock) } ?: "—", style = MaterialTheme.typography.titleLarge)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Sunset", style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(sunset?.let { report.localTime(it).format(clock) } ?: "—", style = MaterialTheme.typography.titleLarge)
                }
            }
            if (sunrise != null && sunset != null && sunset > sunrise) {
                val minutes = (sunset - sunrise) / 60
                Text("${minutes / 60}h ${minutes % 60}m of daylight · City time", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Weather data by OpenWeather", style = MaterialTheme.typography.bodySmall, color = muted)
            Text("Observed ${report.localTime(current.timestamp).format(clock)} · City time",
                style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

private data class WeatherMetric(val icon: DetailSymbol, val label: String, val value: String, val detail: String? = null)

@Composable
private fun MetricCard(metric: WeatherMetric, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp), modifier = modifier) {
        Column(Modifier.padding(16.dp).heightIn(min = 94.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                DetailGlyph(metric.icon, Modifier.size(21.dp))
                Text(metric.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(metric.value, style = MaterialTheme.typography.titleLarge)
            metric.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SectionHeading(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun TemperatureRange(low: Double?, high: Double?, minimum: Double, maximum: Double, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.height(12.dp)) {
        val y = size.height / 2
        drawLine(track, Offset(0f, y), Offset(size.width, y), 5.dp.toPx(), StrokeCap.Round)
        if (low != null && high != null) {
            val span = (maximum - minimum).coerceAtLeast(1.0)
            val start = ((low - minimum) / span).coerceIn(0.0, 1.0).toFloat() * size.width
            val end = ((high - minimum) / span).coerceIn(0.0, 1.0).toFloat() * size.width
            drawLine(ink, Offset(start, y), Offset(end.coerceAtLeast(start), y), 5.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun TemperatureTrend(slots: List<ForecastSlot>, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.clearAndSetSemantics {}) {
        val values = slots.mapNotNull { it.temperature }
        if (values.isEmpty()) return@Canvas
        val min = values.min()
        val span = (values.max() - min).coerceAtLeast(1.0)
        val pointWidth = size.width / slots.size
        var previous: Offset? = null
        slots.forEachIndexed { index, slot ->
            val value = slot.temperature
            if (value == null) { previous = null; return@forEachIndexed }
            val point = Offset(pointWidth * (index + 0.5f), (size.height - 8.dp.toPx()) -
                ((value - min) / span).toFloat() * (size.height - 16.dp.toPx()))
            previous?.let { drawLine(ink.copy(alpha = 0.65f), it, point, 1.5.dp.toPx(), StrokeCap.Round) }
            drawCircle(ink, 2.5.dp.toPx(), point)
            previous = point
        }
    }
}

@Composable
private fun DaylightArc(fraction: Float, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.clearAndSetSemantics {}) {
        val padding = 12.dp.toPx()
        val width = size.width - padding * 2
        val height = size.height - 12.dp.toPx()
        val path = Path().apply {
            moveTo(padding, height)
            cubicTo(padding + width * 0.2f, -height * 0.25f, padding + width * 0.8f, -height * 0.25f, padding + width, height)
        }
        drawPath(path, track, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        val t = fraction
        val x = (1-t)*(1-t)*(1-t)*padding + 3*(1-t)*(1-t)*t*(padding+width*0.2f) +
            3*(1-t)*t*t*(padding+width*0.8f) + t*t*t*(padding+width)
        val y = (1-t)*(1-t)*(1-t)*height + 3*(1-t)*(1-t)*t*(-height*0.25f) +
            3*(1-t)*t*t*(-height*0.25f) + t*t*t*height
        drawCircle(ink.copy(alpha = 0.12f), 9.dp.toPx(), Offset(x, y))
        drawCircle(ink, 4.dp.toPx(), Offset(x, y))
    }
}

private fun previewReport(): WeatherReport {
    val now = 1791133200L
    val location = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    val current = CurrentWeather(now, 17.0, 16.0, 801, "Few clouds", false, 64.0, 1016.0, 10000.0,
        3.2, 225.0, 4.1, 22.0, now - 36000, now + 1800)
    val slots = (0..39).map { index ->
        ForecastSlot(now + (index + 1) * 10800, 14.0 + index % 6, 12.0 + index % 4, 18.0 + index % 4,
            if (index % 3 == 0) 500 else 801, if (index % 3 == 0) "Light rain" else "Few clouds",
            index % 8 < 2, (index % 5) * 10.0, 0.0, 0.0)
    }
    return WeatherReport(location, WeatherUnits.METRIC, current, slots, 10800, now)
}

@Preview(name = "Weather 1.5 · light", showBackground = true, widthDp = 393, heightDp = 1600)
@Composable
private fun WeatherLightPreview() {
    ObviouslyweatherTheme(Appearance.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) { WeatherContent(previewReport()) }
        }
    }
}

@Preview(name = "Weather 1.5 · dark", showBackground = true, widthDp = 393, heightDp = 1600)
@Composable
private fun WeatherDarkPreview() {
    ObviouslyweatherTheme(Appearance.DARK) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) { WeatherContent(previewReport()) }
        }
    }
}
