// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

data class BackTransform(val scale: Float, val translationDp: Float, val alpha: Float, val cornerDp: Float)

/** Phase 0..1 follows the gesture; phase 1..2 finishes dismissal without another screen transition. */
fun predictiveTransform(progress: Float, fromRight: Boolean): BackTransform {
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 2f) else 0f
    val gesture = safeProgress.coerceAtMost(1f)
    val dismissal = (safeProgress - 1f).coerceAtLeast(0f)
    val direction = if (fromRight) -1f else 1f
    return BackTransform(
        scale = 1f - 0.035f * gesture,
        translationDp = direction * (18f * gesture + 36f * dismissal),
        alpha = 1f - dismissal,
        cornerDp = 24f * gesture
    )
}
