// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LicenseContentTest {
    private fun asset(document: LicenseDocument) = File("src/main/assets/licenses/${document.file}").readText()

    @Test fun noticesShowEveryComponentAndKeepAllSourceLinks() {
        val blocks = LicenseContent.blocks(asset(LicenseDocument.NOTICES), markdown = true)
        val components = blocks.filterIsInstance<LicenseBlock.Component>()
        assertEquals(8, components.size)
        assertEquals("Inter, bundled widget font", components.first().name)
        assertEquals("SIL Open Font License 1.1", components.first().license)
        assertEquals("Gradle wrapper", components.last().name)
        assertFalse(blocks.filterIsInstance<LicenseBlock.Paragraph>().any { it.text.startsWith('|') })
        val links = components.flatMap { LicenseContent.spans(it.source, markdown = true) }.mapNotNull { it.target }
        assertEquals(11, links.size)
        assertTrue(links.contains("https://github.com/rsms/inter"))
        assertTrue(links.contains("https://github.com/google/fonts/tree/main/ofl/googlesans"))
        assertTrue(links.contains("https://github.com/square/okio"))
        val weather = LicenseContent.spans(blocks.filterIsInstance<LicenseBlock.Paragraph>().last().text, markdown = true)
        assertEquals("https://openweathermap.org/", weather.single { it.target != null }.target)
        assertTrue(weather.joinToString("") { it.text }.startsWith("Weather data is provided by OpenWeather."))
    }

    @Test fun plainLicensesRemainVerbatimWithClickableWebAddresses() {
        LicenseDocument.entries.filterNot { it.markdown }.forEach { document ->
            val source = asset(document)
            assertEquals(listOf(LicenseBlock.Paragraph(source)), LicenseContent.blocks(source, markdown = false))
            val spans = LicenseContent.spans(source, markdown = false)
            assertEquals(document.title, source, spans.joinToString("") { it.text })
            if (document in listOf(LicenseDocument.GPL, LicenseDocument.INTER, LicenseDocument.GOOGLE_SANS, LicenseDocument.APACHE)) {
                assertTrue(document.title, spans.any { it.target?.startsWith("http") == true })
            }
        }
    }

    @Test fun urlsExcludeSurroundingPunctuationWithoutChangingText() {
        val source = "See (https://example.org/font), <http://example.org/license>. Also https://example.org/a_(b)."
        val spans = LicenseContent.spans(source, markdown = false)
        assertEquals(source, spans.joinToString("") { it.text })
        assertEquals(listOf("https://example.org/font", "http://example.org/license", "https://example.org/a_(b)"),
            spans.mapNotNull { it.target })
    }

    @Test fun trademarkLicenseLinkOpensTheBundledGoogleSansLicense() {
        val blocks = LicenseContent.blocks(asset(LicenseDocument.TRADEMARKS), markdown = true)
        assertEquals(LicenseBlock.Heading("Google Sans Trademarks"), blocks.first())
        val spans = blocks.filterIsInstance<LicenseBlock.Paragraph>().flatMap { LicenseContent.spans(it.text, markdown = true) }
        val ofl = spans.single { it.target == "OFL.txt" }
        assertEquals("OFL.txt", ofl.text)
        assertEquals(LicenseDocument.GOOGLE_SANS, LicenseDocument.TRADEMARKS.linkedDocument(ofl.target!!))
        assertNull(LicenseDocument.INTER.linkedDocument("OFL.txt"))
        assertTrue(spans.any { it.target == "https://github.com/googlefonts/googlesans" })
    }

    @Test fun markdownHeadingsAndPathsDoNotLeakFormattingIntoTheText() {
        assertEquals(listOf(LicenseBlock.Heading("Notices"), LicenseBlock.Paragraph("First line. Second line.")),
            LicenseContent.blocks("# Notices\n\nFirst line.\nSecond line.\n", markdown = true))
        assertEquals(listOf(LicenseSpan("Files are in "), LicenseSpan("assets/licenses/"), LicenseSpan(".")),
            LicenseContent.spans("Files are in `assets/licenses/`.", markdown = true))
    }
}
