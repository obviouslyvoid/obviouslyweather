// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonochromeThemeTest {
    private fun allColors(scheme: ColorScheme): List<Color> = with(scheme) {
        listOf(primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary,
            secondary, onSecondary, secondaryContainer, onSecondaryContainer,
            tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
            background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant,
            surfaceTint, inverseSurface, inverseOnSurface, outline, outlineVariant,
            error, onError, errorContainer, onErrorContainer, scrim,
            surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow,
            surfaceContainer, surfaceContainerHigh, surfaceContainerHighest)
    }

    @Test fun everyLightAndDarkRoleIsMonochromeIncludingErrorsAndElevatedSurfaces() {
        listOf(LightColors, DarkColors).forEach { scheme ->
            allColors(scheme).forEach { color ->
                assertEquals("A theme role must not introduce a color tint", color.red, color.green, 0.00001f)
                assertEquals("A theme role must not introduce a color tint", color.green, color.blue, 0.00001f)
            }
        }
    }

    @Test fun lightAndDarkBackgroundsAndPrimaryControlsHaveFullContrast() {
        assertEquals(Color.White, LightColors.background)
        assertEquals(Color.Black, DarkColors.background)
        assertEquals(Color.Black, LightColors.onBackground)
        assertEquals(Color.White, DarkColors.onBackground)
        listOf(LightColors, DarkColors).forEach { scheme ->
            assertTrue(scheme.primary != scheme.onPrimary)
            assertTrue(scheme.surface != scheme.onSurface)
        }
    }
}
