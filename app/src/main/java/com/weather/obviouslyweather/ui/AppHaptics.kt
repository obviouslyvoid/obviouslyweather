// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import com.weather.obviouslyweather.InteractionPreferences

// Widget configuration has no provider, so shared controls remain silent there.
val LocalAppHaptic = staticCompositionLocalOf { AppHapticFeedback() }

@Composable
fun WithAppHaptics(preferences: InteractionPreferences, content: @Composable () -> Unit) {
    val currentPreferences = rememberUpdatedState(preferences)
    val view = LocalView.current
    val haptics = remember(view) {
        AppHapticFeedback(
            preferences = { currentPreferences.value },
            feedback = { effect ->
                view.performHapticFeedback(
                    when (effect) {
                        AppHapticEffect.LIGHT_TICK -> HapticFeedbackConstants.CLOCK_TICK
                        AppHapticEffect.STRONG_CLICK -> HapticFeedbackConstants.VIRTUAL_KEY
                    }
                )
            }
        )
    }
    CompositionLocalProvider(LocalAppHaptic provides haptics, content = content)
}
