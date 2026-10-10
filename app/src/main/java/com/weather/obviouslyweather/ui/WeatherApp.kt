// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import android.app.Activity
import android.Manifest
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.weather.obviouslyweather.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalTime

private enum class BasePage { STARTUP, SETUP, WEATHER }

@Composable
fun WeatherApp(state: WeatherState, model: WeatherViewModel) {
    val activity = LocalContext.current as? Activity
    val haptics = LocalAppHaptic.current
    val backProgress = remember { Animatable(0f) }
    var backDirection by remember { mutableFloatStateOf(1f) }
    var completingBack by remember { mutableStateOf(false) }
    var lastSheet by remember { mutableStateOf(AppSheet.NONE) }
    if (state.sheet != AppSheet.NONE) lastSheet = state.sheet

    // A normal callback supports users who turn off the in-app swipe preview.
    BackHandler(enabled = !state.refreshConfirmation && state.sheet != AppSheet.NONE && (!state.predictiveBack || state.saving)) { model.closeSheet() }
    BackHandler(enabled = !state.refreshConfirmation && state.sheet == AppSheet.NONE && !state.predictiveBack) { activity?.finish() }
    PredictiveBackHandler(enabled = !state.refreshConfirmation && state.sheet != AppSheet.NONE && state.predictiveBack && !state.saving) { events ->
        try {
            events.collect { event ->
                backDirection = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                backProgress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            completingBack = true
            backProgress.animateTo(2f, tween(180))
            model.closeSheet()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { backProgress.animateTo(0f, tween(180)) }
            completingBack = false
            throw cancelled
        }
    }
    LaunchedEffect(state.sheet) {
        // Keep the final gesture transform through the exit transition.
        if (state.sheet == AppSheet.NONE) delay(220)
        backProgress.snapTo(0f)
        completingBack = false
    }

    val basePage = when {
        state.initializing -> BasePage.STARTUP
        !state.hasKey -> BasePage.SETUP
        else -> BasePage.WEATHER
    }
    Box(Modifier.fillMaxSize()) {
        val hiddenUnderlay = if (state.sheet != AppSheet.NONE) Modifier.clearAndSetSemantics {} else Modifier
        Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = hiddenUnderlay,
            topBar = { if (state.hasKey) AppHeader(state, AppSheet.NONE, model) }) { padding ->
            PageBody(padding) {
                AnimatedContent(basePage,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                    label = "Weather loading transition") { page ->
                    when (page) {
                        BasePage.STARTUP -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        BasePage.SETUP -> SettingsScreen(state.copy(hasKey = false), model)
                        BasePage.WEATHER -> WeatherScreen(state, model)
                    }
                }
            }
        }
        AnimatedVisibility(visible = state.sheet != AppSheet.NONE,
            enter = slideInHorizontally(tween(240)) { it / 6 } + fadeIn(tween(180)),
            exit = if (completingBack) ExitTransition.None else slideOutHorizontally(tween(180)) { it / 8 } + fadeOut(tween(140))) {
            Scaffold(containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val motion = predictiveTransform(backProgress.value, fromRight = backDirection < 0f)
                    translationX = motion.translationDp.dp.toPx()
                    scaleX = motion.scale
                    scaleY = motion.scale
                    alpha = motion.alpha
                    transformOrigin = TransformOrigin.Center
                    shape = RoundedCornerShape(motion.cornerDp.dp)
                    clip = motion.cornerDp > 0f
                },
                topBar = { AppHeader(state, lastSheet, model) }) { padding ->
                PageBody(padding) {
                    AnimatedContent(lastSheet,
                        transitionSpec = { (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 8 }) togetherWith fadeOut(tween(140)) },
                        label = "Settings and city transition") { sheet ->
                        when (sheet) {
                            AppSheet.SETTINGS -> SettingsScreen(state, model)
                            AppSheet.CITY -> CityScreen(state, model)
                            AppSheet.NONE -> Unit
                        }
                    }
                }
            }
        }
    }
    if (state.refreshConfirmation) {
        AlertDialog(
            onDismissRequest = model::cancelManualRefresh,
            title = { Text("Update weather?") },
            text = { Text("Weather was updated less than ${state.background.normalizedIntervalMinutes} minutes ago. Update it now?") },
            confirmButton = {
                TextButton(onClick = { haptics.perform(); model.confirmManualRefresh() }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { haptics.perform(); model.cancelManualRefresh() }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AppHeader(state: WeatherState, sheet: AppSheet, model: WeatherViewModel) {
    val haptics = LocalAppHaptic.current
    Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (sheet != AppSheet.NONE) {
                IconButton(onClick = { haptics.perform(); model.closeSheet() }, enabled = !state.saving,
                    modifier = Modifier.semantics { contentDescription = "Back" }) { ActionGlyph(ActionSymbol.BACK) }
                Text(if (sheet == AppSheet.CITY) "Choose a city" else "Settings", fontSize = 21.sp, fontWeight = FontWeight.Medium)
            } else {
                AppMark(modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(9.dp))
                Text("obviouslyweather", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.hasKey) {
                    IconButton(onClick = { haptics.perform(); model.openSheet(AppSheet.SETTINGS) },
                        modifier = Modifier.semantics { contentDescription = "Settings" }) { ActionGlyph(ActionSymbol.SETTINGS) }
                }
            }
        }
    }
}

@Composable
private fun PageBody(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 600.dp).fillMaxSize()) { content() }
    }
}


@Composable
private fun SettingsScreen(state: WeatherState, model: WeatherViewModel) {
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var units by remember(state.units) { mutableStateOf(state.units) }
    var showLicenses by remember { mutableStateOf(false) }
    val firstLaunch = !state.hasKey
    val context = LocalContext.current
    val haptics = LocalAppHaptic.current
    val focus = LocalFocusManager.current
    var notificationsAllowed by remember { mutableStateOf(RainNotifications.allowed(context)) }
    var permissionDenied by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = RainNotifications.allowed(context)
                if (notificationsAllowed) permissionDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted && RainNotifications.allowed(context)
        permissionDenied = !notificationsAllowed
        if (notificationsAllowed) model.changeBackground(state.background.copy(enabled = true, rainAlerts = true))
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (firstLaunch) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Set up\nobviouslyweather", style = MaterialTheme.typography.headlineLarge)
                Text("Add your OpenWeather key and choose a city.", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsSection("Appearance") {
            ChoiceRow(Appearance.entries, state.appearance, { it.name.lowercase().replaceFirstChar { char -> char.uppercase() } },
                enabled = !state.saving, onSelect = model::changeAppearance)
            PreferenceSwitch("Predictive back", "Preview the previous screen during a back swipe.",
                state.predictiveBack, !state.saving, model::changePredictiveBack)
        }
        SettingsSection("Haptics") {
            PreferenceSwitch("Haptic feedback", "Vibrate when you press buttons or change settings.",
                state.interaction.enabled, !state.saving, onChange = { enabled ->
                    val changed = state.interaction.copy(enabled = enabled)
                    model.changeInteraction(changed)
                    haptics.perform(changed)
                }, feedback = false)
            if (state.interaction.enabled) {
                Text("Strength", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(HapticStrength.entries, state.interaction.strength,
                    { if (it == HapticStrength.LIGHT) "Light" else "Strong" }, enabled = !state.saving,
                    onSelect = { strength ->
                        val changed = state.interaction.copy(strength = strength)
                        model.changeInteraction(changed)
                        haptics.perform(changed)
                    }, feedback = false)
            }
        }
        SettingsSection("Display") {
            Text("Time format", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(ClockStyle.entries, state.display.clock,
                { when (it) { ClockStyle.SYSTEM -> "System"; ClockStyle.TWELVE_HOUR -> "12 hour"; ClockStyle.TWENTY_FOUR_HOUR -> "24 hour" } },
                enabled = !state.saving, onSelect = { model.changeDisplay(state.display.copy(clock = it)) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Metric wind speed", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(MetricWind.entries, state.display.metricWind,
                { if (it == MetricWind.METRES_PER_SECOND) "m/s" else "km/h" }, enabled = !state.saving && units == WeatherUnits.METRIC,
                onSelect = { model.changeDisplay(state.display.copy(metricWind = it)) })
            if (units == WeatherUnits.IMPERIAL) {
                Text("Fahrenheit uses mph.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PreferenceSwitch("Auto-update on launch", "Update weather when you open the app. Background refresh is controlled separately.",
                state.display.refreshOnOpen, !state.saving,
                { model.changeDisplay(state.display.copy(refreshOnOpen = it)) })
        }
        SettingsSection("Widget font") {
            WidgetFontSetting(enabled = !state.saving)
        }
        SettingsSection("Background & alerts") {
            PreferenceSwitch("Background refresh", "Keep weather in the app and widget up to date.",
                state.background.enabled, !state.saving,
                { model.changeBackground(state.background.copy(enabled = it, rainAlerts = if (it) state.background.rainAlerts else false)) })
            RefreshIntervalSetting(state.background, !state.saving, model::changeBackground)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PreferenceSwitch("Rain alerts", "For rain above 30% in the next 3 hours, with useful forecast updates.",
                state.background.rainAlerts, !state.saving, onChange = { enabled ->
                if (!enabled) model.changeBackground(state.background.copy(rainAlerts = false))
                else if (RainNotifications.allowed(context)) {
                    notificationsAllowed = true
                    model.changeBackground(state.background.copy(enabled = true, rainAlerts = true))
                } else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
            if (permissionDenied || (state.background.rainAlerts && !notificationsAllowed)) {
                Text("Allow rain notifications in Android settings.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    haptics.perform()
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Notification settings") }
            }
            if (state.background.rainAlerts) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                QuietHoursSetting(state.background, state.display.use24Hour(DateFormat.is24HourFormat(context)),
                    !state.saving, model::changeBackground)
            }
        }
        SettingsSection("Weather connection") {
            Text("Daily forecast provider", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(DailyForecastProvider.entries, state.dailyProvider, { it.label },
                enabled = !state.saving, onSelect = model::changeDailyProvider)
            Text("Seven days including today. If One Call 4.0 fails, you can switch to One Call 3.0 here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Units", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(WeatherUnits.entries, units,
                { if (it == WeatherUnits.METRIC) "Celsius" else "Fahrenheit" }, enabled = !state.saving, onSelect = { units = it })
            OutlinedTextField(
                value = key, onValueChange = { key = it.trim().take(256) }, modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving, singleLine = true, label = { Text("OpenWeather API key") },
                placeholder = { Text(if (state.hasKey) "Leave empty to keep saved key" else "Paste your API key") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                trailingIcon = {
                    IconButton(onClick = { haptics.perform(); visible = !visible }, modifier = Modifier.semantics {
                        contentDescription = if (visible) "Hide API key" else "Show API key"
                    }) { ActionGlyph(if (visible) ActionSymbol.EYE_OFF else ActionSymbol.EYE) }
                }, shape = RoundedCornerShape(16.dp)
            )
            Text("One Call access is needed for hourly and seven-day forecasts. Your key stays on this device.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                haptics.perform()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://home.openweathermap.org/api_keys"))) }
            }, contentPadding = PaddingValues(0.dp)) { Text("Get an OpenWeather key ↗") }
            state.settingsError?.let { MessageCard(it, error = true) }
            Button(onClick = { haptics.perform(); focus.clearFocus(); model.saveSettings(key, units, state.appearance) },
                enabled = !state.saving && (key.isNotBlank() || (!firstLaunch && units != state.units)),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (firstLaunch) "Continue" else "Save key & units")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preferences save immediately. Apply a custom refresh interval below its field.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!firstLaunch) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("obviouslyweather ${BuildConfig.VERSION_NAME} · OpenWeather", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { haptics.perform(); showLicenses = true }) { Text("Licenses") }
                }
            }
        }
    }
    if (showLicenses && !firstLaunch) LicenseDialog(onClose = { showLicenses = false })
}

@Composable
private fun QuietHoursSetting(background: BackgroundPreferences, use24Hour: Boolean, enabled: Boolean,
    onChange: (BackgroundPreferences) -> Unit) {
    val quietHours = background.quietHours.normalized()
    val haptics = LocalAppHaptic.current
    var editingStart by remember { mutableStateOf<Boolean?>(null) }
    val canChange = enabled && background.enabled
    val clock = clockFormatter(use24Hour)
    fun timeLabel(minutes: Int): String = LocalTime.of(minutes / 60, minutes % 60).format(clock)
    PreferenceSwitch("Quiet hours", "Pause rain notifications during these times. Uses your phone's time zone.",
        quietHours.enabled, canChange,
        { onChange(background.copy(quietHours = quietHours.copy(enabled = it))) })
    if (quietHours.enabled) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { haptics.perform(); editingStart = true }, enabled = canChange,
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Start", style = MaterialTheme.typography.labelMedium)
                    Text(timeLabel(quietHours.startMinutes))
                }
            }
            OutlinedButton(onClick = { haptics.perform(); editingStart = false }, enabled = canChange,
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("End", style = MaterialTheme.typography.labelMedium)
                    Text(timeLabel(quietHours.endMinutes))
                }
            }
        }
        if (quietHours.startMinutes == quietHours.endMinutes) {
            Text("Matching times keep notifications quiet all day.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    editingStart?.let { start ->
        QuietHoursTimeDialog(if (start) "Quiet hours start" else "Quiet hours end",
            if (start) quietHours.startMinutes else quietHours.endMinutes, use24Hour,
            onDismiss = { editingStart = null }, onConfirm = { minutes ->
                onChange(background.copy(quietHours = if (start) quietHours.copy(startMinutes = minutes)
                    else quietHours.copy(endMinutes = minutes)))
                editingStart = null
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietHoursTimeDialog(title: String, minutes: Int, use24Hour: Boolean,
    onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val time = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60,
        is24Hour = use24Hour)
    val haptics = LocalAppHaptic.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { TimeInput(time) },
        confirmButton = {
            TextButton(onClick = { haptics.perform(); onConfirm(time.hour * 60 + time.minute) }) { Text("Save") }
        }, dismissButton = {
            TextButton(onClick = { haptics.perform(); onDismiss() }) { Text("Cancel") }
        })
}

@Composable
private fun RefreshIntervalSetting(background: BackgroundPreferences, enabled: Boolean, onChange: (BackgroundPreferences) -> Unit) {
    var minutes by remember(background.normalizedIntervalMinutes) {
        mutableStateOf(background.normalizedIntervalMinutes.toString())
    }
    val value = minutes.toIntOrNull()
    val valid = value != null && value in RefreshPolicy.MIN_INTERVAL_MINUTES..RefreshPolicy.MAX_INTERVAL_MINUTES
    val haptics = LocalAppHaptic.current
    val focus = LocalFocusManager.current
    val canChange = enabled && background.enabled
    Text("Refresh interval", style = MaterialTheme.typography.bodyMedium)
    ChoiceRow(listOf(30, 60, 120), background.normalizedIntervalMinutes,
        { when (it) { 30 -> "30 min"; 60 -> "1 hour"; else -> "2 hours" } }, enabled = canChange,
        onSelect = { onChange(background.copy(intervalMinutes = it)) })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = minutes, onValueChange = { text -> minutes = text.filter { it in '0'..'9' }.take(4) },
            modifier = Modifier.weight(1f), enabled = canChange, singleLine = true,
            label = { Text("Minutes") }, isError = minutes.isNotEmpty() && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), shape = RoundedCornerShape(16.dp)
        )
        TextButton(onClick = {
            if (valid && value != background.normalizedIntervalMinutes) {
                haptics.perform()
                focus.clearFocus()
                onChange(background.copy(intervalMinutes = value!!))
            }
        }, enabled = canChange && valid && value != background.normalizedIntervalMinutes) { Text("Apply") }
    }
    Text("15 minutes to 24 hours. Shared by the app and widget.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PreferenceSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, feedback: Boolean = true) {
    val haptics = LocalAppHaptic.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WeatherSwitch(checked, onCheckedChange = {
            if (feedback) haptics.perform()
            onChange(it)
        }, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean, onSelect: (T) -> Unit, feedback: Boolean = true) {
    val haptics = LocalAppHaptic.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = selected == option, enabled = enabled, onClick = {
                if (feedback) haptics.perform()
                onSelect(option)
            }, label = { Text(label(option)) },
                shape = RoundedCornerShape(14.dp),
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface))
        }
    }
}


@Composable
private fun CityScreen(state: WeatherState, model: WeatherViewModel) {
    val focus = LocalFocusManager.current
    val haptics = LocalAppHaptic.current
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Find a city", style = MaterialTheme.typography.headlineLarge)
        Text("Search a city and pick the right match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = state.query, onValueChange = model::changeQuery, modifier = Modifier.fillMaxWidth(),
            label = { Text("City") }, placeholder = { Text("Kyiv, UA") }, singleLine = true, enabled = !state.saving,
            leadingIcon = { ActionGlyph(ActionSymbol.SEARCH) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { haptics.perform(); focus.clearFocus(); model.search() }),
            shape = RoundedCornerShape(16.dp))
        Text("Add a country code to narrow the search, for example London, GB.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { haptics.perform(); focus.clearFocus(); model.search() }, enabled = state.query.isNotBlank() && !state.searching && !state.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text("Search") }
        if (state.searching || state.saving) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(if (state.saving) "Saving city…" else "Finding cities…")
            }
        }
        state.searchError?.let {
            MessageCard(it, error = true)
            TextButton(onClick = { haptics.perform(); model.openSheet(AppSheet.SETTINGS) }) { Text("Open Settings") }
        }
        if (state.searched && state.cities.isEmpty()) {
            MessageCard("No cities found. Check the spelling or include a country code.")
        }
        state.cities.forEach { city ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.saving) { haptics.perform(); focus.clearFocus(); model.chooseCity(city) }) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(city.name, style = MaterialTheme.typography.titleMedium)
                        Text(city.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ActionGlyph(ActionSymbol.CHEVRON)
                }
            }
        }
        if (!state.searched && state.searchError == null) {
            Spacer(Modifier.height(12.dp))
            Text("Your city is remembered for next time.\nYou can change it from the weather screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun MessageCard(message: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(16.dp), color = if (error) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
