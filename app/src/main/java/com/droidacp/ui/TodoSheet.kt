package com.droidacp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidacp.core.acp.TodoRow
import com.droidacp.core.chat.ChatState

/**
 * A project folder's to-do list, TASKLIST.md (PlasmaLLM protocol 17), as PlasmaLLM's To do tab
 * reads it: its sections and items, the open ones first ("Done" shows the rest), or the file
 * as it is. Read only. PlasmaLLM says when it changes, and it's read again by itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodoSheet(state: ChatState, actions: ChatActions, onDismiss: () -> Unit) {
    val todo = state.todo ?: return
    val list = todo.list
    var showDone by rememberSaveable { mutableStateOf(false) }
    var showFile by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("To do", style = MaterialTheme.typography.titleLarge)
                    val counts = list?.takeIf { it.exists }?.let { "${it.openCount} open · ${it.doneCount} done" }
                    val where = listOfNotNull(shortFolder(todo.cwd).ifEmpty { null }, counts).joinToString(" · ")
                    if (where.isNotEmpty()) {
                        Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (todo.loading) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = actions::refreshTodo) { Icon(Icons.Filled.Refresh, contentDescription = "Read it again") }
                }
            }
            if (list != null && list.exists) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = showDone, onClick = { showDone = !showDone }, label = { Text("Done (${list.doneCount})") })
                    FilterChip(selected = showFile, onClick = { showFile = !showFile }, label = { Text("The file") })
                }
            }
            todo.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
        when {
            list == null -> if (todo.loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            !list.exists -> Text(
                "This folder has no to-do list yet (TASKLIST.md). An agent starts one when it adds an item: ask yours to.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding),
            )
            showFile -> LazyColumn(contentPadding = padding) {
                item {
                    SelectionContainer {
                        Text(list.markdown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            else -> {
                val rows = list.shownRows(showDone)
                LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rows) { row ->
                        when (row) {
                            is TodoRow.Heading -> TodoHeading(row)
                            is TodoRow.Item -> TodoItem(row)
                        }
                    }
                    if (rows.none { it is TodoRow.Item }) {
                        item {
                            Text(
                                if (list.items.isEmpty()) "No items yet." else "Nothing left open: everything here is done.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoHeading(heading: TodoRow.Heading) {
    val style = if (heading.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall
    Text(
        heading.text,
        style = style,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/** An item: its first line, and its notes under it on a tap. Done ones are faded. */
@Composable
private fun TodoItem(item: TodoRow.Item) {
    var open by rememberSaveable(item.line, item.title) { mutableStateOf(false) }
    val hasNotes = item.notes.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (hasNotes) Modifier.clickable { open = !open } else Modifier)
            .padding(vertical = 6.dp)
            .alpha(if (item.done) 0.6f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CheckMark(item.done, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            MarkdownText(item.title)
            if (hasNotes) {
                if (open) {
                    Spacer(Modifier.height(6.dp))
                    MarkdownText(item.notes)
                } else {
                    Text("More…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** A read-only checkbox: ticked for a done item. */
@Composable
private fun CheckMark(done: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .size(20.dp)
            .then(if (done) Modifier.background(color, shape) else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, shape))
            .semantics {
                contentDescription = "To do item"
                stateDescription = if (done) "Done" else "Not done"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
    }
}
