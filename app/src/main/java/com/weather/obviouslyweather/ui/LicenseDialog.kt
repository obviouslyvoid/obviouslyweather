// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LicenseDialog(onClose: () -> Unit) {
    val assets = LocalContext.current.assets
    val haptics = LocalAppHaptic.current
    val uriHandler = LocalUriHandler.current
    var selected by remember { mutableStateOf(LicenseDocument.NOTICES) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val document = selected
    val blocks by key(document) {
        produceState<List<LicenseBlock>?>(null) {
            value = withContext(Dispatchers.IO) {
                val text = assets.open("licenses/${document.file}").bufferedReader().use { it.readText() }
                LicenseContent.blocks(text, document.markdown)
            }
        }
    }
    val openLink: (String) -> Unit = { target ->
        haptics.perform()
        linkError = null
        val document = selected.linkedDocument(target)
        if (document != null) selected = document
        else try { uriHandler.openUri(target) }
        catch (_: IllegalArgumentException) { linkError = "No app is available to open this link." }
    }
    val buttonHeight = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    AlertDialog(onDismissRequest = onClose, title = { Text("Licenses") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Original code: GPL-3.0-or-later. Provided without warranty.", style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LicenseDocument.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { document ->
                                FilterChip(selected = selected == document, onClick = {
                                    haptics.perform()
                                    linkError = null
                                    selected = document
                                }, modifier = Modifier.weight(1f).height(buttonHeight), label = {
                                    Text(document.title, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.labelLarge, maxLines = 2)
                                })
                            }
                        }
                    }
                }
                linkError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                key(selected) {
                    Column(Modifier.weight(1f, fill = false).heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val content = blocks
                        if (content == null) Text("Loading…", style = MaterialTheme.typography.bodySmall)
                        else content.forEach { block ->
                            when (block) {
                                is LicenseBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleSmall)
                                is LicenseBlock.Paragraph -> LicenseText(block.text, selected.markdown, openLink)
                                is LicenseBlock.Component -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(block.name, style = MaterialTheme.typography.titleSmall)
                                        Text(block.license, style = MaterialTheme.typography.bodySmall)
                                        LicenseText(block.source, markdown = true, openLink)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { haptics.perform(); onClose() }) { Text("Close") } }
    )
}

@Composable
private fun LicenseText(text: String, markdown: Boolean, openLink: (String) -> Unit) {
    val spans = remember(text, markdown) { LicenseContent.spans(text, markdown) }
    val styles = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    Text(buildAnnotatedString {
        spans.forEach { span ->
            val target = span.target
            if (target == null) append(span.text)
            else withLink(LinkAnnotation.Clickable(target, styles) { openLink(target) }) { append(span.text) }
        }
    }, style = MaterialTheme.typography.bodySmall)
}
