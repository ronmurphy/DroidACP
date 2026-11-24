package com.droidacp.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.acp.ToolContent
import com.droidacp.core.diff.LineDiff

/**
 * One tool call (read a file, run a command, edit...). Collapsed it shows the
 * title and status; tap to see its input, output and any diffs.
 */
@Composable
fun ToolCallCard(call: ToolCall, modifier: Modifier = Modifier, startExpanded: Boolean = false) {
    // Edits are interesting enough to show open by default.
    val hasDiff = call.content.any { it is ToolContent.Diff }
    var expanded by rememberSaveable(call.id) { mutableStateOf(startExpanded || hasDiff) }

    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(kindIcon(call.kind), fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = call.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = if (expanded) 6 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusBadge(call.status)
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                for (location in call.locations) {
                    val line = location.line
                    Text(
                        text = if (line != null) "${location.path}:$line" else location.path,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val input = call.rawInput
                if (input != null && (call.content.isEmpty() || call.kind == "execute")) {
                    MonoBox(input, label = "Input")
                }
                for (content in call.content) ToolContentView(content)
                val output = call.rawOutput
                if (output != null && call.content.isEmpty()) MonoBox(output, label = "Output")
                if (call.content.isEmpty() && input == null && output == null) {
                    Text("No details yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    when (status) {
        "in_progress" -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        "completed" -> Text("✓", color = UiColors.success, fontWeight = FontWeight.Bold)
        "failed" -> Text("✗", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        else -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ToolContentView(content: ToolContent) {
    when (content) {
        is ToolContent.Block -> when (val block = content.block) {
            is ContentBlock.Text -> {
                // Tool output is usually code-ish; markdown if it looks like markdown.
                if (block.text.contains("```") || block.text.startsWith("#")) MarkdownText(block.text) else MonoBox(block.text)
            }
            is ContentBlock.Image -> AgentImage(block)
            is ContentBlock.ResourceLink -> Text("📎 ${block.title ?: block.name}", style = MaterialTheme.typography.bodyMedium)
            is ContentBlock.Resource -> MonoBox(block.text ?: block.uri, label = fileName(block.uri))
            else -> Text("(unsupported content)", style = MaterialTheme.typography.bodySmall)
        }
        is ToolContent.Diff -> DiffView(content)
        is ToolContent.Terminal -> Text("Terminal output is shown on the agent's computer.", style = MaterialTheme.typography.bodySmall)
        is ToolContent.SessionRef -> Text("Sub-agent session ${content.sessionId.take(8)}…", style = MaterialTheme.typography.bodySmall)
    }
}

/** Monospace text in a tinted box, scrollable sideways, with a size cap for huge outputs. */
@Composable
fun MonoBox(text: String, label: String? = null) {
    val shown = remember(text) { if (text.length > MAX_MONO_CHARS) text.take(MAX_MONO_CHARS) + "\n… (${text.length - MAX_MONO_CHARS} more characters)" else text }
    Column {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = shown,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
        }
    }
}

@Composable
fun DiffView(diff: ToolContent.Diff) {
    val hunks = remember(diff) { LineDiff.hunks(LineDiff.diff(diff.oldText, diff.newText)) }
    val isNewFile = diff.oldText == null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fileName(diff.path), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (isNewFile) {
                Spacer(Modifier.width(8.dp))
                Text("new file", style = MaterialTheme.typography.labelSmall, color = UiColors.diffAddedText)
            }
        }
        Text(diff.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        if (hunks.isEmpty()) {
            Text("No changes.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                var shownLines = 0
                for (hunk in hunks) {
                    if (shownLines >= MAX_DIFF_LINES) break
                    if (hunk.collapsedBefore > 0) {
                        DiffLineText("   ⋯ ${hunk.collapsedBefore} unchanged line${if (hunk.collapsedBefore == 1) "" else "s"}", null)
                    }
                    for (line in hunk.lines) {
                        if (shownLines >= MAX_DIFF_LINES) break
                        shownLines++
                        val prefix = when (line.kind) {
                            LineDiff.Kind.Added -> "+ "
                            LineDiff.Kind.Removed -> "- "
                            LineDiff.Kind.Same -> "  "
                        }
                        val number = (line.newNumber ?: line.oldNumber)?.toString()?.padStart(4) ?: "    "
                        DiffLineText("$number $prefix${line.text}", line.kind)
                    }
                }
                if (shownLines >= MAX_DIFF_LINES) DiffLineText("   … diff truncated", null)
            }
        }
    }
}

@Composable
private fun DiffLineText(text: String, kind: LineDiff.Kind?) {
    val background = when (kind) {
        LineDiff.Kind.Added -> UiColors.diffAddedBg
        LineDiff.Kind.Removed -> UiColors.diffRemovedBg
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        softWrap = false,
        color = if (kind == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(background)
            .padding(horizontal = 8.dp),
    )
}

private const val MAX_MONO_CHARS = 6000
private const val MAX_DIFF_LINES = 400
