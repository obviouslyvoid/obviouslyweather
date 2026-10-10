// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

internal enum class LicenseDocument(val title: String, val file: String, val markdown: Boolean = false) {
    NOTICES("Notices", "THIRD_PARTY_NOTICES.md", true),
    GPL("GPL", "GPL-3.0.txt"),
    INTER("Inter", "inter-OFL.txt"),
    GOOGLE_SANS("Google Sans", "google-sans-OFL.txt"),
    TRADEMARKS("Trademarks", "google-sans-trademarks.md", true),
    METEOCONS("Meteocons", "meteocons-MIT.txt"),
    PHOSPHOR("Phosphor", "phosphor-MIT.txt"),
    APACHE("Apache", "Apache-2.0.txt");

    fun linkedDocument(target: String): LicenseDocument? =
        if (this == TRADEMARKS && target == "OFL.txt") GOOGLE_SANS else null
}

internal sealed interface LicenseBlock {
    data class Heading(val text: String) : LicenseBlock
    data class Paragraph(val text: String) : LicenseBlock
    data class Component(val name: String, val license: String, val source: String) : LicenseBlock
}

internal data class LicenseSpan(val text: String, val target: String? = null)

internal object LicenseContent {
    fun blocks(source: String, markdown: Boolean): List<LicenseBlock> {
        if (!markdown) return listOf(LicenseBlock.Paragraph(source))
        val lines = source.lines()
        val blocks = mutableListOf<LicenseBlock>()
        val paragraph = mutableListOf<String>()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += LicenseBlock.Paragraph(paragraph.joinToString(" "))
                paragraph.clear()
            }
        }
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            when {
                line.isEmpty() -> flushParagraph()
                line.startsWith("# ") -> {
                    flushParagraph()
                    blocks += LicenseBlock.Heading(line.removePrefix("# "))
                }
                index + 1 < lines.size && lines[index + 1].trim().matches(Regex("=+")) -> {
                    flushParagraph()
                    blocks += LicenseBlock.Heading(line)
                    index++
                }
                line == "| Component | License | Source |" -> {
                    flushParagraph()
                    index += 2
                    while (index < lines.size && lines[index].trim().startsWith("|")) {
                        val cells = lines[index].trim().removeSurrounding("|").split('|').map(String::trim)
                        if (cells.size == 3) blocks += LicenseBlock.Component(cells[0], cells[1], cells[2])
                        index++
                    }
                    continue
                }
                else -> paragraph += line
            }
            index++
        }
        flushParagraph()
        return blocks
    }

    private val inlinePattern = Regex("\\[([^\\]]+)\\]\\(([^\\s)]+)\\)|`([^`]+)`|https?://[^\\s<>]+")
    private val urlPattern = Regex("https?://[^\\s<>]+")

    fun spans(text: String, markdown: Boolean): List<LicenseSpan> {
        val spans = mutableListOf<LicenseSpan>()
        var end = 0
        for (match in (if (markdown) inlinePattern else urlPattern).findAll(text)) {
            if (match.range.first > end) spans += LicenseSpan(text.substring(end, match.range.first))
            when {
                markdown && match.groups[1] != null -> spans += LicenseSpan(match.groupValues[1], match.groupValues[2])
                markdown && match.groups[3] != null -> spans += LicenseSpan(match.groupValues[3])
                else -> {
                    val raw = match.value
                    var url = raw.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'')
                    while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1)
                    spans += LicenseSpan(url, url)
                    if (url.length < raw.length) spans += LicenseSpan(raw.substring(url.length))
                }
            }
            end = match.range.last + 1
        }
        if (end < text.length) spans += LicenseSpan(text.substring(end))
        return spans
    }
}
