// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.weather.obviouslyweather.Appearance
import kotlinx.coroutines.CancellationException

internal val LightColors = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8), onPrimaryContainer = Color.Black,
    inversePrimary = Color.White,
    secondary = Color.Black, onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAEAEA), onSecondaryContainer = Color.Black,
    tertiary = Color.Black, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAEAEA), onTertiaryContainer = Color.Black,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF606060),
    surfaceTint = Color.Transparent, inverseSurface = Color(0xFF222222), inverseOnSurface = Color.White,
    outline = Color(0xFF777777), outlineVariant = Color(0xFFDDDDDD),
    error = Color.Black, onError = Color.White,
    errorContainer = Color(0xFFE6E6E6), onErrorContainer = Color.Black, scrim = Color.Black,
    surfaceDim = Color(0xFFDDDDDD), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF4F4F4), surfaceContainerHigh = Color(0xFFEEEEEE),
    surfaceContainerHighest = Color(0xFFE4E4E4)
)
internal val DarkColors = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF292929), onPrimaryContainer = Color.White,
    inversePrimary = Color.Black,
    secondary = Color.White, onSecondary = Color.Black,
    secondaryContainer = Color(0xFF292929), onSecondaryContainer = Color.White,
    tertiary = Color.White, onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF292929), onTertiaryContainer = Color.White,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF171717), onSurfaceVariant = Color(0xFFAAAAAA),
    surfaceTint = Color.Transparent, inverseSurface = Color(0xFFEAEAEA), inverseOnSurface = Color.Black,
    outline = Color(0xFF888888), outlineVariant = Color(0xFF333333),
    error = Color.White, onError = Color.Black,
    errorContainer = Color(0xFF292929), onErrorContainer = Color.White, scrim = Color.Black,
    surfaceDim = Color.Black, surfaceBright = Color(0xFF353535),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF101010),
    surfaceContainer = Color(0xFF171717), surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF292929)
)

@Composable
fun ObviouslyweatherTheme(appearance: Appearance = Appearance.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    var displayedDark by remember { mutableStateOf(dark) }
    var previousFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val oldFrameOpacity = remember { Animatable(0f) }
    val screenLayer = rememberGraphicsLayer()

    LaunchedEffect(dark) {
        if (dark != displayedDark) {
            // Capture only on a theme change. Including the overlay makes interrupted fades continuous.
            val captured = try {
                screenLayer.toImageBitmap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null // A not-yet-drawn preview can switch directly without blocking the app.
            }
            oldFrameOpacity.snapTo(1f)
            previousFrame = captured
            displayedDark = dark
        }
        if (previousFrame != null) {
            oldFrameOpacity.animateTo(0f, tween(350))
            previousFrame = null
        }
    }
    MaterialTheme(colorScheme = if (displayedDark) DarkColors else LightColors, typography = Typography) {
        Box(Modifier.fillMaxSize().drawWithContent {
            screenLayer.record { this@drawWithContent.drawContent() }
            drawLayer(screenLayer)
        }) {
            content()
            previousFrame?.let { frame ->
                // Alpha is read in the graphics phase, so animation frames do not recompose the UI.
                Image(frame, contentDescription = null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = oldFrameOpacity.value }
                        .clearAndSetSemantics {})
            }
        }
    }
}
