// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.util.SizeF
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.weather.obviouslyweather.*
import com.weather.obviouslyweather.ui.WeatherSwitch
import com.weather.obviouslyweather.ui.theme.ObviouslyweatherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class WidgetSetup(
    val preferences: WidgetPreferences,
    val settings: StoredSettings,
    val report: WeatherReport?,
    val previewSize: SizeF,
    val previewRenderSize: WidgetRenderSize,
    val use24Hour: Boolean,
    val hideAmPmResetVersion: Long,
    val fontRevision: Long
)

class WidgetSettingsActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var setup by mutableStateOf<WidgetSetup?>(null)
    private var saving by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val initialAppearance = WeatherStore(this).appearance()
        applyPlatformAppearance(this, initialAppearance)
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, resultIntent())
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider != ComponentName(this, WeatherWidgetProvider::class.java)) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val loaded = setup
            ObviouslyweatherTheme(loaded?.settings?.appearance ?: initialAppearance) {
                val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
                BackHandler(enabled = saving) { }
                LaunchedEffect(light) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = light
                        isAppearanceLightNavigationBars = light
                    }
                }
                if (loaded == null) Surface(Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else WidgetSettings(loaded, saving, error, onCancel = { finish() }, onSave = ::save)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        lifecycleScope.launch {
            setup = withContext(Dispatchers.IO) {
                val store = WeatherStore(this@WidgetSettingsActivity)
                val settings = store.settings()
                val widgetStore = WidgetStore(this@WidgetSettingsActivity)
                val preferences = widgetStore.read(widgetId)
                WidgetSetup(preferences, settings,
                    settings.location?.let { store.cached(it, settings.units) },
                    WeatherWidgets.previewSize(this@WidgetSettingsActivity, widgetId),
                    WeatherWidgets.previewRenderSize(this@WidgetSettingsActivity, widgetId),
                    settings.display.use24Hour(DateFormat.is24HourFormat(this@WidgetSettingsActivity)),
                    widgetStore.hiddenAmPmResetVersion(), WidgetFonts.revision(this@WidgetSettingsActivity))
            }
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun save(preferences: WidgetPreferences) {
        if (saving) return
        saving = true
        error = null
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(WidgetStore(this@WidgetSettingsActivity).save(widgetId, preferences))
                    WeatherWidgets.update(this@WidgetSettingsActivity, widgetId)
                    val settings = WeatherStore(this@WidgetSettingsActivity).settings()
                    BackgroundRefresh.schedule(this@WidgetSettingsActivity,
                        settings.background.enabled && settings.key != null && settings.location != null)
                }
                setResult(RESULT_OK, resultIntent())
                finish()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not save the widget. Please try again."
                saving = false
            }
        }
    }
}

@Composable
private fun WidgetSettings(setup: WidgetSetup, saving: Boolean, error: String?, onCancel: () -> Unit, onSave: (WidgetPreferences) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(setup.preferences.forecast) }
    var background by rememberSaveable { mutableStateOf(setup.preferences.background) }
    var textColor by rememberSaveable { mutableStateOf(setup.preferences.textColor) }
    var hideAmPm by rememberSaveable { mutableStateOf(setup.preferences.hideAmPm && !setup.use24Hour) }
    var draftResetVersion by rememberSaveable { mutableLongStateOf(setup.hideAmPmResetVersion) }
    val resetHideAmPm = setup.use24Hour || draftResetVersion != setup.hideAmPmResetVersion
    LaunchedEffect(setup.use24Hour, setup.hideAmPmResetVersion) {
        if (setup.use24Hour || draftResetVersion != setup.hideAmPmResetVersion) hideAmPm = false
        draftResetVersion = setup.hideAmPmResetVersion
    }
    val preferences = WidgetPreferences(mode, background, textColor, hideAmPm = hideAmPm && !resetHideAmPm)
    Scaffold(
        topBar = {
            Text("Widget settings", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.statusBarsPadding().padding(start = 24.dp, top = 20.dp, bottom = 16.dp))
        },
        bottomBar = {
            Surface {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
                    Button(onClick = { onSave(preferences) }, enabled = !saving, modifier = Modifier.weight(1f).height(48.dp)) { Text(if (saving) "Saving…" else "Save") }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            WidgetPreview(preferences, setup)
            setup.settings.location?.let { Text(it.name, modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (setup.settings.key == null || setup.settings.location == null) {
                val context = LocalContext.current
                TextButton(onClick = { context.startActivity(Intent(context, MainActivity::class.java)) }) { Text("Set up weather in the app") }
            }
            SettingsCard {
                Text("Show weather", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                ForecastChoice("Hourly forecast", mode == WidgetForecastMode.THREE_HOUR, !saving) { mode = WidgetForecastMode.THREE_HOUR }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ForecastChoice("Daily forecast", mode == WidgetForecastMode.DAILY, !saving) { mode = WidgetForecastMode.DAILY }
            }
            if (!setup.use24Hour) {
                SettingsCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Hide AM/PM", style = MaterialTheme.typography.titleMedium)
                            Text("Show 12-hour times without AM or PM on this widget.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        WeatherSwitch(checked = preferences.hideAmPm, enabled = !saving, onCheckedChange = { hideAmPm = it })
                    }
                }
            }
            SettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Background", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    WeatherSwitch(checked = background, enabled = !saving, onCheckedChange = { background = it })
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("Font color", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = textColor == WidgetTextColor.WHITE, enabled = !saving,
                        onClick = { textColor = WidgetTextColor.WHITE }, label = { Text("White") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = textColor == WidgetTextColor.BLACK, enabled = !saving,
                        onClick = { textColor = WidgetTextColor.BLACK }, label = { Text("Black") }, modifier = Modifier.weight(1f))
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), content = content)
    }
}

@Composable
private fun ForecastChoice(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, enabled = enabled, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun WidgetPreview(preferences: WidgetPreferences, setup: WidgetSetup) {
    val context = LocalContext.current
    val artwork by produceState<Result<WidgetArtwork>?>(null, preferences, setup) {
        try {
            value = Result.success(WeatherWidgetRenderer.artwork(context.applicationContext, setup.previewSize,
                preferences, setup.settings, setup.report, setup.previewRenderSize))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            value = Result.failure(error)
        }
    }
    val size = WidgetRenderSize.forHost(setup.previewSize.width, setup.previewSize.height, 1f)
    Box(Modifier.fillMaxWidth().aspectRatio(size.width.toFloat() / size.height)
        .background(Brush.linearGradient(listOf(Color(0xFF929292), Color(0xFF555555))), RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center) {
        val rendered = artwork?.getOrNull()
        if (rendered != null) {
            val image = remember(rendered.bitmap) { rendered.bitmap.asImageBitmap() }
            Image(image, contentDescription = rendered.description.toString(), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else if (artwork?.isFailure == true) {
            Text("Preview unavailable", color = Color.White)
        } else {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}
