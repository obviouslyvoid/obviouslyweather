// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.format.DateFormat
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredSettings(val key: String?, val location: WeatherLocation?, val units: WeatherUnits, val appearance: Appearance, val keyUnreadable: Boolean, val predictiveBack: Boolean = true, val display: DisplayPreferences = DisplayPreferences(), val background: BackgroundPreferences = BackgroundPreferences(), val interaction: InteractionPreferences = InteractionPreferences(), val dailyProvider: DailyForecastProvider = DailyForecastProvider.OCA4)
internal data class CachedWeather(val report: WeatherReport, val needsUpgrade: Boolean, val response: WeatherResponse? = null)

class WeatherStore(context: Context) {
    private companion object { val cacheLock = Any() }
    private val applicationContext = context.applicationContext
    private val preferences = context.getSharedPreferences("weather_settings", Context.MODE_PRIVATE)
    // Retain the legacy cache while reading and migrating existing installations.
    private val legacyCache = AtomicFile(File(context.noBackupFilesDir, "last_weather_hybrid.json"))
    private val cache = AtomicFile(File(context.noBackupFilesDir, "last_weather_hybrid_v4.json"))
    private val keyAlias = "obviouslyweather.openweather"

    fun settings(): StoredSettings = synchronized(preferences) {
        var unreadable = false
        val key = try { readKey() } catch (_: Exception) { unreadable = true; null }
        val location = runCatching {
            preferences.getString("location", null)?.let { WeatherParser.location(JSONObject(it)) }
        }.getOrNull()
        val units = runCatching { WeatherUnits.valueOf(preferences.getString("units", null) ?: "METRIC") }.getOrDefault(WeatherUnits.METRIC)
        val appearance = appearance()
        val display = display()
        StoredSettings(key, location, units, appearance, unreadable, preferences.getBoolean("predictive_back", true), display, background(), interaction(), dailyProvider())
    }

    fun dailyProvider(): DailyForecastProvider =
        DailyForecastProvider.fromStoredValue(preferences.getString("daily_provider", null))

    fun saveDailyProvider(provider: DailyForecastProvider) {
        preferences.edit().putString("daily_provider", provider.name).apply()
    }

    fun background(): BackgroundPreferences = BackgroundPreferences(
        preferences.getBoolean("background_refresh", true),
        preferences.getBoolean("rain_alerts", false),
        RefreshPolicy.normalizedMinutes(runCatching { preferences.getInt("refresh_interval_minutes", RefreshPolicy.DEFAULT_INTERVAL_MINUTES) }
            .getOrDefault(RefreshPolicy.DEFAULT_INTERVAL_MINUTES)),
        QuietHoursPreferences(
            preferences.getBoolean("quiet_hours_enabled", true),
            runCatching { preferences.getInt("quiet_hours_start_minutes", 22 * 60) }.getOrDefault(22 * 60),
            runCatching { preferences.getInt("quiet_hours_end_minutes", 8 * 60) }.getOrDefault(8 * 60)
        ).normalized()
    )

    fun saveBackground(background: BackgroundPreferences) {
        val quietHours = background.quietHours.normalized()
        preferences.edit().putBoolean("background_refresh", background.enabled).putBoolean("rain_alerts", background.rainAlerts)
            .putInt("refresh_interval_minutes", background.normalizedIntervalMinutes)
            .putBoolean("quiet_hours_enabled", quietHours.enabled)
            .putInt("quiet_hours_start_minutes", quietHours.startMinutes)
            .putInt("quiet_hours_end_minutes", quietHours.endMinutes).apply()
    }

    fun interaction(): InteractionPreferences {
        val strength = runCatching { HapticStrength.valueOf(preferences.getString("haptic_strength", null) ?: "LIGHT") }
            .getOrDefault(HapticStrength.LIGHT)
        return InteractionPreferences(preferences.getBoolean("haptics_enabled", true), strength)
    }

    fun saveInteraction(interaction: InteractionPreferences) {
        preferences.edit().putBoolean("haptics_enabled", interaction.enabled).putString("haptic_strength", interaction.strength.name).apply()
    }

    fun sentRainAlerts(): Set<String> = synchronized(preferences) {
        preferences.getStringSet("sent_rain_alerts", emptySet()).orEmpty().toSet()
    }
    fun saveSentRainAlerts(history: Set<String>): Boolean = commitPreferences("sent_rain_alerts") {
        putStringSet("sent_rain_alerts", history)
    }

    fun rainAlertState(): RainAlertState = synchronized(preferences) {
        RainAlertStateCodec.decode(preferences.getString("rain_alert_state", null))
    }

    fun saveRainAlertState(state: RainAlertState, history: Set<String>): Boolean =
        commitPreferences("rain_alert_state", "sent_rain_alerts") {
            putString("rain_alert_state", RainAlertStateCodec.encode(state))
            putStringSet("sent_rain_alerts", history)
        }

    internal fun restoreRainAlertState(state: RainAlertState, history: Set<String>) = synchronized(preferences) {
        preferences.edit().putString("rain_alert_state", RainAlertStateCodec.encode(state))
            .putStringSet("sent_rain_alerts", history).commit()
    }

    fun saveAppearance(appearance: Appearance) {
        synchronized(preferences) { preferences.edit().putString("appearance", appearance.name).apply() }
        applyPlatformAppearance(applicationContext, appearance)
    }

    fun appearance(): Appearance = runCatching {
        Appearance.valueOf(preferences.getString("appearance", null) ?: "SYSTEM")
    }.getOrDefault(Appearance.SYSTEM)

    fun savePredictiveBack(enabled: Boolean) {
        preferences.edit().putBoolean("predictive_back", enabled).apply()
    }

    fun display(): DisplayPreferences {
        val clock = runCatching { ClockStyle.valueOf(preferences.getString("clock_style", null) ?: "SYSTEM") }.getOrDefault(ClockStyle.SYSTEM)
        val metricWind = runCatching { MetricWind.valueOf(preferences.getString("metric_wind", null) ?: "METRES_PER_SECOND") }.getOrDefault(MetricWind.METRES_PER_SECOND)
        return DisplayPreferences(clock, metricWind, preferences.getBoolean("refresh_on_open", true))
    }

    fun saveDisplay(display: DisplayPreferences) {
        preferences.edit().putString("clock_style", display.clock.name).putString("metric_wind", display.metricWind.name)
            .putBoolean("refresh_on_open", display.refreshOnOpen).apply()
        com.weather.obviouslyweather.widget.WidgetStore(applicationContext)
            .syncClock(display.use24Hour(DateFormat.is24HourFormat(applicationContext)))
    }

    fun saveSettings(key: String, units: WeatherUnits, appearance: Appearance) {
        val previousKey = runCatching { readKey() }.getOrNull()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        if (!commitPreferences("key_iv", "key_ciphertext", "units", "appearance") {
                putString("key_iv", encode(cipher.iv)).putString("key_ciphertext", encode(encrypted))
                    .putString("units", units.name).putString("appearance", appearance.name)
            }) {
            throw WeatherFailure("Could not save your settings. Please try again.")
        }
        if (previousKey != key) invalidateUnboundDaily()
    }

    fun saveLocation(location: WeatherLocation) {
        if (!commitPreferences("location") { putString("location", WeatherParser.locationJson(location).toString()) }) {
            throw WeatherFailure("Could not save the selected city. Please try again.")
        }
    }

    private fun commitPreferences(vararg keys: String, change: SharedPreferences.Editor.() -> Unit): Boolean =
        synchronized(preferences) {
            val previous = preferences.all
            if (preferences.edit().apply(change).commit()) true else {
                // commit() changes Android's in-memory map even when writing to disk fails.
                preferences.edit().apply {
                    for (key in keys) when (val value = previous[key]) {
                        null -> remove(key)
                        is String -> putString(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> error("Unsupported committed preference: $key")
                    }
                }.commit()
                false
            }
        }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun readKey(): String? = synchronized(preferences) {
        val encoded = preferences.getString("key_ciphertext", null) ?: return@synchronized null
        val iv = preferences.getString("key_iv", null) ?: error("Missing IV")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    fun cached(location: WeatherLocation, units: WeatherUnits): WeatherReport? =
        cachedSnapshot(location, units, runCatching { readKey() }.getOrNull())?.report

    internal fun cachedSnapshot(location: WeatherLocation, units: WeatherUnits, key: String? = null): CachedWeather? = synchronized(cacheLock) {
        readCache(cache, 4, location, units, key) ?: readCache(legacyCache, 3, location, units, key)
    }

    private fun readCache(file: AtomicFile, version: Int, location: WeatherLocation, units: WeatherUnits, key: String?): CachedWeather? = runCatching {
        val json = file.openRead().bufferedReader().use { it.readText() }
        WeatherCacheCodec.decode(json, version, location, units, key)
    }.getOrNull()

    fun saveCache(response: WeatherResponse, key: String? = null) = synchronized(cacheLock) {
        writeCache(WeatherCacheCodec.encode(response, key))
    }

    private fun invalidateUnboundDaily() = synchronized(cacheLock) {
        // Old installations did not bind their cached forecasts to an API key.
        // Keep core conditions on key changes, but do not reuse an unbound daily response.
        runCatching {
            val json = cache.openRead().bufferedReader().use { it.readText() }
            WeatherCacheCodec.withoutUnboundDaily(json)?.let { writeCache(it) }
        }
        Unit
    }

    private fun writeCache(json: String) {
        val output = cache.startWrite()
        try {
            output.write(json.toByteArray(Charsets.UTF_8))
            cache.finishWrite(output)
        } catch (error: Exception) {
            cache.failWrite(output)
            throw error
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/** Pure cache serialization keeps source age and credential binding independently testable. */
internal object WeatherCacheCodec {
    private const val MAX_DAILY_AGE_SECONDS = 48 * 3600L

    fun fingerprint(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun encode(response: WeatherResponse, key: String? = null): String {
        val report = response.report
        val obj = JSONObject().put("version", 4).put("location", WeatherParser.locationJson(report.location))
            .put("units", report.units.name).put("current", currentCacheSafeJson(response.currentJson))
            .put("hourly", response.hourlyJson?.let { cacheSafeJson(it, "hourly forecast") } ?: "")
            .put("forecast", response.forecastJson?.let { forecastCacheSafeJson(it) } ?: "")
            .put("daily", response.dailyJson?.let { cacheSafeJson(it, "daily forecast") } ?: "")
            .put("fetchedAt", report.fetchedAt).put("note", report.forecastNote ?: "")
        if (response.dailyJson != null) {
            obj.put("dailyFetchedAt", response.dailyFetchedAt ?: report.dailyFetchedAt ?: report.fetchedAt)
                .put("dailyFromCache", report.dailyFromCache)
        }
        key?.let { obj.put("keyFingerprint", fingerprint(it)) }
        return obj.toString()
    }

    fun decode(json: String, version: Int, location: WeatherLocation, units: WeatherUnits,
        key: String? = null, now: Long = System.currentTimeMillis() / 1000): CachedWeather? = runCatching {
        val obj = JSONObject(json)
        if (obj.optInt("version") != version || obj.optString("units") != units.name) return@runCatching null
        val savedLocation = WeatherParser.location(obj.getJSONObject("location")) ?: return@runCatching null
        if (savedLocation.identity != location.identity) return@runCatching null
        val current = currentCacheSafeJson(obj.getString("current"))
        val hourly = obj.stringOrNull("hourly")?.let { cacheSafeJson(it, "hourly forecast") }
        val forecast = obj.stringOrNull("forecast")?.let { forecastCacheSafeJson(it) }
        val fetchedAt = obj.getLong("fetchedAt")
        val sourceTime = if (obj.has("dailyFetchedAt")) obj.validTimestamp("dailyFetchedAt") else fetchedAt
        val savedFingerprint = obj.stringOrNull("keyFingerprint")
        val keyMatches = key == null || savedFingerprint == null || savedFingerprint == fingerprint(key)
        var daily = obj.stringOrNull("daily")?.takeIf {
            version == 4 && keyMatches && sourceTime != null && sourceTime <= now &&
                sourceTime > 0 && now - sourceTime <= MAX_DAILY_AGE_SECONDS
        }?.let { runCatching { cacheSafeJson(it, "daily forecast") }.getOrNull() }
        var note = obj.stringOrNull("note")
        if (daily == null) note = note?.removeSuffix(" Showing the saved forecast.")
        var report = runCatching {
            HybridWeatherParser.report(location, units, current, hourly, forecast, fetchedAt,
                note, daily, sourceTime, obj.optBoolean("dailyFromCache", false))
        }.getOrElse {
            daily = null
            note = note?.removeSuffix(" Showing the saved forecast.")
            HybridWeatherParser.report(location, units, current, hourly, forecast, fetchedAt, note)
        }
        if (daily != null) {
            val today = report.localTime(now).toLocalDate()
            val window = OneCall4Parser.daily(daily!!, report.offset, report.zoneId)
                .filter { it.date >= today && it.date <= today.plusDays(6) }.take(7)
            if (window.none { it.date == today && (it.low != null || it.high != null) }) {
                daily = null
                note = note?.removeSuffix(" Showing the saved forecast.")
                report = HybridWeatherParser.report(location, units, current, hourly, forecast, fetchedAt, note)
            } else {
                report = report.copy(providerDaily = window)
            }
        }
        val response = WeatherResponse(report, current, hourly, forecast, daily, report.dailyFetchedAt)
        CachedWeather(report, needsUpgrade = version == 3, response = response)
    }.getOrNull()

    fun withoutUnboundDaily(json: String): String? {
        val obj = JSONObject(json)
        if (obj.stringOrNull("keyFingerprint") != null || obj.stringOrNull("daily") == null) return null
        obj.put("daily", "").remove("dailyFetchedAt")
        obj.remove("dailyFromCache")
        return obj.toString()
    }

    private fun JSONObject.stringOrNull(name: String): String? = (opt(name) as? String)?.takeIf { it.isNotBlank() }
    private fun JSONObject.validTimestamp(name: String): Long? = (opt(name) as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it >= 0 && it <= 253402300799.0 && it == kotlin.math.floor(it) }?.toLong()
}
