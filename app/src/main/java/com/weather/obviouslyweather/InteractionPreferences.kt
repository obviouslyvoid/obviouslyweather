// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

enum class HapticStrength { LIGHT, STRONG }

data class InteractionPreferences(
    val enabled: Boolean = true,
    val strength: HapticStrength = HapticStrength.LIGHT
)
