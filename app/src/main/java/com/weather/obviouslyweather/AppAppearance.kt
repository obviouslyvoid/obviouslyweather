// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.app.UiModeManager
import android.content.Context

internal fun applyPlatformAppearance(context: Context, appearance: Appearance) {
    val mode = when (appearance) {
        Appearance.LIGHT -> UiModeManager.MODE_NIGHT_NO
        Appearance.DARK -> UiModeManager.MODE_NIGHT_YES
        // AUTO clears the app override so Android inherits the system configuration.
        Appearance.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
    }
    context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
}
