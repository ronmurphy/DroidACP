@file:OptIn(ExperimentalMaterial3Api::class)

package com.droidacp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidacp.core.chat.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every JSON-RPC message sent (→) and received (←). Handy for debugging an agent,
 * and a good way to see what ACP actually looks like on the wire.
 */
@Composable
fun LogScreen(entries: List<LogEntry>, onBack: () -> Unit, onClear: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text("Protocol log") },
                actions = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(entries.joinToString("\n") { (if (it.outgoing) "→ " else "← ") + it.text }))
                    }) { Text("Copy all") }
                    IconButton(onClick = onClear) { Icon(Icons.Default.Delete, contentDescription = "Clear") }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Text("Nothing yet. Connect to an agent and its messages will show up here.", modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        SelectionContainer(Modifier.padding(padding)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
                items(entries) { entry ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "${format.format(Date(entry.timeMillis))}  ${if (entry.outgoing) "→ to agent" else "← from agent"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        )
                        Text(entry.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}
