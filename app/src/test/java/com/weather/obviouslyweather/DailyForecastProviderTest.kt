// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DailyForecastProviderTest {
    @Test fun missingOrInvalidPreferenceDefaultsToOca4AndSavedOca3IsRetained() {
        assertEquals(DailyForecastProvider.OCA4, DailyForecastProvider.fromStoredValue(null))
        assertEquals(DailyForecastProvider.OCA4, DailyForecastProvider.fromStoredValue("unknown"))
        for (provider in DailyForecastProvider.entries) {
            assertEquals(provider, DailyForecastProvider.fromStoredValue(provider.name))
        }
    }

    @Test fun oca3RequestsOnlyDailyDataWithoutChangingCoordinatesOrUnits() {
        val base = mapOf("lat" to "50.45", "lon" to "30.52", "units" to "imperial")
        assertEquals(base, DailyForecastProvider.OCA4.parameters(base))
        assertEquals(base + ("exclude" to "current,minutely,hourly,alerts"), DailyForecastProvider.OCA3.parameters(base))
        assertFalse(base.containsKey("exclude"))
    }

    @Test fun oca3UsesExistingCacheSchemaAndDropsUnneededRootFields() {
        val raw = """{"lat":50.45,"lon":30.52,"timezone":"Europe/Kyiv","timezone_offset":10800,
            "daily":[{"dt":1791201600,"temp":{"min":10,"max":20},"pop":0.4}],
            "appid":"LOCAL_PROVIDER_SENTINEL","current":{},"hourly":[],"next":"private-link"}"""
        val safe = JSONObject(DailyForecastProvider.OCA3.cacheJson(raw))
        assertEquals(setOf("lat", "lon", "timezone", "timezone_offset", "data"), safe.keys().asSequence().toSet())
        assertEquals("Europe/Kyiv", safe.getString("timezone"))
        assertEquals(10800, safe.getInt("timezone_offset"))
        assertEquals(JSONObject(raw).getJSONArray("daily").toString(), safe.getJSONArray("data").toString())
        assertFalse(safe.toString().contains("LOCAL_PROVIDER_SENTINEL"))
    }

    @Test fun malformedOca3ResponsesAreRejectedWithoutAnAlternateProvider() {
        for (raw in listOf("not json", "{}", """{"daily":{}}""", """{"daily":"bad"}""",
            """{"cod":401,"message":"LOCAL_PRIVATE_SENTINEL"}""")) {
            val error = runCatching { DailyForecastProvider.OCA3.cacheJson(raw) }.exceptionOrNull()
            assertTrue(error is WeatherFailure)
            assertEquals("OpenWeather returned an unreadable daily forecast.", error!!.message)
        }
    }
}
