// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WeatherCacheCodecTest {
    private val location = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private val now = epoch("2026-10-07T08:00:00Z")
    private val key = "private-test-credential"
    private fun epoch(iso: String) = Instant.parse(iso).epochSecond
    private val current = """{"dt":$now,"timezone":10800,"main":{"temp":20},"weather":[{"id":800,"icon":"01d"}]}"""
    private val hourly = """{"timezone":"Europe/Kyiv","timezone_offset":10800,"data":[{"dt":${now + 3600},"temp":21,"pop":0.1}]}"""

    private fun daily(first: String = "2026-10-07", count: Int = 7): String {
        val firstDay = LocalDate.parse(first)
        val data = JSONArray()
        repeat(count) { index ->
            data.put(JSONObject().put("dt", firstDay.plusDays(index.toLong()).atStartOfDay(ZoneId.of("Europe/Kyiv")).toEpochSecond())
                .put("temp", JSONObject().put("min", 10 + index).put("max", 20 + index))
                .put("weather", JSONArray().put(JSONObject().put("id", 800).put("icon", "01d"))))
        }
        return JSONObject().put("timezone", "Europe/Kyiv").put("timezone_offset", 10800).put("data", data)
            .put("next", "https://example.invalid/?appid=must-not-be-cached").put("appid", "must-not-be-cached").toString()
    }

    private fun response(coreAt: Long = now, dailyAt: Long = coreAt,
        dailyJson: String = daily(), reused: Boolean = false, loading: Boolean = false): WeatherResponse {
        val report = HybridWeatherParser.report(location, WeatherUnits.METRIC, current, hourly, null,
            coreAt, dailyJson = dailyJson, dailyFetchedAt = dailyAt, dailyFromCache = reused).copy(dailyLoading = loading)
        return WeatherResponse(report, current, hourly, null, dailyJson, dailyAt)
    }

    private fun decode(json: String, clock: Long = now, credential: String? = key, version: Int = 4) =
        WeatherCacheCodec.decode(json, version, location, WeatherUnits.METRIC, credential, clock)

    @Test fun dailySourceAgeSurvivesFreshCoreRefreshAndCacheRoundTrip() {
        val sourceTime = now - 30 * 3600
        val saved = WeatherCacheCodec.encode(response(dailyAt = sourceTime, reused = true), key)
        val cached = decode(saved)!!
        assertEquals(now, cached.report.fetchedAt)
        assertEquals(sourceTime, cached.report.dailyFetchedAt)
        assertTrue(cached.report.dailyFromCache)
        assertEquals(sourceTime, cached.response!!.dailyFetchedAt)
        assertEquals(7, cached.report.daily.size)
        assertFalse(cached.needsUpgrade)
    }

    @Test fun repeatedPersistenceDoesNotResetTheAgeOfReusedDailyData() {
        val sourceTime = now - 47 * 3600
        val original = decode(WeatherCacheCodec.encode(response(dailyAt = sourceTime, reused = true), key))!!
        val refreshed = original.response!!.copy(report = original.report.copy(fetchedAt = now + 7200))
        val cached = decode(WeatherCacheCodec.encode(refreshed, key), clock = now + 7200)!!
        assertEquals(now + 7200, cached.report.fetchedAt)
        assertNull(cached.response!!.dailyJson)
        assertNull(cached.report.dailyFetchedAt)
        assertTrue(cached.report.daily.isEmpty())
        assertEquals(20.0, cached.report.current.temperature, 0.0)
        assertEquals(1, cached.report.slots.size)
    }

    @Test fun fortyEightHourAgeBoundaryIsInclusiveAndFutureDatesCannotBeReused() {
        val boundary = WeatherCacheCodec.encode(response(dailyAt = now - 48 * 3600), key)
        assertNotNull(decode(boundary)!!.response!!.dailyJson)
        assertNull(decode(boundary, clock = now + 1)!!.response!!.dailyJson)
        val future = WeatherCacheCodec.encode(response(dailyAt = now + 1), key)
        assertNull(decode(future)!!.response!!.dailyJson)
    }

    @Test fun dayRolloverUsesRealRemainingDaysAndDropsYesterday() {
        val yesterday = epoch("2026-10-06T08:00:00Z")
        val saved = WeatherCacheCodec.encode(response(coreAt = yesterday, dailyJson = daily("2026-10-06")), key)
        val cached = decode(saved)!!
        assertEquals(yesterday, cached.report.fetchedAt)
        assertEquals(yesterday, cached.report.dailyFetchedAt)
        assertEquals((0L..5L).map { LocalDate.of(2026, 10, 7).plusDays(it) }, cached.report.daily.map { it.date })
        assertEquals(6, cached.report.daily.size)
    }

    @Test fun aForecastWithoutTodayCannotBecomeTheCachedDailyFallback() {
        val saved = WeatherCacheCodec.encode(response(dailyJson = daily("2026-10-08")), key)
        val cached = decode(saved)!!
        assertNull(cached.response!!.dailyJson)
        assertTrue(cached.report.daily.isEmpty())
        assertEquals(20.0, cached.report.current.temperature, 0.0)
    }

    @Test fun todayNeedsReadableTemperaturesToKeepTheSavedDailyForecast() {
        val root = JSONObject(daily())
        root.getJSONArray("data").getJSONObject(0).put("temp", JSONObject().put("min", "bad").put("max", JSONObject.NULL))
        val original = response(dailyJson = root.toString(), reused = true)
        val reason = "Daily forecast: OpenWeather took too long to respond. Please try again."
        val saved = WeatherCacheCodec.encode(original.copy(report = original.report.copy(
            forecastNote = "$reason Showing the saved forecast.")), key)
        val cached = decode(saved)!!
        assertNull(cached.response!!.dailyJson)
        assertTrue(cached.report.daily.isEmpty())
        assertEquals(reason, cached.report.forecastNote)
        assertEquals(20.0, cached.report.current.temperature, 0.0)
    }

    @Test fun expiredSavedDailyDataCannotLeaveASavedForecastClaimInTheNote() {
        val reason = "Hourly forecast: unavailable. Daily forecast: service unavailable."
        val original = response(dailyAt = now - 48 * 3600 - 1, reused = true)
        val saved = WeatherCacheCodec.encode(original.copy(report = original.report.copy(
            forecastNote = "$reason Showing the saved forecast.")), key)
        val cached = decode(saved)!!
        assertNull(cached.response!!.dailyJson)
        assertFalse(cached.report.dailyFromCache)
        assertEquals(reason, cached.report.forecastNote)
    }

    @Test fun oldV4WithoutDailyMetadataUsesOriginalFetchTimeDuringMigration() {
        val originalFetch = now - 3600
        val obj = JSONObject(WeatherCacheCodec.encode(response(coreAt = originalFetch)))
        obj.remove("dailyFetchedAt")
        obj.remove("dailyFromCache")
        val cached = decode(obj.toString())!!
        assertEquals(originalFetch, cached.report.dailyFetchedAt)
        assertFalse(cached.report.dailyFromCache)
        assertNotNull(cached.response!!.dailyJson)
        assertFalse(cached.needsUpgrade)
    }

    @Test fun legacyV3RetainsItsThreeHourForecastAndStillRequiresUpgrade() {
        val forecast = """{"list":[{"dt":$now,"main":{"temp":18,"temp_min":16,"temp_max":19},"weather":[{"id":801,"icon":"02d"}]}]}"""
        val obj = JSONObject(WeatherCacheCodec.encode(response())).put("version", 3).put("forecast", forecast)
        val cached = decode(obj.toString(), version = 3)!!
        assertTrue(cached.needsUpgrade)
        assertNull(cached.response!!.dailyJson)
        assertNotNull(cached.response!!.forecastJson)
        assertNull(cached.report.dailyFetchedAt)
        assertEquals(1, cached.report.daily.size)
    }

    @Test fun keyFingerprintIsSanitizedAndDifferentCredentialDropsOnlyDailyData() {
        val saved = WeatherCacheCodec.encode(response(), key)
        assertFalse(saved.contains(key))
        assertFalse(saved.contains("must-not-be-cached"))
        assertFalse(saved.contains("appid"))
        assertFalse(saved.contains("example.invalid"))
        assertEquals(64, JSONObject(saved).getString("keyFingerprint").length)
        assertNotNull(decode(saved)!!.response!!.dailyJson)
        val changed = decode(saved, credential = "different-test-credential")!!
        assertNull(changed.response!!.dailyJson)
        assertNull(changed.report.dailyFetchedAt)
        assertEquals(20.0, changed.report.current.temperature, 0.0)
        assertEquals(1, changed.report.slots.size)
    }

    @Test fun keyChangeClearsUnboundMigrationDailyWithoutDeletingCoreCache() {
        val old = WeatherCacheCodec.encode(response())
        val changed = WeatherCacheCodec.withoutUnboundDaily(old)!!
        val cached = decode(changed)!!
        assertNull(cached.response!!.dailyJson)
        assertEquals(20.0, cached.report.current.temperature, 0.0)
        assertEquals(1, cached.report.slots.size)
        assertNull(WeatherCacheCodec.withoutUnboundDaily(WeatherCacheCodec.encode(response(), key)))
    }

    @Test fun loadingStateIsEphemeralAndFreshDailyDataStaysFreshOnDisk() {
        val saved = WeatherCacheCodec.encode(response(loading = true), key)
        assertFalse(JSONObject(saved).has("dailyLoading"))
        val cached = decode(saved)!!
        assertFalse(cached.report.dailyLoading)
        assertFalse(cached.report.dailyFromCache)
        assertEquals(now, cached.report.dailyFetchedAt)
    }

    @Test fun malformedDailyMetadataOrPayloadCannotDiscardValidCoreData() {
        val saved = JSONObject(WeatherCacheCodec.encode(response(), key))
        listOf("bad timestamp", -1, 1.5).forEach { value ->
            val cached = decode(JSONObject(saved.toString()).put("dailyFetchedAt", value).toString())!!
            assertNull(cached.response!!.dailyJson)
            assertEquals(20.0, cached.report.current.temperature, 0.0)
        }
        val malformed = decode(saved.put("daily", "{bad JSON}").toString())!!
        assertNull(malformed.response!!.dailyJson)
        assertEquals(1, malformed.report.slots.size)
    }

    @Test fun cacheIdentityIncludesUnitsAndLocationAndKeyWhitespaceIsNormalized() {
        val saved = WeatherCacheCodec.encode(response(), key)
        assertNull(WeatherCacheCodec.decode(saved, 4, location, WeatherUnits.IMPERIAL, key, now))
        assertNull(WeatherCacheCodec.decode(saved, 4, location.copy(lat = 0.0), WeatherUnits.METRIC, key, now))
        assertEquals(WeatherCacheCodec.fingerprint(key), WeatherCacheCodec.fingerprint("  $key  "))
    }
}
