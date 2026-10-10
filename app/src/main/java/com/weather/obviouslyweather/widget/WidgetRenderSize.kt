// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import kotlin.math.roundToInt

/** Render at the host's native pixel density unless the complete update exceeds its bitmap budget. */
data class WidgetRenderSize(val width: Int, val height: Int, val density: Float) {
    val pixelWidth: Int get() = (width * density).roundToInt().coerceAtLeast(1)
    val pixelHeight: Int get() = (height * density).roundToInt().coerceAtLeast(1)
    val pixelCount: Long get() = pixelWidth.toLong() * pixelHeight
    companion object {
        @JvmStatic
        @JvmOverloads
        fun forHost(width: Float, height: Float, density: Float, pixelBudget: Long = 4_000_000L): WidgetRenderSize =
            allocate(listOf(width to height), density, pixelBudget).single()

        /** Android counts all responsive bitmaps together and allows 1.5 screenfuls.
         * Keep ten percent spare for framework accounting; normal phone sizes retain every pixel. */
        @JvmStatic
        fun forHosts(hosts: List<Pair<Float, Float>>, density: Float,
                     screenPixelWidth: Int, screenPixelHeight: Int): List<WidgetRenderSize> =
            allocate(hosts, density, bitmapPixelBudget(screenPixelWidth, screenPixelHeight))

        @JvmStatic
        fun bitmapPixelBudget(screenPixelWidth: Int, screenPixelHeight: Int): Long {
            val width = screenPixelWidth.takeIf { it > 0 } ?: 1080
            val height = screenPixelHeight.takeIf { it > 0 } ?: 1920
            return (width.toLong() * height * 1.35).toLong().coerceAtLeast(1)
        }

        private fun allocate(hosts: List<Pair<Float, Float>>, density: Float, pixelBudget: Long): List<WidgetRenderSize> {
            val normalized = hosts.map { (width, height) -> dimensions(width, height) }
            if (normalized.isEmpty()) return emptyList()
            val nativeDpi = ((if (density.isFinite()) density else 2f).coerceIn(0.5f, 6f) * 160).roundToInt()
            fun atDpi(dpi: Int) = normalized.map { (width, height) -> WidgetRenderSize(width, height, dpi / 160f) }
            require(pixelBudget >= atDpi(1).sumOf { it.pixelCount }) { "Widget bitmap budget is too small" }
            val native = atDpi(nativeDpi)
            if (native.sumOf { it.pixelCount } <= pixelBudget) return native
            // Use the same density for every responsive variant. Integer densityDpi matches
            // Android's layout resource context, avoiding a second full-widget Canvas rescale.
            var low = 1
            var high = nativeDpi
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (atDpi(mid).sumOf { it.pixelCount } <= pixelBudget) low = mid else high = mid - 1
            }
            return atDpi(low)
        }

        private fun dimensions(width: Float, height: Float): Pair<Int, Int> {
            val safeWidth = (if (width.isFinite()) width else 320f).roundToInt().coerceIn(220, 600)
            val safeHeight = (if (height.isFinite()) height else 220f).roundToInt().coerceIn(110, 420)
            return safeWidth to safeHeight
        }
    }
}
