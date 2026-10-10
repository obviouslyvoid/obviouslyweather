// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class FontIdentity(val families: Set<String>) {
    val isOneUiSans: Boolean get() = families.any {
        it.lowercase().replace(" ", "").replace("-", "") in setOf(
            "oneuisans", "oneuisansvf", "oneuisansappvf", "oneuisanskrvf"
        )
    }
    val displayName: String get() = families.firstOrNull()?.filterNot(Char::isISOControl)?.take(80) ?: "Imported font"

    companion object {
        // Read family records, not filenames or the device manufacturer. Renamed fonts must not pass.
        fun read(source: ByteBuffer, collectionIndex: Int = 0): FontIdentity? = runCatching {
            val buffer = source.duplicate().order(ByteOrder.BIG_ENDIAN)
            val size = buffer.limit().toLong()
            fun range(offset: Long, length: Long) {
                require(offset >= 0 && length >= 0 && offset <= size - length)
            }
            fun u16(offset: Long): Int { range(offset, 2); return buffer.getShort(offset.toInt()).toInt() and 0xffff }
            fun u32(offset: Long): Long { range(offset, 4); return buffer.getInt(offset.toInt()).toLong() and 0xffffffffL }
            val header = if (u32(0) == 0x74746366L) {
                val count = u32(8)
                require(collectionIndex >= 0 && collectionIndex.toLong() < count && count <= 256)
                u32(12L + collectionIndex * 4L)
            } else { require(collectionIndex == 0); 0L }
            require(u32(header) in setOf(0x00010000L, 0x4f54544fL))
            val tableCount = u16(header + 4)
            require(tableCount in 1..256)
            range(header + 12, tableCount * 16L)
            val record = (0 until tableCount).map { header + 12 + it * 16L }
                .firstOrNull { u32(it) == 0x6e616d65L } ?: return null
            val names = u32(record + 8)
            val length = u32(record + 12)
            range(names, length)
            require(length >= 6 && u16(names) in 0..1)
            val count = u16(names + 2)
            val strings = u16(names + 4).toLong()
            require(count <= 4096 && 6L + count * 12L <= length && strings >= 6L + count * 12L)
            fun families(id: Int): Set<String> = (0 until count).mapNotNull { index ->
                val name = names + 6 + index * 12L
                val platform = u16(name)
                if (u16(name + 6) != id || platform !in setOf(0, 3)) return@mapNotNull null
                val bytes = u16(name + 8)
                val offset = strings + u16(name + 10)
                require(bytes <= 512 && bytes % 2 == 0 && offset <= length - bytes)
                val encoded = ByteArray(bytes)
                val reader = buffer.duplicate()
                reader.position((names + offset).toInt())
                reader.get(encoded)
                String(encoded, Charsets.UTF_16BE).trim().takeIf { it.isNotEmpty() }
            }.toSet()
            val families = families(16).ifEmpty { families(1) }
            FontIdentity(families).takeIf { families.isNotEmpty() }
        }.getOrNull()
    }
}
