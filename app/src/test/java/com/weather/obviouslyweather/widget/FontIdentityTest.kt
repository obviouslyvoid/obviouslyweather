// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class FontIdentityTest {
    private fun font(family: String, typographic: Boolean = true): ByteArray {
        val text = family.toByteArray(Charsets.UTF_16BE)
        return ByteBuffer.allocate(46 + text.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x00010000); putShort(1); putShort(0); putShort(0); putShort(0)
            putInt(0x6e616d65); putInt(0); putInt(28); putInt(18 + text.size)
            putShort(0); putShort(1); putShort(18)
            putShort(3); putShort(1); putShort(0x0409)
            putShort((if (typographic) 16 else 1).toShort()); putShort(text.size.toShort()); putShort(0)
            put(text)
        }.array()
    }

    @Test fun acceptsOnlyOneUiSansFamilyNames() {
        for (name in listOf("One UI Sans", "One UI Sans VF", "One UI Sans APP VF", "OneUISansKR-VF")) {
            assertTrue(name, FontIdentity.read(ByteBuffer.wrap(font(name)))!!.isOneUiSans)
        }
        for (name in listOf("SamsungOne", "Roboto", "Inter", "One UI Sans Fake", "NotOneUISans")) {
            assertFalse(name, FontIdentity.read(ByteBuffer.wrap(font(name)))!!.isOneUiSans)
        }
    }

    @Test fun readsLegacyFamilyAndPreservesCallerBuffer() {
        val buffer = ByteBuffer.wrap(font("Inter", typographic = false)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(10)
        assertEquals("Inter", FontIdentity.read(buffer)?.displayName)
        assertEquals(10, buffer.position())
        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order())
    }

    @Test fun collectionChecksActualFaceIndex() {
        val first = font("Roboto")
        val second = font("One UI Sans VF")
        val bytes = ByteBuffer.allocate(20 + first.size + second.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x74746366); putInt(0x00010000); putInt(2); putInt(20); putInt(20 + first.size)
            put(first); put(second)
            putInt(20 + 20, 20 + 28)
            putInt(20 + first.size + 20, 20 + first.size + 28)
        }.array()
        assertFalse(FontIdentity.read(ByteBuffer.wrap(bytes), 0)!!.isOneUiSans)
        assertTrue(FontIdentity.read(ByteBuffer.wrap(bytes), 1)!!.isOneUiSans)
        assertNull(FontIdentity.read(ByteBuffer.wrap(bytes), 2))
        assertNull(FontIdentity.read(ByteBuffer.wrap(bytes), -1))
    }

    @Test fun malformedTablesAndTruncatedFilesAreRejected() {
        val valid = font("One UI Sans VF")
        for (length in 0 until valid.size) assertNull(FontIdentity.read(ByteBuffer.wrap(valid.copyOf(length))))
        val broken = valid.copyOf()
        ByteBuffer.wrap(broken).order(ByteOrder.BIG_ENDIAN).putInt(20, -1)
        assertNull(FontIdentity.read(ByteBuffer.wrap(broken)))
        val random = Random(235)
        repeat(1000) { assertNull(FontIdentity.read(ByteBuffer.wrap(random.nextBytes(random.nextInt(0, 256))))) }
    }

    @Test fun namesAreBoundedAndControlCharactersRemoved() {
        assertEquals("Importedname", FontIdentity.read(ByteBuffer.wrap(font("Imported\nname")))?.displayName)
        assertNull(FontIdentity.read(ByteBuffer.wrap(font("a".repeat(300)))))
    }
}
