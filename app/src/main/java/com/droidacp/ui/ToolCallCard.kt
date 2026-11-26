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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.droidacp.core.chat.ToolInput
import com.droidacp.core.chat.ToolSize
import com.droidacp.core.chat.displayTitle
import com.droidacp.core.diff.LineDiff

/**
 * One tool call (read a file, run a command, edit...). Collapsed it shows the
 * title and status; tap to see its input, output and any diffs.
 *
 * A card whose details are long (over [ToolSize.LONG_LINES] lines) stays closed, says
 * how long ("120 lines", or "+12 −3" for file edits), and opens full screen: long output
 * is easier to read there, and a long chat stays light, since closed cards aren't drawn.
 */
@Composable
fun ToolCallCard(
    call: ToolCall,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
    /** Its permission is asked on another device or the laptop, not here (this tablet is the one it acts on). */
    waitingElsewhere: Boolean = false,
) {
    // Edits are interesting enough to show open by default (when short).
    val hasDiff = call.content.any { it is ToolContent.Diff }
    var expanded by rememberSaveable(call.id) { mutableStateOf(startExpanded || hasDiff) }
    var fullScreen by rememberSaveable(call.id) { mutableStateOf(false) }
    val lines = remember(call) { ToolSize.detailLines(call) }
    val long = lines > ToolSize.LONG_LINES
    val stat = remember(call) { if (long) ToolSize.diffStat(call) else null }
    // A card that grows long while running closes, and opens full screen from then on.
    val open = expanded && !long

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
                .clickable(onClickLabel = if (long) "Open full screen" else null) {
                    if (long) fullScreen = true else expanded = !expanded
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(kindIcon(call.kind), fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = call.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = if (open) 6 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (long) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stat?.let { "+${it.added} −${it.removed}" } ?: "$lines lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusBadge(call.status)
            Icon(
                when {
                    long -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                    open -> Icons.Default.KeyboardArrowUp
                    else -> Icons.Default.KeyboardArrowDown
                },
                contentDescription = when {
                    long -> "Open full screen"
                    open -> "Collapse"
                    else -> "Expand"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (waitingElsewhere) {
            Text(
                "Waiting for approval on your phone or laptop",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
        if (open) {
            ToolDetails(call, wrap = false, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
        }
    }
    if (fullScreen) ToolDetailsSheet(call, onDismiss = { fullScreen = false })
}

/** What a card shows when open: locations, input, content and output. [limit]: [MonoBox]'s size cap. */
@Composable
private fun ToolDetails(call: ToolCall, wrap: Boolean, modifier: Modifier = Modifier, limit: Int = MAX_MONO_CHARS) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
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
            ToolInputView(input, call.kind, wrap = wrap, limit = limit)
        }
        for (content in call.content) ToolContentView(content, wrap = wrap, limit = limit)
        val output = call.rawOutput
        if (output != null && call.content.isEmpty()) MonoBox(output, label = "Output", wrap = wrap, limit = limit)
        if (call.content.isEmpty() && input == null && output == null) {
            Text("No details yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A long card's details, full screen. Lines wrap to the screen by default; code that
 * reads better unbroken can scroll sideways instead. It follows the call live, so a
 * command still running keeps adding its output here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolDetailsSheet(call: ToolCall, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var wrap by rememberSaveable { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        ) {
            Text(kindIcon(call.kind), fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = call.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusBadge(call.status)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
        }
        TextButton(onClick = { wrap = !wrap }, modifier = Modifier.padding(start = 8.dp)) {
            Text(if (wrap) "Scroll long lines sideways" else "Wrap long lines")
        }
        ToolDetails(
            call,
            wrap = wrap,
            // Full screen is where long output is read: far more of it than the chat shows.
            limit = FULL_SCREEN_CHARS,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        )
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

/**
 * [wrap]: break long lines instead of scrolling sideways. [whole]: show very long lines
 * whole instead of cutting them (the permission dialog, where nothing may be hidden).
 */
@Composable
fun ToolContentView(content: ToolContent, wrap: Boolean = false, whole: Boolean = false, limit: Int = MAX_MONO_CHARS) {
    when (content) {
        is ToolContent.Block -> when (val block = content.block) {
            is ContentBlock.Text -> {
                // Tool output is usually code-ish; markdown if it looks like markdown.
                if (block.text.contains("```") || block.text.startsWith("#")) {
                    MarkdownText(block.text)
                } else {
                    MonoBox(block.text, wrap = wrap, whole = whole, limit = limit)
                }
            }
            is ContentBlock.Image -> AgentImage(block)
            is ContentBlock.ResourceLink -> Text("📎 ${block.title ?: block.name}", style = MaterialTheme.typography.bodyMedium)
            is ContentBlock.Resource -> MonoBox(block.text ?: block.uri, label = fileName(block.uri), wrap = wrap, whole = whole, limit = limit)
            else -> Text("(unsupported content)", style = MaterialTheme.typography.bodySmall)
        }
        is ToolContent.Diff -> DiffView(content, wrap = wrap, whole = whole)
        is ToolContent.Terminal -> Text("Terminal output is shown on the agent's computer.", style = MaterialTheme.typography.bodySmall)
        is ToolContent.SessionRef -> Text("Sub-agent session ${content.sessionId.take(8)}…", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * A tool's input. A command shows as the agent wrote it (not as JSON), under its
 * description, with any other fields after it; anything else as it came.
 */
@Composable
fun ToolInputView(input: String, kind: String, wrap: Boolean = false, whole: Boolean = false, limit: Int = MAX_MONO_CHARS) {
    val command = remember(input, kind) { ToolInput.commandOf(kind, input) }
    if (command == null) {
        MonoBox(input, label = "Input", wrap = wrap, whole = whole, limit = limit)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        command.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        MonoBox(command.command, label = "Command", wrap = wrap, whole = whole, limit = limit)
        if (command.other.isNotEmpty()) {
            Text(
                command.other.joinToString(" · ") { (name, value) -> "$name: $value" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Monospace text in a tinted box, scrollable sideways (or wrapped, with [wrap]), with a
 * size cap for huge outputs ([limit] characters). Very long lines are cut, unless [whole].
 */
@Composable
fun MonoBox(text: String, label: String? = null, wrap: Boolean = false, whole: Boolean = false, limit: Int = MAX_MONO_CHARS) {
    val shown = remember(text, whole, limit) {
        val capped = if (text.length > limit) text.take(limit) + "\n… (${text.length - limit} more characters)" else text
        if (whole) capped else ToolSize.clipLongLines(capped)
    }
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
                softWrap = wrap,
                modifier = (if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(10.dp),
            )
        }
    }
}

/** A file change. [showPath] = false when the path is already shown just above (the permission dialog). */
@Composable
fun DiffView(diff: ToolContent.Diff, wrap: Boolean = false, showPath: Boolean = true, whole: Boolean = false) {
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
        if (showPath) Text(diff.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        if (hunks.isEmpty()) {
            Text("No changes.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            // Wrapped lines fill the width (so their colour does); scrolled ones are as wide as the longest.
            Column((if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())).padding(vertical = 6.dp)) {
                var shownLines = 0
                for (hunk in hunks) {
                    if (shownLines >= MAX_DIFF_LINES) break
                    if (hunk.collapsedBefore > 0) {
                        DiffLineText("   ⋯ ${hunk.collapsedBefore} unchanged line${if (hunk.collapsedBefore == 1) "" else "s"}", null, wrap)
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
                        val text = if (whole) line.text else ToolSize.clipLine(line.text)
                        DiffLineText("$number $prefix$text", line.kind, wrap)
                    }
                }
                if (shownLines >= MAX_DIFF_LINES) DiffLineText("   … diff truncated", null, wrap)
            }
        }
    }
}

@Composable
private fun DiffLineText(text: String, kind: LineDiff.Kind?, wrap: Boolean) {
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
        softWrap = wrap,
        color = if (kind == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = (if (wrap) Modifier.fillMaxWidth() else Modifier)
            .background(background)
            .padding(horizontal = 8.dp),
    )
}

/** What a tool's text box shows in the chat; the rest is in the full-screen view. */
private const val MAX_MONO_CHARS = 6000

/** What it shows full screen. */
private const val FULL_SCREEN_CHARS = 100_000
private const val MAX_DIFF_LINES = 400
