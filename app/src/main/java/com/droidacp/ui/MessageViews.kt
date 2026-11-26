package com.droidacp.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.Plan
import com.droidacp.core.chat.ChatItem

@Composable
fun UserBubble(item: ChatItem.User) {
    // A message typed in the Claude app or claude.ai (Remote Control): Claude's orange, as
    // PlasmaLLM shows it, kept faint behind the text so it reads in light and dark themes.
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val container = if (item.remote) ClaudeOrange.copy(alpha = if (dark) 0.22f else 0.14f).compositeOver(scheme.surface) else scheme.primaryContainer
    val content = if (item.remote) scheme.onSurface else scheme.onPrimaryContainer
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            border = if (item.remote) BorderStroke(1.5.dp, ClaudeOrange) else null,
            modifier = Modifier.widthIn(max = 600.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.remote) {
                    Text(
                        "You – remote message",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        // Lighter in the dark theme and darker in the light one, for contrast on the fill.
                        color = if (dark) ClaudeOrangeLight else ClaudeOrangeDeep,
                    )
                }
                for (attachment in item.attachments) AttachmentView(attachment)
                if (item.text.isNotEmpty()) {
                    SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}

/** Claude's orange, PlasmaLLM's colour for remote messages. */
private val ClaudeOrange = Color(0xFFD97757)
private val ClaudeOrangeLight = Color(0xFFE8957A)
private val ClaudeOrangeDeep = Color(0xFF9E4127)

@Composable
fun AgentMessage(item: ChatItem.Agent) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (item.text.isNotEmpty()) {
            SelectionContainer { MarkdownText(item.text) }
        }
        for (attachment in item.attachments) AttachmentView(attachment)
    }
}

@Composable
private fun AttachmentView(block: ContentBlock) {
    when (block) {
        is ContentBlock.Image -> AgentImage(block)
        is ContentBlock.ResourceLink -> Text("📎 ${block.title ?: block.name}", style = MaterialTheme.typography.bodyMedium)
        is ContentBlock.Resource -> MonoBox(block.text ?: block.uri, label = fileName(block.uri))
        is ContentBlock.Audio -> Text("🔊 audio (${block.mimeType})", style = MaterialTheme.typography.bodySmall)
        is ContentBlock.Text -> Text(block.text)
        is ContentBlock.Other -> Text("(${block.type} content)", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AgentImage(image: ContentBlock.Image) {
    val decoder = LocalImageDecoder.current
    val bitmap = remember(image.data) { decoder.decode(image.data) }
    if (bitmap == null) {
        Text("🖼 image (${image.mimeType})", style = MaterialTheme.typography.bodySmall)
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "Image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    }
}

/** The agent's private reasoning. Collapsed to one line unless tapped. */
@Composable
fun ThoughtView(item: ChatItem.Thought, live: Boolean) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (live) "💭 Thinking…" else "💭 Thought", style = MaterialTheme.typography.labelLarge, color = color)
            Spacer(Modifier.width(4.dp))
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = color)
        }
        Text(
            text = if (expanded) item.text.trim() else item.text.trim().lineSequence().lastOrNull { it.isNotBlank() } ?: "",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = color,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

@Composable
fun NoteView(item: ChatItem.Note) {
    val color = when (item.severity) {
        ChatItem.Severity.Error -> MaterialTheme.colorScheme.error
        ChatItem.Severity.Warning -> MaterialTheme.colorScheme.tertiary
        ChatItem.Severity.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = item.text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 24.dp),
    )
}

/** The agent's current plan(s), shown as a collapsible bar above the conversation. */
@Composable
fun PlanBar(plans: Map<String, Plan>, modifier: Modifier = Modifier) {
    if (plans.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val entries = plans.values.flatMap { it.entries ?: emptyList() }
    val markdown = plans.values.mapNotNull { it.markdown }.joinToString("\n\n")
    val done = entries.count { it.status == "completed" }
    val current = entries.firstOrNull { it.status == "in_progress" }

    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(Modifier.clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📋 Plan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        entries.isNotEmpty() && current != null -> "$done/${entries.size} · ${current.content}"
                        entries.isNotEmpty() -> "$done/${entries.size} done"
                        else -> "tap to view"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
            if (entries.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = { done.toFloat() / entries.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
            if (expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (entry in entries) {
                        val mark = when (entry.status) {
                            "completed" -> "☑"
                            "in_progress" -> "◐"
                            else -> "☐"
                        }
                        Text(
                            "$mark  ${entry.content}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (entry.status == "in_progress") FontWeight.Bold else null,
                        )
                    }
                    if (markdown.isNotBlank()) MarkdownText(markdown)
                }
            }
        }
    }
}
