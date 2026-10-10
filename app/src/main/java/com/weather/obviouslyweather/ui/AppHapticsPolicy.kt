// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import com.weather.obviouslyweather.HapticStrength
import com.weather.obviouslyweather.InteractionPreferences

internal enum class AppHapticEffect { LIGHT_TICK, STRONG_CLICK }

internal fun interactionHapticEffect(preferences: InteractionPreferences): AppHapticEffect? =
    if (!preferences.enabled) null
    else when (preferences.strength) {
        HapticStrength.LIGHT -> AppHapticEffect.LIGHT_TICK
        HapticStrength.STRONG -> AppHapticEffect.STRONG_CLICK
    }

class AppHapticFeedback internal constructor(
    private val preferences: () -> InteractionPreferences = { InteractionPreferences(enabled = false) },
    private val feedback: (AppHapticEffect) -> Unit = {}
) {
    /** Override with the new preference when previewing a settings change. */
    fun perform(preferencesOverride: InteractionPreferences? = null) {
        interactionHapticEffect(preferencesOverride ?: preferences())?.let(feedback)
    }
}
