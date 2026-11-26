package com.droidacp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidacp.core.chat.ToolSize
import com.droidacp.core.markdown.Markdown

/** Renders agent markdown as Compose UI. Parsing lives in `core` (Markdown.kt). */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { Markdown.parseBlocks(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) MarkdownBlock(block)
    }
}

@Composable
private fun MarkdownBlock(block: Markdown.Block) {
    val typography = MaterialTheme.typography
    when (block) {
        is Markdown.Block.Heading -> Text(
            text = inline(block.text),
            style = when (block.level) {
                1 -> typography.titleLarge
                2 -> typography.titleMedium
                else -> typography.titleSmall
            },
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
        )
        is Markdown.Block.Paragraph -> Text(inline(block.text), style = typography.bodyLarge)
        is Markdown.Block.Code -> CodeBlock(block.code, block.language)
        is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            )
            Spacer(Modifier.width(10.dp))
            Text(inline(block.text), style = typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is Markdown.Block.ListItem -> Row(Modifier.padding(start = (block.depth * 18).dp)) {
            val marker = when {
                block.checked == true -> "☑"
                block.checked == false -> "☐"
                block.ordered -> "${block.number}."
                else -> "•"
            }
            Text(marker, style = typography.bodyLarge, modifier = Modifier.width(if (block.ordered) 30.dp else 20.dp))
            Text(inline(block.text), style = typography.bodyLarge)
        }
        is Markdown.Block.Table -> TableBlock(block)
        Markdown.Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

/** Inline markdown → AnnotatedString with bold/italic/code/strike/link styles. */
@Composable
fun inline(text: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, codeBackground, linkColor) {
        buildAnnotatedString {
            for (span in Markdown.parseInline(text)) {
                val style = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBackground else Color.Unspecified,
                    textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                )
                val link = span.link
                if (link != null) {
                    val linkStyle = style.copy(color = linkColor, textDecoration = TextDecoration.Underline)
                    withLink(LinkAnnotation.Url(link, TextLinkStyles(style = linkStyle))) { append(span.text) }
                } else {
                    withStyle(style) { append(span.text) }
                }
            }
        }
    }
}

@Composable
fun CodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    language ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                    Text("Copy", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                text = remember(code) { ToolSize.clipLongLines(code) },
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun TableBlock(table: Markdown.Block.Table) {
    val allRows = listOf(table.header) + table.rows
    val columns = allRows.maxOf { it.size }
    val widths = (0 until columns).map { c ->
        val longest = allRows.maxOf { it.getOrNull(c)?.length ?: 0 }
        (longest * 8 + 24).coerceIn(56, 280).dp
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            allRows.forEachIndexed { index, row ->
                Row(
                    Modifier.background(
                        when {
                            index == 0 -> MaterialTheme.colorScheme.surfaceVariant
                            index % 2 == 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            else -> Color.Transparent
                        },
                    ),
                ) {
                    widths.forEachIndexed { c, width ->
                        Text(
                            text = inline(row.getOrNull(c) ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (index == 0) FontWeight.Bold else null,
                            modifier = Modifier
                                .width(width)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
