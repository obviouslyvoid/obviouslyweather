// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Monotonic time keeps rapid responses legible without delaying the weather data itself. */
internal fun refreshSettleDelay(startedAt: Long, completedAt: Long): Long =
    maxOf(250L, 650L - (completedAt - startedAt).coerceAtLeast(0L))

@Composable
internal fun RefreshButton(loading: Boolean, onRefresh: () -> Unit) {
    var showingProgress by remember { mutableStateOf(loading) }
    var startedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(loading) {
        if (loading) {
            startedAt = SystemClock.elapsedRealtime()
            showingProgress = true
        } else if (showingProgress) {
            delay(refreshSettleDelay(startedAt, SystemClock.elapsedRealtime()))
            showingProgress = false
        }
    }
    val busy = loading || showingProgress
    val palette = MaterialTheme.colorScheme
    FilledTonalIconButton(onClick = onRefresh, enabled = !busy,
        modifier = Modifier.size(48.dp).semantics { contentDescription = if (busy) "Refreshing weather" else "Refresh weather" },
        shape = RoundedCornerShape(18.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            disabledContainerColor = palette.secondaryContainer,
            disabledContentColor = palette.onSecondaryContainer)) {
        Crossfade(busy, animationSpec = tween(240), label = "Refresh button") { spinning ->
            if (spinning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                color = palette.onSurfaceVariant)
            else ActionGlyph(ActionSymbol.REFRESH)
        }
    }
}
