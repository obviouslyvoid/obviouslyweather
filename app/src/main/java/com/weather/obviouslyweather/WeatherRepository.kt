// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import kotlin.coroutines.coroutineContext

data class WeatherResponse(
    val report: WeatherReport,
    val currentJson: String,
    val hourlyJson: String?,
    val forecastJson: String?,
    val dailyJson: String? = null,
    val dailyFetchedAt: Long? = null
)

class WeatherRepository internal constructor(
    private val transport: suspend (String, Map<String, String>, String) -> String,
    private val now: () -> Long,
    private val dailyTimeoutMillis: Long = 30_000
) {
    constructor() : this(::requestCoordinatedOpenWeather, { System.currentTimeMillis() / 1000 })

    suspend fun search(query: String, key: String): List<WeatherLocation> = withContext(Dispatchers.IO) {
        try {
            val json = transport("/geo/1.0/direct", mapOf("q" to query, "limit" to "5"), key)
            coroutineContext.ensureActive()
            WeatherParser.locations(json)
        } catch (_: JSONException) {
            throw WeatherFailure("OpenWeather returned an unreadable list of cities.")
        }
    }

    /** Preserve the original trailing-lambda callback when callers only need a report. */
    suspend fun weather(location: WeatherLocation, units: WeatherUnits, key: String,
        coreCallback: suspend (WeatherReport) -> Unit): WeatherResponse =
        weather(location, units, key, onCoreReady = coreCallback, previous = null, onCoreResponse = null)

    suspend fun weather(location: WeatherLocation, units: WeatherUnits, key: String,
        onCoreReady: (suspend (WeatherReport) -> Unit)? = null,
        onCoreResponse: (suspend (WeatherResponse) -> Unit)? = null,
        previous: WeatherResponse? = null,
        dailyProvider: DailyForecastProvider = DailyForecastProvider.OCA4): WeatherResponse = withContext(Dispatchers.IO) {
        coroutineScope {
            val startedAt = now()
            val parameters = mapOf("lat" to location.lat.toString(), "lon" to location.lon.toString(), "units" to units.apiValue)
            // Independent endpoints overlap; only hourly pagination depends on a previous response.
            val currentRequest = async {
                val json = currentCacheSafeJson(transport(CURRENT_PATH, parameters, key))
                coroutineContext.ensureActive()
                WeatherParser.report(location, units, json, null, startedAt)
                json
            }
            val hourlyRequest = async { hourly(parameters, key, startedAt) }
            val dailyRequest = async {
                try {
                    val raw = withTimeoutOrNull(dailyTimeoutMillis) {
                        transport(dailyProvider.path, dailyProvider.parameters(parameters), key)
                    }
                        ?: throw WeatherFailure("OpenWeather took too long to respond. Please try again.")
                    val json = dailyProvider.cacheJson(raw)
                    coroutineContext.ensureActive()
                    OneCall4Parser.daily(json, 0)
                    OptionalForecast(json, null, now())
                } catch (error: WeatherFailure) {
                    OptionalForecast(null, "Daily forecast: ${error.message}")
                } catch (_: JSONException) {
                    OptionalForecast(null, "OpenWeather returned an unreadable daily forecast.")
                }
            }
            // Required current conditions fail the refresh and cancel the optional branches.
            val currentJson = currentRequest.await()
            val hourly = hourlyRequest.await()
            coroutineContext.ensureActive()
            // The current/hourly refresh has its own completion time. Waiting for
            // daily data must neither prolong its button nor advance that time.
            val completedAt = now()
            fun response(dailyJson: String?, dailyFetchedAt: Long?, fromCache: Boolean,
                loading: Boolean, note: String?, at: Long): WeatherResponse {
                val report = HybridWeatherParser.report(location, units, currentJson, hourly.json, null,
                    at, note, dailyJson = dailyJson, dailyFetchedAt = dailyFetchedAt,
                    dailyFromCache = fromCache).copy(fetchedAt = completedAt,
                    dailyLoading = loading).let { if (loading) it.copy(forecastNote = hourly.note) else it }
                return WeatherResponse(report, currentJson, hourly.json, null, dailyJson, dailyFetchedAt)
            }

            fun usableDaily(json: String?, at: Long): String? = json?.let { raw ->
                try {
                    val safe = cacheSafeJson(raw, "daily forecast")
                    val report = HybridWeatherParser.report(location, units, currentJson, hourly.json,
                        null, at, dailyJson = safe)
                    val today = report.localTime(at).toLocalDate()
                    safe.takeIf { report.daily.any { it.date == today && (it.low != null || it.high != null) } }
                } catch (_: WeatherFailure) { null } catch (_: JSONException) { null }
            }

            fun retainedDaily(at: Long): OptionalForecast? {
                if (previous == null || previous.report.location.identity != location.identity ||
                    previous.report.units != units) return null
                val sourceTime = previous.dailyFetchedAt ?: previous.report.dailyFetchedAt ?: return null
                if (sourceTime !in 1..253402300799L || sourceTime > at ||
                    at - sourceTime > MAX_DAILY_REUSE_SECONDS) return null
                val json = usableDaily(previous.dailyJson, at) ?: return null
                return OptionalForecast(json, null, sourceTime)
            }

            if (!dailyRequest.isCompleted && (onCoreReady != null || onCoreResponse != null)) {
                val saved = retainedDaily(completedAt)
                val core = response(saved?.json, saved?.fetchedAt, saved != null, true,
                    hourly.note, completedAt)
                onCoreResponse?.invoke(core)
                onCoreReady?.invoke(core.report)
            }
            val daily = dailyRequest.await()
            coroutineContext.ensureActive()
            // Recheck the window after waiting, including a possible local date
            // rollover. Retain original provider records; never invent days.
            val resolvedAt = now()
            val fresh = usableDaily(daily.json, resolvedAt)
            val saved = if (fresh == null) retainedDaily(resolvedAt) else null
            val dailyNote = if (fresh != null) null else (daily.note
                ?: "The daily forecast is currently unavailable.") +
                if (saved != null) " Showing the saved forecast." else ""
            val note = listOfNotNull(hourly.note, dailyNote).distinct().joinToString(" ").takeIf { it.isNotBlank() }
            response(fresh ?: saved?.json, if (fresh != null) daily.fetchedAt else saved?.fetchedAt,
                saved != null, false, note, resolvedAt)
        }
    }

    private suspend fun hourly(parameters: Map<String, String>, key: String, startedAt: Long): OptionalForecast {
        var hourlyJson: String? = null
        val windowStart = startedAt - startedAt % 3600
        var hourlyStart = windowStart
        val hourlyData = JSONArray()
        var hourlyEnvelope: JSONObject? = null
        // OCA4 returns at most 20 records per page. Two pages cover the displayed
        // 24 hours; one independent daily request covers the seven-day outlook.
        for (pageIndex in 0 until 2) {
            coroutineContext.ensureActive()
            try {
                val raw = transport(HOURLY_PATH, parameters + ("start" to hourlyStart.toString()), key)
                coroutineContext.ensureActive()
                val page = JSONObject(raw)
                val slots = OneCall4Parser.hourly(raw)
                if (hourlyEnvelope == null) hourlyEnvelope = JSONObject(cacheSafeJson(raw, "hourly forecast"))
                val records = page.getJSONArray("data")
                for (index in 0 until records.length()) {
                    val record = records.optJSONObject(index) ?: continue
                    val time = record.number("dt") ?: continue
                    if (time >= windowStart && time < startedAt + 24 * 3600) hourlyData.put(record)
                }
                hourlyEnvelope.put("data", hourlyData)
                hourlyJson = hourlyEnvelope.toString()
                val merged = OneCall4Parser.hourly(hourlyJson)
                if (slots.isEmpty() || merged.count { it.timestamp >= startedAt } >= 24 ||
                    slots.last().timestamp >= startedAt + 24 * 3600) break
                // We never need to interpret a third-page link after the bounded second request.
                if (pageIndex == 1) break
                val next = (page.opt("next") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: break
                // A timeline can advertise an overlapping cursor, or a link format we cannot
                // safely parse. Continue from the last validated hour instead of treating a
                // usable first page as a failed forecast. All requests still use our fixed
                // HTTPS endpoint and the original location, units and key, never the URL.
                val lastHour = slots.last().timestamp
                val cursor = nextHourlyStart(next, maxOf(hourlyStart, slots.last().timestamp))
                    ?: (lastHour + 3600).takeIf {
                        lastHour >= hourlyStart && lastHour <= 253402300799L - 3600
                    } ?: break
                if (cursor >= startedAt + 24 * 3600) break
                hourlyStart = cursor
            } catch (error: WeatherFailure) {
                return OptionalForecast(hourlyJson, if (hourlyJson == null) "Hourly forecast: ${error.message}"
                    else "The rest of the hourly forecast is unavailable.")
            } catch (_: JSONException) {
                return OptionalForecast(hourlyJson, "OpenWeather returned an unreadable hourly forecast.")
            }
        }
        return OptionalForecast(hourlyJson, null)
    }

    private data class OptionalForecast(val json: String?, val note: String?, val fetchedAt: Long? = null)
    private companion object {
        const val CURRENT_PATH = "/data/2.5/weather"
        const val HOURLY_PATH = "/data/4.0/onecall/timeline/1h"
        const val MAX_DAILY_REUSE_SECONDS = 48 * 3600L
    }
}

/** Only accept a forward timestamp from a pagination link; rebuild all other parameters ourselves. */
internal fun nextHourlyStart(link: String, previous: Long): Long? = runCatching {
    val uri = URI(link)
    if (uri.scheme != "https" || uri.host != "api.openweathermap.org" || uri.port !in listOf(-1, 443) ||
        uri.rawPath != "/data/4.0/onecall/timeline/1h" || uri.rawUserInfo != null || uri.rawFragment != null) return@runCatching null
    val starts = uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
        val pair = parameter.split('=', limit = 2)
        if (pair.size == 2 && URLDecoder.decode(pair[0], "UTF-8") == "start") URLDecoder.decode(pair[1], "UTF-8") else null
    }
    if (starts.size != 1) return@runCatching null
    starts.single().toLongOrNull()?.takeIf { it > previous && it <= 253402300799L }
}.getOrNull()

/** Timeline links include appid. Cache only documented data fields for each product. */
internal fun cacheSafeJson(json: String, what: String): String =
    sanitizedJson(json, what, listOf("lat", "lon", "timezone", "timezone_offset", "data"))

internal fun currentCacheSafeJson(json: String): String = sanitizedJson(json, "current conditions",
    listOf("coord", "weather", "main", "visibility", "wind", "clouds", "dt", "sys", "timezone", "id", "name", "cod", "rain", "snow"))

internal fun forecastCacheSafeJson(json: String): String =
    sanitizedJson(json, "daily forecast", listOf("cod", "message", "cnt", "list", "city"))

private fun sanitizedJson(json: String, what: String, fields: List<String>): String = try {
    val root = JSONObject(json)
    val safe = JSONObject()
    for (name in fields) if (root.has(name)) safe.put(name, root.get(name))
    safe.toString()
} catch (_: JSONException) {
    throw WeatherFailure("OpenWeather returned an unreadable $what.")
}

fun serviceError(status: Int, oneCall: Boolean = false): String = when (status) {
    401 -> if (oneCall) "OpenWeather rejected your API key for forecasts. Confirm One Call access is active for this key."
        else "OpenWeather rejected your API key. Check it in Settings. New keys can take a while to activate."
    403 -> if (oneCall) "This API key cannot access One Call forecasts. Check the subscription for this key."
        else "This API key cannot access the requested weather data."
    404 -> "OpenWeather has no weather data for this location."
    429 -> "Your OpenWeather request limit has been reached. Please try again later."
    in 500..599 -> "OpenWeather is temporarily unavailable. Please try again later."
    else -> "OpenWeather could not complete the request (HTTP $status)."
}
