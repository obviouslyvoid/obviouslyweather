// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.weather.obviouslyweather.widget.WeatherWidgets
import com.weather.obviouslyweather.widget.WidgetFont
import com.weather.obviouslyweather.widget.WidgetFonts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WidgetFontSetting(enabled: Boolean) {
    val context = LocalContext.current.applicationContext
    val haptics = LocalAppHaptic.current
    val scope = rememberCoroutineScope()
    var font by remember { mutableStateOf<WidgetFont?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { font = withContext(Dispatchers.IO) { WidgetFonts.current(context) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            try {
                font = withContext(Dispatchers.IO) {
                    WidgetFonts.import(context, uri)
                    WidgetFonts.current(context)
                }
                WeatherWidgets.requestUpdate(context)
            } catch (_: TimeoutCancellationException) { error = "The font provider took too long. Try a file saved on this device." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not import this font. Choose a TTF or OTF file under 8 MB." }
            finally { busy = false }
        }
    }
    Text(font?.let { if (it.imported) "${it.label} · imported" else it.label } ?: "Checking widget font…",
        style = MaterialTheme.typography.bodyLarge)
    Text("Automatic uses One UI Sans on Samsung devices when available, and Inter otherwise. Imported fonts apply to all widgets.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = enabled && !busy, onClick = {
            haptics.perform()
            picker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-opentype", "application/octet-stream"))
        }) { Text(if (busy) "Applying…" else "Import font") }
        if (font?.imported == true) TextButton(enabled = enabled && !busy, onClick = {
            haptics.perform()
            scope.launch {
                busy = true
                error = null
                try {
                    font = withContext(Dispatchers.IO) { WidgetFonts.reset(context); WidgetFonts.current(context) }
                    WeatherWidgets.requestUpdate(context)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Could not change the widget font. Please try again." }
                finally { busy = false }
            }
        }) { Text("Use automatic") }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
