// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.net.Uri
import android.os.CancellationSignal
import android.util.AtomicFile
import com.weather.obviouslyweather.R
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WidgetFont(val typeface: Typeface, val label: String, val imported: Boolean)

object WidgetFonts {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private var selected: WidgetFont? = null
    private var automatic: WidgetFont? = null
    private var importGeneration = 0L
    private fun importedFile(context: Context) = File(context.noBackupFilesDir, "widget-font.ttf")

    @Synchronized
    fun current(context: Context): WidgetFont {
        selected?.let { return it }
        val imported = runCatching {
            val file = importedFile(context)
            require(file.length() in 1..MAX_BYTES.toLong())
            val bytes = AtomicFile(file).readFully()
            font(bytes, imported = true)
        }.getOrNull()
        return (imported ?: automatic(context)).also { selected = it }
    }

    suspend fun import(context: Context, uri: Uri) = withTimeout(20_000) {
        val generation = synchronized(this) { ++importGeneration }
        val bytes = readBytes(context, uri)
        require(bytes.size in 1..MAX_BYTES) { "Choose a TTF or OTF font smaller than 8 MB." }
        withContext(Dispatchers.IO) {
            val font = font(bytes, imported = true)
            ensureActive()
            synchronized(this@WidgetFonts) {
                check(generation == importGeneration) { "Font import was cancelled." }
                val file = AtomicFile(importedFile(context))
                val output = file.startWrite()
                try { output.write(bytes); file.finishWrite(output) }
                catch (error: Exception) { file.failWrite(output); throw error }
                selected = font
                invalidatePreview(context)
            }
        }
    }

    private suspend fun readBytes(context: Context, uri: Uri): ByteArray = suspendCancellableCoroutine { result ->
        val cancellation = CancellationSignal()
        val descriptor = AtomicReference<AssetFileDescriptor?>()
        val reader = CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
            try {
                val bytes = runInterruptible {
                    requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r", cancellation)).use { file ->
                        descriptor.set(file)
                        if (!result.isActive) throw CancellationException("Font import was cancelled.")
                        file.createInputStream().use { it.readNBytes(MAX_BYTES + 1) }
                    }
                }
                result.resume(bytes)
            } catch (error: Exception) {
                if (result.isActive) result.resumeWithException(error)
            } finally { descriptor.set(null) }
        }
        result.invokeOnCancellation {
            runCatching { cancellation.cancel() }
            reader.cancel()
            runCatching { descriptor.getAndSet(null)?.close() }
        }
        reader.start()
    }

    @Synchronized
    fun reset(context: Context) {
        importGeneration++
        AtomicFile(importedFile(context)).delete()
        selected = automatic(context)
        invalidatePreview(context)
    }

    private fun invalidatePreview(context: Context) {
        val preferences = context.getSharedPreferences("weather_widget_font", Context.MODE_PRIVATE)
        preferences.edit().putLong("revision", preferences.getLong("revision", 0) + 1).apply()
    }

    fun revision(context: Context): Long =
        context.getSharedPreferences("weather_widget_font", Context.MODE_PRIVATE).getLong("revision", 0)

    private fun font(bytes: ByteArray, imported: Boolean): WidgetFont {
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
        val identity = requireNotNull(FontIdentity.read(buffer)) { "This file is not a supported TTF or OTF font." }
        val typeface = typeface(buffer)
        val paint = Paint().apply { this.typeface = typeface }
        require(listOf("0", "9", "%", "°", "A", "M").all(paint::hasGlyph)) { "This font is missing weather symbols or numbers." }
        return WidgetFont(typeface, identity.displayName, imported)
    }

    private fun automatic(context: Context): WidgetFont {
        automatic?.let { return it }
        val system = runCatching {
            SystemFonts.getAvailableFonts().sortedBy { it.file?.name.orEmpty() }.firstNotNullOfOrNull { available ->
                val identity = FontIdentity.read(available.buffer, available.ttcIndex)
                if (identity?.isOneUiSans != true || available.style.slant != 0) return@firstNotNullOfOrNull null
                runCatching {
                    WidgetFont(typeface(available.buffer, available.ttcIndex), "One UI Sans (system)", imported = false)
                }.getOrNull()
            }
        }.getOrNull()
        return (system ?: WidgetFont(context.resources.getFont(R.font.widget_sans), "Inter (bundled)", imported = false))
            .also { automatic = it }
    }

    private fun typeface(buffer: ByteBuffer, index: Int = 0): Typeface {
        fun face(weight: Int) = Font.Builder(buffer).setTtcIndex(index).setWeight(weight)
            .setSlant(FontStyle.FONT_SLANT_UPRIGHT).setFontVariationSettings("'wght' $weight").build()
        val family = FontFamily.Builder(face(400)).addFont(face(500)).build()
        return Typeface.CustomFallbackBuilder(family).setStyle(FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT)).build()
    }
}
