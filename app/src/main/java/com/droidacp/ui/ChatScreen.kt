@file:OptIn(ExperimentalMaterial3Api::class)

package com.droidacp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import kotlinx.coroutines.launch

/** Everything the chat screen can ask for. The Android side forwards these to AgentController. */
interface ChatActions {
    fun back()
    fun send(text: String): Boolean
    fun cancel()
    fun pickImage()
    fun removeAttachment(id: Long)
    fun setMode(modeId: String)
    fun setConfig(option: ConfigOption, value: Any)
    fun newSession()
    fun showHistory()
    fun openSession(sessionId: String)
    fun authenticate(methodId: String)
    fun answerPermission(requestId: Long, optionId: String?)

    /** The composer took the text of a message that couldn't be sent from a notification. */
    fun draftUsed()
    fun reconnect()
    fun disconnect()
    fun openLog()
}

@Composable
fun ChatScreen(
    state: ChatState,
    actions: ChatActions,
    attachments: List<AttachmentThumb>,
    focusRequest: Int,
    draft: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val follower = remember(listState) { EndFollower(listState) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = actions::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column {
                        Text(state.agentName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = subtitle(state),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (state.canChat) {
                        IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, contentDescription = "Session settings") }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (state.isConnected) {
                                DropdownMenuItem(text = { Text("New session") }, onClick = { menuOpen = false; actions.newSession() })
                                if (state.capabilities.listSessions) {
                                    DropdownMenuItem(text = { Text("Session history") }, onClick = {
                                        menuOpen = false
                                        historyOpen = true
                                        actions.showHistory()
                                    })
                                }
                                DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menuOpen = false; actions.disconnect() })
                            } else if (state.status !is ConnectionStatus.Connecting) {
                                DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menuOpen = false; actions.reconnect() })
                            }
                            DropdownMenuItem(text = { Text("Protocol log") }, onClick = { menuOpen = false; actions.openLog() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(state = state, actions = actions, attachments = attachments, focusRequest = focusRequest, draft = draft, onSent = {
                scope.launch { follower.scrollToEnd() }
            })
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Keep lines a readable length on a big tablet screen.
            val side = ((maxWidth - 860.dp) / 2).coerceAtLeast(12.dp)
            val status = state.status

            when {
                state.items.isEmpty() && status is ConnectionStatus.Connecting -> CenterMessage(connectingText(state), progress = true)
                state.items.isEmpty() && status is ConnectionStatus.Failed -> ConnectionError(status.reason, actions)
                state.authRequired -> AuthPanel(state, actions)
                state.items.isEmpty() && state.startingSession -> CenterMessage("Starting a session…", progress = true)
                state.items.isEmpty() && state.canChat -> EmptyChat(state)
                else -> MessageList(state, listState, side)
            }

            Column(Modifier.padding(horizontal = side, vertical = 6.dp)) {
                if (status is ConnectionStatus.Failed && state.items.isNotEmpty()) DisconnectedBanner(status.reason, actions)
                PlanBar(state.plans)
            }

            val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
            if (!atBottom && state.items.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { follower.scrollToEnd() } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = side, bottom = 12.dp),
                ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom") }
            }
        }
    }

    // Follow the conversation while new text streams in, unless the user scrolled up.
    LaunchedEffect(follower) { follower.watch() }
    // Another chat (PlasmaLLM switched, or one picked from the history): start at its end.
    LaunchedEffect(state.sessionId) { follower.scrollToEnd() }
    val last = state.items.lastOrNull()
    val lastSize = when (last) {
        is ChatItem.Agent -> last.text.length
        is ChatItem.Thought -> last.text.length
        is ChatItem.Tool -> last.call.status.length + last.call.content.size
        else -> 0
    }
    LaunchedEffect(state.items.size, lastSize, state.busy) { follower.keepUp() }

    val permission = state.permissions.firstOrNull()
    if (permission != null) {
        val tool = state.items.lastOrNull { it is ChatItem.Tool && it.call.id == permission.toolCallId } as? ChatItem.Tool
        PermissionDialog(permission, tool?.call, onAnswer = { optionId -> actions.answerPermission(permission.requestId, optionId) })
    }
    if (settingsOpen) SessionSettingsSheet(state, actions, onDismiss = { settingsOpen = false })
    if (historyOpen) HistorySheet(state, onOpen = { id -> historyOpen = false; actions.openSession(id) }, onDismiss = { historyOpen = false })
}

private fun connectingText(state: ChatState): String {
    val p = state.profile
    return when {
        p == null -> "Connecting…"
        p.isLocal || p.isCloud -> "Starting the agent…"
        p.isPlasmaLlm -> "Connecting to PlasmaLLM on ${p.url}…"
        else -> "Connecting to ${p.url}…"
    }
}

/** The project folder: PlasmaLLM's open folder when connected to it. */
private fun folderOf(state: ChatState): String? =
    state.plasmaLlm?.chat?.cwd?.ifEmpty { null } ?: state.profile?.cwd?.ifEmpty { null }

private fun subtitle(state: ChatState): String {
    val status = state.status
    return when {
        status is ConnectionStatus.Connecting -> "Connecting…"
        status is ConnectionStatus.Failed -> "Disconnected"
        status is ConnectionStatus.Disconnected -> "Not connected"
        state.cancelling -> "Stopping…"
        state.permissions.isNotEmpty() -> "Waiting for your permission"
        state.busy && state.remoteTurn -> "Working (sent from another screen)…"
        state.busy -> "Working…"
        state.startingSession -> "Starting session…"
        else -> listOfNotNull(state.sessionTitle, state.currentModeName).joinToString(" · ").ifEmpty { folderOf(state) ?: "Ready" }
    }
}

@Composable
private fun MessageList(state: ChatState, listState: LazyListState, side: Dp) {
    // Leave room at the top for the plan bar, which floats over the list.
    val topPadding = if (state.plans.isNotEmpty()) 64.dp else 8.dp
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = side, end = side, top = topPadding, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.items, key = { it.key }) { item ->
            when (item) {
                is ChatItem.User -> UserBubble(item)
                is ChatItem.Agent -> AgentMessage(item)
                is ChatItem.Thought -> ThoughtView(item, live = state.busy && item === state.items.lastOrNull())
                is ChatItem.Tool -> ToolCallCard(item.call)
                is ChatItem.Note -> NoteView(item)
            }
        }
        if (state.busy) {
            item(key = "working") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        when {
                            state.cancelling -> "Stopping…"
                            // A prompt typed in PlasmaLLM's window or on another device.
                            state.remoteTurn -> "${state.agentName} is working on a message sent from another screen…"
                            else -> "${state.agentName} is working…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String, progress: Boolean = false) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyChat(state: ChatState) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("👋", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("Connected to ${state.agentName}", style = MaterialTheme.typography.titleMedium)
        val version = state.agent?.version
        if (version != null) Text("version $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            "Working in ${state.profile?.cwd ?: "?"}" + if (state.commands.isNotEmpty()) "\nType / to see the agent's commands." else "",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionError(reason: String, actions: ChatActions) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't connect", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(reason, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(
            "Check that acp-bridge is running on the other computer, that the address and token match, " +
                "and that this tablet can reach it (same Wi-Fi or Tailscale).",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = actions::openLog) { Text("Protocol log") }
            Button(onClick = actions::reconnect) { Text("Try again") }
        }
    }
}

@Composable
private fun DisconnectedBanner(reason: String, actions: ChatActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Disconnected: $reason",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions::reconnect) { Text("Reconnect") }
        }
    }
}

@Composable
private fun AuthPanel(state: ChatState, actions: ChatActions) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 520.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sign in to ${state.agentName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (state.authMethods.isEmpty()) {
                    Text("The agent needs you to sign in on the computer it runs on. Do that, then tap Retry.")
                }
                for (method in state.authMethods) {
                    HorizontalDivider()
                    Text(method.name, style = MaterialTheme.typography.titleSmall)
                    val description = method.description
                    if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
                    if (method.type == "terminal") {
                        Text(
                            "This sign-in runs in a terminal. Do it on the agent's computer, then tap Retry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(onClick = { actions.authenticate(method.id) }, enabled = !state.startingSession) { Text("Sign in with ${method.name}") }
                    }
                }
                HorizontalDivider()
                OutlinedButton(onClick = actions::newSession, enabled = !state.startingSession, modifier = Modifier.align(Alignment.End)) { Text("Retry") }
            }
        }
    }
}
