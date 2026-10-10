// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DisplayPreferencesTest {
    @Test fun refreshChangesEvenWhenProviderObservationIsUnchanged() {
        val location = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
        val response = """{"dt":1791133200,"timezone":10800,"main":{"temp":17},"weather":[{"id":800,"description":"clear sky","icon":"01d"}]}"""
        val firstTime = Instant.parse("2026-10-04T17:19:05Z").epochSecond
        val first = WeatherParser.report(location, WeatherUnits.METRIC, response, null, firstTime)
        val second = WeatherParser.report(location, WeatherUnits.METRIC, response, null, firstTime + 3)
        assertEquals(first.current.timestamp, second.current.timestamp)
        assertEquals("20:19:05", refreshClock(first.fetchedAt, true, ZoneId.of("Europe/Kyiv")))
        assertEquals("20:19:08", refreshClock(second.fetchedAt, true, ZoneId.of("Europe/Kyiv")))
    }

    @Test fun refreshUsesPhoneTimezoneRatherThanWeatherLocation() {
        val timestamp = Instant.parse("2026-10-04T17:19:05Z").epochSecond
        assertEquals("13:19:05", refreshClock(timestamp, true, ZoneId.of("America/New_York")))
    }

    @Test fun explicitClockChoiceOverridesSystemPreference() {
        assertFalse(DisplayPreferences(ClockStyle.TWELVE_HOUR).use24Hour(true))
        assertTrue(DisplayPreferences(ClockStyle.TWENTY_FOUR_HOUR).use24Hour(false))
        assertFalse(DisplayPreferences().use24Hour(false))
        assertTrue(DisplayPreferences().use24Hour(true))
    }

    @Test fun metricConversionPreservesMissingValuesAndImperialSpeeds() {
        val converted = windDisplay(5.0, WeatherUnits.METRIC, MetricWind.KILOMETRES_PER_HOUR)
        assertEquals(18.0, converted.first!!, 0.001)
        assertEquals("km/h", converted.second)
        assertEquals(null to "km/h", windDisplay(null, WeatherUnits.METRIC, MetricWind.KILOMETRES_PER_HOUR))
        assertEquals(5.0 to "mph", windDisplay(5.0, WeatherUnits.IMPERIAL, MetricWind.KILOMETRES_PER_HOUR))
    }
}
