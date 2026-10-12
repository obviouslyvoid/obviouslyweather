// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class AppSheet { NONE, CITY, SETTINGS }
data class WeatherState(
    val initializing: Boolean = true, val hasKey: Boolean = false,
    val location: WeatherLocation? = null, val units: WeatherUnits = WeatherUnits.METRIC,
    val appearance: Appearance = Appearance.SYSTEM, val predictiveBack: Boolean = true, val report: WeatherReport? = null,
    val display: DisplayPreferences = DisplayPreferences(),
    val background: BackgroundPreferences = BackgroundPreferences(),
    val interaction: InteractionPreferences = InteractionPreferences(),
    val dailyProvider: DailyForecastProvider = DailyForecastProvider.OCA4,
    val loading: Boolean = false, val error: String? = null, val fromCache: Boolean = false,
    val cacheNeedsUpgrade: Boolean = false,
    val refreshConfirmation: Boolean = false,
    val sheet: AppSheet = AppSheet.NONE, val query: String = "", val cities: List<WeatherLocation> = emptyList(),
    val searching: Boolean = false, val searched: Boolean = false, val searchError: String? = null,
    val saving: Boolean = false, val settingsError: String? = null
)

class WeatherViewModel internal constructor(application: Application,
    private val refreshWeather: suspend (Context, RefreshIntent, Boolean, suspend (WeatherReport) -> Unit) -> RefreshResult
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, { context, intent, background, coreReady ->
        WeatherSync.refresh(context, intent, background, coreReady)
    })
    private val store = WeatherStore(application)
    private val repository = WeatherRepository()
    private val mutableState = MutableStateFlow(WeatherState(appearance = store.appearance()))
    val state = mutableState.asStateFlow()
    private var apiKey: String? = null
    private var weatherJob: Job? = null
    private val weatherJobs = mutableSetOf<Job>()
    private val backgroundWeatherJobs = mutableSetOf<Job>()
    @Volatile private var refreshGeneration = 0L
    @Volatile private var coreReadyGeneration: Long? = null
    private var searchJob: Job? = null
    private val foregroundActive = MutableStateFlow(false)

    private data class ForegroundRefreshPlan(
        val active: Boolean,
        val intervalMinutes: Int,
        val fetchedAt: Long?,
        val location: String?,
        val units: WeatherUnits
    )

    init {
        viewModelScope.launch {
            combine(state, foregroundActive) { snapshot, active ->
                ForegroundRefreshPlan(
                    active && !snapshot.initializing && snapshot.background.enabled && snapshot.hasKey && snapshot.location != null,
                    snapshot.background.normalizedIntervalMinutes, snapshot.report?.fetchedAt,
                    snapshot.location?.identity, snapshot.units
                )
            }.distinctUntilChanged().collectLatest { plan ->
                if (!plan.active) return@collectLatest
                while (true) {
                    delay(RefreshPolicy.foregroundDelayMillis(plan.fetchedAt, System.currentTimeMillis() / 1000, plan.intervalMinutes))
                    // Avoid replacing a manual fetch, a settings save or its confirmation dialog.
                    while (state.value.loading || state.value.saving || state.value.refreshConfirmation) delay(1000)
                    val snapshot = state.value
                    if (foregroundActive.value && snapshot.background.enabled && snapshot.hasKey && snapshot.location != null)
                        refresh(RefreshIntent.AUTOMATIC, background = true)
                    // Let a cache hit or result restart this timer from the accepted completion time.
                    delay(1000)
                }
            }
        }
        viewModelScope.launch {
            WeatherSync.updates.collect { update ->
                val report = update.report
                mutableState.update {
                    if (!it.initializing && it.location?.identity == report.location.identity && it.units == report.units &&
                        apiKey?.let(WeatherCacheCodec::fingerprint) == update.keyFingerprint &&
                        update.sequence == WeatherSync.latestUpdateSequence && update.dailyProvider == it.dailyProvider &&
                        (!update.background || it.background.enabled))
                        it.copy(report = report, fromCache = false, cacheNeedsUpgrade = false,
                            error = if (report.fetchedAt != it.report?.fetchedAt) null else it.error) else it
                }
            }
        }
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { store.settings() }
            apiKey = settings.key
            val cached = settings.location?.let { withContext(Dispatchers.IO) { store.cachedSnapshot(it, settings.units, settings.key) } }
            mutableState.value = WeatherState(
                initializing = false, hasKey = apiKey != null, location = settings.location, units = settings.units,
                appearance = settings.appearance, predictiveBack = settings.predictiveBack, display = settings.display, background = settings.background,
                interaction = settings.interaction, dailyProvider = settings.dailyProvider,
                report = cached?.report, fromCache = cached != null, cacheNeedsUpgrade = cached?.needsUpgrade == true,
                settingsError = if (settings.keyUnreadable) "Your saved key could not be opened. Please enter it again." else null,
                sheet = if (apiKey != null && settings.location == null) AppSheet.CITY else AppSheet.NONE
            )
            scheduleBackground()
            if (apiKey != null && settings.location != null && settings.display.refreshOnOpen) refresh()
        }
    }

    fun openSheet(sheet: AppSheet) {
        mutableState.update { it.copy(sheet = sheet, settingsError = null, searchError = null, refreshConfirmation = false) }
    }

    fun changeAppearance(appearance: Appearance) {
        mutableState.update { it.copy(appearance = appearance) }
        store.saveAppearance(appearance)
    }

    fun changePredictiveBack(enabled: Boolean) {
        mutableState.update { it.copy(predictiveBack = enabled) }
        store.savePredictiveBack(enabled)
    }

    fun changeDisplay(display: DisplayPreferences) {
        mutableState.update { it.copy(display = display) }
        store.saveDisplay(display)
        com.weather.obviouslyweather.widget.WeatherWidgets.requestUpdate(getApplication())
    }

    fun changeBackground(background: BackgroundPreferences) {
        val normalized = background.copy(intervalMinutes = background.normalizedIntervalMinutes)
        val cancelActive = !background.enabled && weatherJob?.let { it in backgroundWeatherJobs } == true
        if (!background.enabled) {
            if (cancelActive) {
                refreshGeneration++
                coreReadyGeneration = null
                weatherJob = null
            }
            backgroundWeatherJobs.toList().forEach { it.cancel() }
            backgroundWeatherJobs.clear()
        }
        mutableState.update { it.copy(background = normalized,
            loading = if (cancelActive) false else it.loading,
            report = if (!background.enabled) it.report?.copy(dailyLoading = false) else it.report) }
        store.saveBackground(normalized)
        if (!background.enabled || !background.rainAlerts) RainNotifications.cancel(getApplication())
        scheduleBackground()
    }

    fun changeInteraction(interaction: InteractionPreferences) {
        mutableState.update { it.copy(interaction = interaction) }
        store.saveInteraction(interaction)
    }

    fun changeDailyProvider(provider: DailyForecastProvider) {
        if (state.value.saving || state.value.dailyProvider == provider) return
        store.saveDailyProvider(provider)
        cancelWeatherRequests()
        mutableState.update { it.copy(dailyProvider = provider, loading = false, refreshConfirmation = false,
            report = it.report?.copy(dailyLoading = false)) }
        refresh(RefreshIntent.FORCE)
    }

    fun setForegroundActive(active: Boolean) {
        foregroundActive.value = active
    }

    private fun scheduleBackground() {
        com.weather.obviouslyweather.widget.WeatherWidgets.requestUpdate(getApplication())
        val snapshot = state.value
        BackgroundRefresh.schedule(getApplication(), snapshot.background.enabled && snapshot.hasKey && snapshot.location != null,
            intervalMinutes = snapshot.background.normalizedIntervalMinutes)
    }

    fun closeSheet() {
        if (state.value.saving) return
        searchJob?.cancel()
        mutableState.update { it.copy(sheet = AppSheet.NONE, searching = false) }
    }

    fun changeQuery(query: String) {
        searchJob?.cancel()
        mutableState.update { it.copy(query = query.take(120), cities = emptyList(), searched = false, searching = false, searchError = null) }
    }

    fun search() {
        val query = state.value.query.trim()
        val key = apiKey ?: return
        if (query.isBlank()) return
        searchJob?.cancel()
        mutableState.update { it.copy(searching = true, searched = false, cities = emptyList(), searchError = null) }
        searchJob = viewModelScope.launch {
            try {
                val cities = repository.search(query, key)
                mutableState.update { it.copy(cities = cities, searching = false, searched = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(searching = false, searchError = safeMessage(error)) }
            }
        }
    }

    fun chooseCity(location: WeatherLocation) {
        if (state.value.saving) return
        searchJob?.cancel()
        cancelWeatherRequests()
        mutableState.update { it.copy(saving = true, searching = false, searchError = null, refreshConfirmation = false) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.saveLocation(location) }
                RainNotifications.cancel(getApplication())
                val cached = withContext(Dispatchers.IO) { store.cachedSnapshot(location, state.value.units, apiKey) }
                mutableState.update { it.copy(location = location, report = cached?.report, fromCache = cached != null, cacheNeedsUpgrade = cached?.needsUpgrade == true,
                    sheet = AppSheet.NONE, saving = false, loading = false, error = null) }
                scheduleBackground()
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(saving = false, loading = false, searchError = safeMessage(error)) }
            }
        }
    }

    fun saveSettings(enteredKey: String, units: WeatherUnits, appearance: Appearance) {
        if (state.value.saving) return
        val key = enteredKey.trim().ifBlank { apiKey.orEmpty() }
        if (key.isBlank()) {
            mutableState.update { it.copy(settingsError = "Enter your OpenWeather API key to continue.") }
            return
        }
        val shouldRefresh = enteredKey.isNotBlank() || units != state.value.units || !state.value.hasKey
        mutableState.update { it.copy(saving = true, settingsError = null, refreshConfirmation = false) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.saveSettings(key, units, appearance) }
                if (shouldRefresh) {
                    cancelWeatherRequests()
                    searchJob?.cancel()
                }
                val keyChanged = apiKey != key
                apiKey = key
                mutableState.update {
                    it.copy(hasKey = true, units = units, appearance = appearance, saving = false,
                        report = if (it.units != units) null else if (keyChanged) it.report?.copy(providerDaily = emptyList(),
                            dailySlots = null, dailyFetchedAt = null, dailyFromCache = false, dailyLoading = false, forecastNote = null) else it.report,
                        cacheNeedsUpgrade = if (it.units != units) false else it.cacheNeedsUpgrade,
                        sheet = if (it.location == null) AppSheet.CITY else AppSheet.NONE,
                        loading = if (shouldRefresh) false else it.loading,
                        searching = false, searchError = null, cities = emptyList(), searched = false, error = null)
                }
                scheduleBackground()
                if (shouldRefresh && state.value.location != null) refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(saving = false, settingsError =
                    if (error is WeatherFailure) error.message else "Could not securely save your key. Please try again.") }
            }
        }
    }

    fun requestManualRefresh() {
        val snapshot = state.value
        if (snapshot.initializing || snapshot.loading || snapshot.saving || snapshot.location == null || apiKey == null) return
        if (!snapshot.cacheNeedsUpgrade && RefreshPolicy.isRecent(snapshot.report, System.currentTimeMillis() / 1000, snapshot.background.normalizedIntervalMinutes)) {
            mutableState.update { it.copy(refreshConfirmation = true) }
        } else refresh(RefreshIntent.MANUAL)
    }

    fun cancelManualRefresh() {
        mutableState.update { it.copy(refreshConfirmation = false) }
    }

    fun confirmManualRefresh() {
        if (!state.value.refreshConfirmation || state.value.loading || state.value.saving) return
        refresh(RefreshIntent.FORCE)
    }

    private fun cancelWeatherRequests() {
        refreshGeneration++
        coreReadyGeneration = null
        weatherJobs.toList().forEach { it.cancel() }
        weatherJobs.clear()
        backgroundWeatherJobs.clear()
        weatherJob = null
    }

    private fun refresh(intent: RefreshIntent = RefreshIntent.FORCE, background: Boolean = false) {
        val snapshot = state.value
        if (snapshot.location == null || apiKey == null) return
        // Keep an accepted core's daily subscriber alive while a confirmed new
        // core refresh joins it. Otherwise canceling first would restart the call.
        if (coreReadyGeneration != refreshGeneration) weatherJob?.cancel()
        val generation = ++refreshGeneration
        coreReadyGeneration = null
        mutableState.update { it.copy(loading = true, error = null, refreshConfirmation = false) }
        weatherJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = refreshWeather(getApplication(), intent, background) coreReady@{ core ->
                    coroutineContext.ensureActive()
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == refreshGeneration && (!background || state.value.background.enabled)) {
                            coreReadyGeneration = generation
                            mutableState.update {
                                if (it.location?.identity == core.location.identity && it.units == core.units)
                                    it.copy(loading = false)
                                else it
                            }
                        }
                    }
                }
                if (generation != refreshGeneration) return@launch
                mutableState.update {
                    when (result) {
                        is RefreshResult.Updated -> if (result.sequence == WeatherSync.latestUpdateSequence &&
                            it.location?.identity == result.response.report.location.identity && it.units == result.response.report.units)
                            it.copy(report = result.response.report, loading = false, fromCache = false, cacheNeedsUpgrade = false, error = null)
                            else it.copy(loading = false)
                        is RefreshResult.Recent -> if (result.sequence == WeatherSync.latestUpdateSequence &&
                            it.location?.identity == result.report.location.identity && it.units == result.report.units)
                            it.copy(report = result.report, loading = false, cacheNeedsUpgrade = false, error = null,
                                refreshConfirmation = intent == RefreshIntent.MANUAL)
                            else it.copy(loading = false)
                        RefreshResult.Skipped -> it.copy(loading = false)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == refreshGeneration)
                    mutableState.update { it.copy(loading = false, error = safeMessage(error), fromCache = it.report != null) }
            } finally {
                weatherJobs.remove(coroutineContext[Job])
                backgroundWeatherJobs.remove(coroutineContext[Job])
            }
        }.also { weatherJobs += it; if (background) backgroundWeatherJobs += it; it.start() }
    }

    fun refreshIfOld() {
        val current = state.value
        val report = current.report
        if (current.display.refreshOnOpen && !current.initializing && !current.loading && !current.saving && !current.refreshConfirmation &&
            current.hasKey && current.location != null && (current.cacheNeedsUpgrade ||
                !RefreshPolicy.isRecent(report, System.currentTimeMillis() / 1000, current.background.normalizedIntervalMinutes)))
            refresh(RefreshIntent.AUTOMATIC)
    }

    private fun safeMessage(error: Exception): String =
        if (error is WeatherFailure) error.message ?: "Could not load the weather."
        else "Could not load the weather. Please try again."
}
