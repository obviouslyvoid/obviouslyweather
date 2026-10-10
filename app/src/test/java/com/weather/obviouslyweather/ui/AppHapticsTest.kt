// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import com.weather.obviouslyweather.HapticStrength
import com.weather.obviouslyweather.InteractionPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppHapticsTest {
    @Test fun defaultPreferencesUseALightTick() {
        assertEquals(AppHapticEffect.LIGHT_TICK, interactionHapticEffect(InteractionPreferences()))
    }

    @Test fun disabledPreferencesSuppressEitherStrength() {
        HapticStrength.entries.forEach { strength ->
            assertNull(interactionHapticEffect(InteractionPreferences(enabled = false, strength = strength)))
        }
    }

    @Test fun turningHapticsOffIsSilentImmediately() {
        val emitted = mutableListOf<AppHapticEffect>()
        val feedback = AppHapticFeedback({ InteractionPreferences() }, emitted::add)
        feedback.perform(InteractionPreferences(enabled = false))
        assertEquals(emptyList<AppHapticEffect>(), emitted)
    }

    @Test fun enablingOrChangingStrengthPreviewsTheNewChoice() {
        val emitted = mutableListOf<AppHapticEffect>()
        val feedback = AppHapticFeedback({ InteractionPreferences(enabled = false) }, emitted::add)
        feedback.perform(InteractionPreferences())
        feedback.perform(InteractionPreferences(strength = HapticStrength.STRONG))
        assertEquals(listOf(AppHapticEffect.LIGHT_TICK, AppHapticEffect.STRONG_CLICK), emitted)
    }

    @Test fun buttonFeedbackReadsLatestPreferencesRatherThanACapturedChoice() {
        var preferences = InteractionPreferences()
        val emitted = mutableListOf<AppHapticEffect>()
        val feedback = AppHapticFeedback({ preferences }, emitted::add)
        feedback.perform()
        preferences = preferences.copy(strength = HapticStrength.STRONG)
        feedback.perform()
        preferences = preferences.copy(enabled = false)
        feedback.perform()
        assertEquals(listOf(AppHapticEffect.LIGHT_TICK, AppHapticEffect.STRONG_CLICK), emitted)
    }

    @Test fun AFeedbackControllerNeverEmitsWithoutAnInteraction() {
        val emitted = mutableListOf<AppHapticEffect>()
        AppHapticFeedback({ InteractionPreferences() }, emitted::add)
        assertEquals(emptyList<AppHapticEffect>(), emitted)
    }
}
