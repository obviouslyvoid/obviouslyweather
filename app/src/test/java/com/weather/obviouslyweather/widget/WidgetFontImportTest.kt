// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.weather.obviouslyweather.R
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetFontImportTest {
    class SlowProvider(val opened: CountDownLatch, val release: CountDownLatch, val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            opened.countDown()
            release.await()
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        override fun getType(uri: Uri) = "font/ttf"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Test fun blockedProviderDoesNotBlockReadersAndCancelledImportsCannotReplaceTheFont() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "test-font.ttf")
        app.resources.openRawResource(R.font.widget_sans).use { source -> file.outputStream().use { source.copyTo(it) } }
        WidgetFonts.reset(app)
        val previous = WidgetFonts.current(app)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = SlowProvider(opened, release, file)
        provider.attachInfo(app, ProviderInfo().apply { authority = "test.slow.font" })
        ShadowContentResolver.registerProviderInternal("test.slow.font", provider)
        val uri = Uri.parse("content://test.slow.font/font")
        val importer = async(Dispatchers.Default) { WidgetFonts.import(app, uri) }
        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            assertEquals(previous, withTimeout(1000) { withContext(Dispatchers.IO) { WidgetFonts.current(app) } })
            withTimeout(1000) { importer.cancelAndJoin() }
            assertEquals(previous, WidgetFonts.current(app))
        } finally { release.countDown(); importer.cancelAndJoin() }
        WidgetFonts.import(app, Uri.fromFile(file))
        assertTrue(WidgetFonts.current(app).imported)
        val imported = WidgetFonts.current(app)
        val invalid = File(app.cacheDir, "invalid-font.ttf").apply { writeText("not a font") }
        assertTrue(runCatching { WidgetFonts.import(app, Uri.fromFile(invalid)) }.isFailure)
        assertEquals(imported, WidgetFonts.current(app))
    }

    @Test fun resetWinsOverAnEarlierImportThatFinishesLater() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "test-font.ttf")
        app.resources.openRawResource(R.font.widget_sans).use { source -> file.outputStream().use { source.copyTo(it) } }
        WidgetFonts.reset(app)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = SlowProvider(opened, release, file)
        provider.attachInfo(app, ProviderInfo().apply { authority = "test.slow.font" })
        ShadowContentResolver.registerProviderInternal("test.slow.font", provider)
        val importer = async(Dispatchers.Default) { runCatching { WidgetFonts.import(app, Uri.parse("content://test.slow.font/font")) } }
        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            WidgetFonts.reset(app)
            release.countDown()
            assertTrue(withTimeout(3000) { importer.await() }.isFailure)
            assertFalse(WidgetFonts.current(app).imported)
        } finally { release.countDown(); importer.cancelAndJoin() }
    }
}
