// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshButtonTest {
    @Test fun fastResponsesKeepTheProgressStateReadable() {
        listOf(0L, 30L, 200L).forEach { fetchDuration ->
            val settle = refreshSettleDelay(1000L, 1000L + fetchDuration)
            assertTrue(fetchDuration + settle >= 650L)
            assertTrue(settle >= 250L)
        }
    }

    @Test fun slowResponsesStillSettleBeforeReturningToTheRefreshIcon() {
        assertEquals(250L, refreshSettleDelay(1000L, 8000L))
    }

    @Test fun staleOrReversedTimestampsDoNotCauseAnUnboundedWait() {
        assertEquals(650L, refreshSettleDelay(1000L, 900L))
    }
}
