// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class OneCall4ParserTest {
    private val location = WeatherLocation("Kyiv", "UA", null, 50.45, 30.52)
    private val fetchedAt = epoch("2026-10-05T09:02:00Z")
    private fun epoch(iso: String) = Instant.parse(iso).epochSecond
    private fun fixture(name: String) = javaClass.getResource("/oca4-$name.json")!!.readText()
    private fun current(record: String = """{"dt":1791190800,"temp":17.5}""", metadata: String = "") =
        """{$metadata"data":[$record]}"""
    private fun report(hourly: String? = fixture("hourly"), daily: String? = fixture("daily"), current: String = fixture("current")) =
        OneCall4Parser.report(location, WeatherUnits.METRIC, current, hourly, daily, fetchedAt)
    private fun failure(action: () -> Any?) {
        try {
            action()
            fail("An unreadable response must fail explicitly")
        } catch (expected: WeatherFailure) {
            assertFalse(expected.message.isNullOrBlank())
            assertFalse(expected.message!!.contains("appid"))
        }
    }

    @Test fun separateDocumentedEnvelopesPreserveActualHourlyAndDailyValues() {
        val parsed = report()
        assertEquals(17.5, parsed.current.temperature, 0.0)
        assertEquals(16.8, parsed.current.feelsLike!!, 0.0)
        assertEquals(1023.0, parsed.current.pressure!!, 0.0)
        assertEquals(58.0, parsed.current.humidity!!, 0.0)
        assertEquals(20.0, parsed.current.clouds!!, 0.0)
        assertEquals(10000.0, parsed.current.visibility!!, 0.0)
        assertEquals(2.6, parsed.current.windSpeed!!, 0.0)
        assertEquals(275.0, parsed.current.windDegrees!!, 0.0)
        assertEquals(5.2, parsed.current.gust!!, 0.0)
        assertEquals(epoch("2026-10-05T03:58:00Z"), parsed.current.sunrise)
        assertEquals(epoch("2026-10-05T15:27:00Z"), parsed.current.sunset)
        assertEquals("Few clouds", parsed.current.description)
        assertEquals("Europe/Kyiv", parsed.zoneId)
        assertEquals(3600, parsed.forecastStepSeconds)
        assertEquals(4, parsed.slots.size)
        assertEquals(65.0, parsed.slots[1].precipitation!!, 0.001)
        assertEquals(0.9, parsed.slots[1].rainMm, 0.001)
        assertNull(parsed.slots[1].low)
        assertNull(parsed.slots[1].high)
        assertTrue(parsed.slots.last().night)
        assertEquals(8.4, parsed.daily.first().low!!, 0.0)
        assertEquals(19.6, parsed.daily.first().high!!, 0.0)
        assertEquals(500, parsed.daily.first().condition)
        assertNull(parsed.forecastNote)
    }

    @Test fun next24HoursUseFetchTimeNotAnOlderCurrentObservation() {
        val origin = epoch("2026-10-05T09:00:00Z")
        val data = JSONArray()
        (-3..49).reversed().forEach { hour -> data.put(JSONObject().put("dt", origin + hour * 3600).put("temp", 12 + hour)) }
        val parsed = report(hourly = JSONObject().put("data", data).toString())
        assertEquals(24, parsed.next24Hours(parsed.fetchedAt).size)
        assertEquals(epoch("2026-10-05T10:00:00Z"), parsed.next24Hours(parsed.fetchedAt).first().timestamp)
        assertEquals(epoch("2026-10-06T09:00:00Z"), parsed.next24Hours(parsed.fetchedAt).last().timestamp)
        assertTrue(parsed.next24Hours(parsed.fetchedAt).all { it.timestamp >= parsed.fetchedAt && it.timestamp < parsed.fetchedAt + 86400 })
        assertTrue(parsed.next24Hours(parsed.fetchedAt).zipWithNext().all { (a, b) -> b.timestamp - a.timestamp == 3600L })
    }

    @Test fun next24HoursExcludeTheExactEndAndDeduplicate() {
        val parsed = report(hourly = """{"data":[{"dt":$fetchedAt,"temp":1},{"dt":${fetchedAt + 86400},"temp":2},{"dt":$fetchedAt,"temp":3}]}""")
        assertEquals(listOf(fetchedAt), parsed.next24Hours(parsed.fetchedAt).map { it.timestamp })
        assertEquals(1.0, parsed.next24Hours(parsed.fetchedAt).single().temperature!!, 0.0)
    }

    @Test fun currentMustContainAValidFlatTemperatureAndTime() {
        listOf("not JSON", "[]", "{}", """{"data":{}}""", """{"data":[]}""",
            current("""{"dt":1}"""), current("""{"temp":17}"""),
            current("""{"dt":-1,"temp":17}"""), current("""{"dt":1.2,"temp":17}"""),
            current("""{"dt":1,"temp":"17"}"""), current("""{"dt":1,"temp":true}"""),
            current("""{"dt":1,"main":{"temp":17}}""")
        ).forEach { invalid -> failure { report(current = invalid) } }
    }

    @Test fun currentOptionalValuesStayUnknownAndZeroTemperatureIsValid() {
        val parsed = report(hourly = null, daily = null, current = current("""{"dt":1,"temp":0}"""))
        assertEquals(0.0, parsed.current.temperature, 0.0)
        assertNull(parsed.current.feelsLike)
        assertNull(parsed.current.condition)
        assertNull(parsed.current.pressure)
        assertNull(parsed.current.windSpeed)
        assertNull(parsed.current.sunrise)
        assertNull(parsed.current.sunset)
        assertEquals("Unknown conditions", parsed.current.description)
        assertTrue(parsed.slots.isEmpty())
        assertNotNull(parsed.providerDaily)
        assertTrue(parsed.daily.isEmpty())
        assertNotNull(parsed.forecastNote)
    }

    @Test fun malformedForecastEnvelopesCannotMasqueradeAsAnEmptyForecast() {
        listOf("bad JSON", "[]", "{}", """{"data":{}}""", """{"data":[{},null,{"dt":"bad"}]}""").forEach { invalid ->
            failure { OneCall4Parser.hourly(invalid) }
            failure { OneCall4Parser.daily(invalid, 0) }
        }
        assertTrue(OneCall4Parser.hourly("""{"data":[]}""").isEmpty())
        assertTrue(OneCall4Parser.daily("""{"data":[]}""", 0).isEmpty())
    }

    @Test fun hourlyOrderingAndDuplicatesAreHandledWithoutInventingRanges() {
        val slots = OneCall4Parser.hourly("""{"data":[{"dt":20,"temp":8},{"dt":"invalid"},{"dt":10,"temp":7},{"dt":20,"temp":99},{"dt":-1},null]}""")
        assertEquals(listOf(10L, 20L), slots.map { it.timestamp })
        assertEquals(8.0, slots.last().temperature!!, 0.0)
        assertTrue(slots.all { it.low == null && it.high == null })
    }

    @Test fun hourlyChanceAndHourlyAmountsAreClampedWithoutReadingThreeHourValues() {
        val slots = OneCall4Parser.hourly("""{"data":[{"dt":1,"pop":1.8,"rain":{"1h":-2,"3h":20},"snow":{"1h":0.8}},{"dt":2,"pop":-1},{"dt":3,"pop":"0.5","rain":{"3h":9}}]}""")
        assertEquals(100.0, slots[0].precipitation!!, 0.0)
        assertEquals(0.0, slots[1].precipitation!!, 0.0)
        assertNull(slots[2].precipitation)
        assertEquals(0.0, slots[0].rainMm, 0.0)
        assertEquals(0.8, slots[0].snowMm, 0.0)
        assertEquals(0.0, slots[2].rainMm, 0.0)
    }

    @Test fun providerDailyUsesTheWholeDayRangeAndNeverHourlyAggregation() {
        val parsed = report()
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6)), parsed.daily.map { it.date })
        assertEquals(8.4, parsed.daily[0].low!!, 0.0)
        assertEquals(19.6, parsed.daily[0].high!!, 0.0)
        assertEquals(80.0, parsed.daily[0].precipitation!!, 0.0)
        assertEquals(0.9, parsed.daily[0].rainMm, 0.0)
        assertEquals(7.2, parsed.daily[1].low!!, 0.0)
        assertTrue(report(daily = null).daily.isEmpty())
        assertTrue(report(daily = """{"data":[]}""").daily.isEmpty())
    }

    @Test fun dailySortsDeduplicatesLocalDatesAndPreservesMissingConditions() {
        val day1 = epoch("2026-10-05T09:00:00Z")
        val day2 = epoch("2026-10-06T09:00:00Z")
        val daily = OneCall4Parser.daily("""{"data":[{"dt":$day2,"temp":{"min":3,"max":10},"rain":2.3},{"dt":$day1,"temp":{"min":1,"max":9},"pop":1.2,"snow":{"1h":-2}},{"dt":${day1 + 3600},"temp":{"min":-99,"max":99}},{"dt":-1}]}""", 10800, "Europe/Kyiv")
        assertEquals(2, daily.size)
        assertEquals(1.0, daily.first().low!!, 0.0)
        assertEquals(9.0, daily.first().high!!, 0.0)
        assertNull(daily.first().condition)
        assertEquals("Unknown conditions", daily.first().description)
        assertEquals(100.0, daily.first().precipitation!!, 0.0)
        assertEquals(0.0, daily.first().snowMm, 0.0)
        assertEquals(2.3, daily.last().rainMm, 0.0)
        val unknown = OneCall4Parser.daily("""{"data":[{"dt":$day1,"temp":{"day":18,"night":8}}]}""", 0).single()
        assertNull(unknown.low)
        assertNull(unknown.high)
    }

    @Test fun ianaTimezoneUsesTheOffsetAtEachInstantAcrossDst() {
        val parsed = report(current = current(metadata = "\"timezone\":\"Europe/Kyiv\",\"timezone_offset\":10800,"))
        val first = parsed.localTime(epoch("2026-10-25T00:30:00Z"))
        val second = parsed.localTime(epoch("2026-10-25T01:30:00Z"))
        assertEquals(3, first.hour)
        assertEquals(3, second.hour)
        assertEquals(10800, first.offset.totalSeconds)
        assertEquals(7200, second.offset.totalSeconds)
        assertEquals(0, parsed.localTime(epoch("2026-10-26T22:00:00Z")).hour)
        assertEquals(LocalDate.of(2026, 10, 27), parsed.localTime(epoch("2026-10-26T22:00:00Z")).toLocalDate())
        assertEquals(12, parsed.localTime(epoch("2026-10-26T10:00:00Z")).hour)
    }

    @Test fun invalidOrAbsentZoneFallsBackToOffsetAndValidForecastMetadata() {
        val noZone = report(hourly = null, daily = null,
            current = current(metadata = "\"timezone\":\"invalid/zone\",\"timezone_offset\":7200,"))
        assertNull(noZone.zoneId)
        assertEquals(7200, noZone.localTime(epoch("2026-10-26T12:00:00Z")).offset.totalSeconds)
        val fallback = report(current = current())
        assertEquals("Europe/Kyiv", fallback.zoneId)
        assertEquals(10800, fallback.offset)
        assertEquals(7200, fallback.localTime(epoch("2026-10-26T12:00:00Z")).offset.totalSeconds)
        assertEquals(50400, localTimeAt(0, Int.MAX_VALUE, "bad-zone").offset.totalSeconds)
    }

    @Test fun currentTimezoneTakesPriorityOverOtherEndpointMetadata() {
        val parsed = report(current = current(metadata = "\"timezone\":\"America/New_York\",\"timezone_offset\":-14400,"))
        assertEquals("America/New_York", parsed.zoneId)
        assertEquals(-14400, parsed.offset)
        assertEquals(5, parsed.localTime(epoch("2026-10-05T09:00:00Z")).hour)
    }

    @Test fun dailyDateUsesItsIanaZoneRatherThanAStaleOffset() {
        val midnight = epoch("2026-10-26T22:30:00Z")
        val daily = OneCall4Parser.daily("""{"timezone":"Europe/Kyiv","data":[{"dt":$midnight,"temp":{"min":1,"max":2}}]}""", 0)
        assertEquals(LocalDate.of(2026, 10, 27), daily.single().date)
    }

    @Test fun explicitPartialFailureNoteIsRetainedWithValidCurrentConditions() {
        val parsed = OneCall4Parser.report(location, WeatherUnits.IMPERIAL, current(), null, fixture("daily"), fetchedAt, "Hourly unavailable")
        assertEquals(17.5, parsed.current.temperature, 0.0)
        assertEquals(WeatherUnits.IMPERIAL, parsed.units)
        assertEquals("Hourly unavailable", parsed.forecastNote)
        assertTrue(parsed.slots.isEmpty())
        assertEquals(2, parsed.daily.size)
    }
}
