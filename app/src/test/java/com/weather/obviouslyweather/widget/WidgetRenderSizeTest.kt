// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetRenderSizeTest {
    @Test fun realHostAspectRatioIsRetainedInsteadOfShorteningTheArtwork() {
        assertEquals(180, WidgetRenderSize.forHost(360f, 180f, 3f).height)
        assertEquals(220, WidgetRenderSize.forHost(400f, 220f, 3f).height)
        assertEquals(320, WidgetRenderSize.forHost(360f, 320f, 3f).height)
    }
    @Test fun compactHostDoesNotGainLetterboxingFromAnArtificialHeightFloor() {
        val size = WidgetRenderSize.forHost(320f, 110f, 3f)
        assertEquals(110, size.height)
        assertEquals(320, size.width)
    }
    @Test fun phoneWidgetsKeepNativePixelsInsteadOfTheOldDensityCap() {
        for ((host, expected) in listOf((320f to 220f) to (960 to 660),
                (360f to 220f) to (1080 to 660), (420f to 240f) to (1260 to 720))) {
            val size = WidgetRenderSize.forHost(host.first, host.second, 3f)
            assertEquals(3f, size.density, 0f)
            assertEquals(expected.first, size.pixelWidth)
            assertEquals(expected.second, size.pixelHeight)
        }
        assertEquals(4f, WidgetRenderSize.forHost(360f, 220f, 4f).density, 0f)
    }
    @Test fun fourNormalResponsiveSizesFitAtNativeDensityTogether() {
        val sizes = WidgetRenderSize.forHosts(listOf(320f to 220f, 360f to 220f, 420f to 240f,
            600f to 110f), 3f, 1080, 2340)
        assertEquals(4, sizes.size)
        assertTrue(sizes.all { it.density == 3f })
        assertTrue(sizes.sumOf { it.pixelCount } <= WidgetRenderSize.bitmapPixelBudget(1080, 2340))
    }
    @Test fun allResponsiveBitmapsShareTheFrameworkBudgetForAnExtremeResize() {
        val sizes = WidgetRenderSize.forHosts(List(4) { 2000f to 2000f }, 5f, 1080, 2340)
        assertTrue(sizes.all { it.width == 600 && it.height == 420 })
        assertEquals(1, sizes.map { it.density }.distinct().size)
        val budget = WidgetRenderSize.bitmapPixelBudget(1080, 2340)
        assertTrue(sizes.sumOf { it.pixelCount } <= budget)
        assertTrue(sizes.first().density < 5f)
        // Pick the best whole densityDpi that can be laid out without another bitmap rescale.
        val next = sizes.first().copy(density = sizes.first().density + 1 / 160f)
        assertTrue(next.pixelCount * sizes.size > budget)
    }
    @Test fun aSingleLargePreviewHasBoundedBitmapMemory() {
        val size = WidgetRenderSize.forHost(2000f, 2000f, 5f)
        assertTrue(size.pixelCount <= 4_000_000L)
        assertTrue(size.density > 2.5f)
    }
    @Test fun exactNativeBudgetDoesNotCauseUnnecessaryDownsampling() {
        val size = WidgetRenderSize.forHost(360f, 220f, 3f, 1080L * 660)
        assertEquals(3f, size.density, 0f)
        assertEquals(1080L * 660, size.pixelCount)
    }
    @Test fun bitmapBudgetKeepsTenPercentHeadroomFromTheFrameworkLimit() {
        assertEquals(3_411_720L, WidgetRenderSize.bitmapPixelBudget(1080, 2340))
        assertEquals(2_799_360L, WidgetRenderSize.bitmapPixelBudget(0, -1))
        assertTrue(WidgetRenderSize.forHosts(emptyList(), 3f, 1080, 2340).isEmpty())
    }
    @Test fun missingAndMalformedHostMetricsUseSafeBounds() {
        val invalid = WidgetRenderSize.forHost(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
        assertEquals(WidgetRenderSize(320, 220, 2f), invalid)
        val negative = WidgetRenderSize.forHost(-1f, -1f, -1f)
        assertEquals(WidgetRenderSize(220, 110, 0.5f), negative)
    }
}
