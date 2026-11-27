@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.droidacp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidacp.R
import com.droidacp.core.acp.BridgeFolders
import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.acp.PlasmaTab
import com.droidacp.core.chat.ChatBookmark
import com.droidacp.core.chat.ChatBookmarks
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.TabClose
import kotlinx.coroutines.launch

/** Browsing the agent's computer for a folder: who to ask, and how. */
data class FolderBrowse(val folders: BridgeFolders, val url: String, val token: String)

/** What the Chats panel shows besides the chat itself. */
data class ChatsPanel(
    val bookmarks: List<ChatBookmark> = emptyList(),
    /** Folders used before on this agent's computer (or this device, for the built-in agent). */
    val recentFolders: List<String> = emptyList(),
    /** Null when the chats don't live in folders (the cloud agent). */
    val showFolders: Boolean = false,
    /** Null when there's no computer to browse (the built-in agents). */
    val browse: FolderBrowse? = null,
)

/**
 * The title bar's Chats panel: pick a folder, start a chat there, or open one of its
 * chats or a bookmarked one. Nothing changes until a chat is picked. [onSwitch] runs a
 * switch to a chat (null: a new one), asking first if that stops the agent's work.
 * With PlasmaLLM 8 the other tabs' chats come first: opening one stops nothing. With
 * PlasmaLLM 15 a new tab can run another agent ([AgentPicker]), each tab says its agent and
 * can be closed, and a bookmarked chat opens only when its agent's tab is in front.
 */
@Composable
fun ChatsSheet(
    state: ChatState,
    panel: ChatsPanel,
    actions: ChatActions,
    onSwitch: (sessionId: String?, go: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    // The folder being looked at; the open chat's until another one is picked.
    var folder by rememberSaveable { mutableStateOf(state.folder ?: "") }
    var browsing by remember { mutableStateOf(false) }
    // A bookmark being checked before it opens, and one found to be gone.
    var checking by remember { mutableStateOf<String?>(null) }
    var gone by remember { mutableStateOf<ChatBookmark?>(null) }
    var checkFailed by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isPlasma = state.plasmaLlm != null
    var picking by remember { mutableStateOf(false) }
    // A tab being closed, one whose agent works (asked first), and why one didn't close or show.
    var closing by remember { mutableStateOf<String?>(null) }
    var closeBusy by remember { mutableStateOf<PlasmaTab?>(null) }
    var tabError by remember { mutableStateOf<String?>(null) }
    // A bookmark of a chat with another agent than the front tab's, and that agent.
    var otherAgent by remember { mutableStateOf<Pair<ChatBookmark, String>?>(null) }

    fun closeTab(tab: PlasmaTab, force: Boolean = false) {
        val tabId = tab.tabId ?: return
        closing = tabId
        tabError = null
        scope.launch {
            when (val r = actions.closeTab(tabId, force)) {
                TabClose.Closed -> {}
                TabClose.Busy -> closeBusy = tab
                is TabClose.Refused -> tabError = r.message
            }
            closing = null
        }
    }

    fun look(path: String) {
        folder = path
        actions.listChats(path)
    }

    fun openChat(sessionId: String, cwd: String?) {
        onDismiss()
        onSwitch(sessionId) { actions.openChat(sessionId, cwd) }
    }

    // A bookmarked chat may have been deleted since. Opening a chat that's gone makes
    // PlasmaLLM start a new one on the computer (and close the one open there), so check
    // the agent's list first. An agent that can't list chats just gets the open.
    fun openBookmark(b: ChatBookmark) {
        // Only its own agent can open it (PlasmaLLM 15): the front tab's would call it gone.
        state.agentNeededFor(b)?.let { needed ->
            otherAgent = b to needed
            return
        }
        if (!state.capabilities.listSessions) return openChat(b.sessionId, b.cwd)
        checking = b.sessionId
        checkFailed = null
        scope.launch {
            val exists = actions.chatExists(b.sessionId, b.cwd)
            checking = null
            when (exists) {
                true -> openChat(b.sessionId, b.cwd)
                false -> gone = b
                null -> checkFailed = b.sessionId
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

            val current = state.sessionId
            if (current != null) {
                val saved = ChatBookmarks.contains(panel.bookmarks, current)
                ListItem(
                    headlineContent = { Text(state.sessionTitle ?: "This chat", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOfNotNull("Open now", otherThanClaude(state, state.shownTab), state.folder?.let(::shortFolder)).joinToString(" · "))
                    },
                    trailingContent = {
                        BookmarkButton(saved) {
                            actions.toggleBookmark(current, state.folder ?: "", state.sessionTitle, state.shownTab?.agentId ?: state.frontAgentId)
                        }
                    },
                )
                HorizontalDivider()
            }

            if (panel.showFolders) {
                FolderRow(
                    folder = folder,
                    recent = panel.recentFolders,
                    canBrowse = panel.browse != null,
                    onPick = ::look,
                    onBrowse = { browsing = true },
                    onForget = actions::forgetFolder,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        val cwd = folder.ifBlank { null }
                        onSwitch(null) { actions.newChat(cwd) }
                    },
                    enabled = state.isConnected && !state.startingSession,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                    Text(if (panel.showFolders) "  New chat in this folder" else "  New chat")
                }
                // PlasmaLLM 15: a tab of its own, with Claude or another agent; nothing here stops.
                if (state.offersAgents) {
                    OutlinedButton(onClick = { picking = true }, enabled = !state.startingSession) {
                        Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                        Text("  New tab with…")
                    }
                }
            }
            if (isPlasma) {
                Text(
                    "Opening a chat here opens it in PlasmaLLM on the computer too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                val tabs = state.otherTabs
                val chatless = state.tabsWithoutChat
                if (tabs.isNotEmpty() || chatless.isNotEmpty()) {
                    item(key = "tabs") { SectionHeader("In PlasmaLLM's other tabs") }
                    tabError?.let { item(key = "close-error") { Hint(it, error = true) } }
                    items(tabs, key = { "t:" + it.sessionId }) { tab ->
                        ListItem(
                            leadingContent = {
                                if (tab.busy) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                                } else {
                                    Box(Modifier.size(24.dp))
                                }
                            },
                            headlineContent = { Text(tab.title ?: "Untitled chat", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        otherThanClaude(state, tab),
                                        tab.cwd.ifEmpty { null }?.let(::shortFolder),
                                        if (tab.busy) "working" else "idle",
                                        "no tablet".takeIf { tab.tabletTools == false && state.plasmaLlm?.tabletTools == true },
                                    ).joinToString(" · "),
                                )
                            },
                            trailingContent = { CloseTabButton(state, tab, closing) { closeTab(tab) } },
                            modifier = Modifier.clickable(enabled = state.canSwitchChats) { openChat(tab.sessionId, tab.cwd.ifEmpty { null }) },
                        )
                    }
                    // A tab starting its agent, or one whose agent couldn't start: only closed from here.
                    items(chatless, key = { "n:" + it.tabId }) { tab ->
                        val agent = state.agentLabel(tab) ?: "Its agent"
                        ListItem(
                            leadingContent = {
                                if (tab.starting) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                                } else {
                                    Box(Modifier.size(24.dp))
                                }
                            },
                            headlineContent = { Text("A new tab with $agent", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        tab.cwd.ifEmpty { null }?.let(::shortFolder),
                                        if (tab.starting) "starting" else "no chat; the computer says why",
                                        "in front".takeIf { tab.front },
                                    ).joinToString(" · "),
                                )
                            },
                            trailingContent = { CloseTabButton(state, tab, closing) { closeTab(tab) } },
                        )
                    }
                }
                if (panel.bookmarks.isNotEmpty()) {
                    item(key = "bookmarks") { SectionHeader("Bookmarks") }
                    items(panel.bookmarks, key = { "b:" + it.sessionId }) { b ->
                        val isOpen = b.sessionId == current
                        ListItem(
                            leadingContent = {
                                if (checking == b.sessionId) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                                } else {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            headlineContent = { Text(b.title ?: "Untitled chat", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                if (checkFailed == b.sessionId) {
                                    Text("Couldn't check this chat is still there. Try again.", color = MaterialTheme.colorScheme.error)
                                } else {
                                    Text(listOfNotNull(b.cwd.ifEmpty { null }?.let(::shortFolder), "open now".takeIf { isOpen }, inTab(state, b.sessionId)).joinToString(" · "))
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { actions.removeBookmark(b.sessionId) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove bookmark", Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.clickable(enabled = !isOpen && checking == null && state.canSwitchChats) { openBookmark(b) },
                        )
                    }
                }

                item(key = "listed") {
                    // Each agent keeps its own chats: the list is the front tab's agent's (PlasmaLLM 15).
                    val whose = state.frontAgentId?.takeIf { it != PlasmaLlm.CLAUDE }?.let { "${state.nameOfAgent(it)}'s chats" } ?: "Chats"
                    SectionHeader(if (panel.showFolders && folder.isNotBlank()) "$whose in ${shortFolder(folder)}" else whose)
                }
                if (panel.browse != null && state.capabilities.listSessions) {
                    // The list is Claude Code's, so it includes chats started in a terminal there.
                    item(key = "terminal") {
                        Hint("Don't open a chat that's still running in a terminal: two Claude processes would then work on the same chat.")
                    }
                }
                // Only this folder's list: never another folder's under this one's name.
                val listed = state.pastSessions.takeIf { folder.isBlank() || state.pastSessionsCwd == folder }
                when {
                    !state.capabilities.listSessions -> item(key = "cant") {
                        Hint("This agent can't list its chats. Bookmarked ones still open.")
                    }
                    state.pastSessionsError != null -> item(key = "error") { Hint(state.pastSessionsError!!, error = true) }
                    listed == null -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    listed.isEmpty() -> item(key = "none") { Hint("No chats in this folder yet.") }
                    else -> items(listed, key = { "s:" + it.sessionId }) { session ->
                        val isOpen = session.sessionId == current
                        val saved = ChatBookmarks.contains(panel.bookmarks, session.sessionId)
                        val cwd = session.cwd.ifEmpty { folder }
                        ListItem(
                            headlineContent = { Text(session.title ?: "Untitled chat", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(listOfNotNull(session.updatedAt?.replace('T', ' ')?.take(16), "open now".takeIf { isOpen }, inTab(state, session.sessionId)).joinToString(" · "))
                            },
                            trailingContent = { BookmarkButton(saved) { actions.toggleBookmark(session.sessionId, cwd, session.title, state.frontAgentId) } },
                            modifier = Modifier.clickable(enabled = !isOpen && state.canSwitchChats) { openChat(session.sessionId, cwd) },
                        )
                    }
                }
            }
        }
    }

    gone?.let { b ->
        AlertDialog(
            onDismissRequest = { gone = null },
            title = { Text("That chat is gone") },
            text = {
                Text(
                    "\"${b.title ?: "Untitled chat"}\" isn't in the agent's list for ${b.cwd.ifEmpty { "its folder" }} any more, " +
                        "so it can't be opened. Remove the bookmark?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.removeBookmark(b.sessionId)
                    gone = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { gone = null }) { Text("Keep") } },
        )
    }

    if (picking) {
        AgentPicker(
            state = state,
            folder = folder,
            actions = actions,
            onStarted = {
                picking = false
                onDismiss()
            },
            onDismiss = { picking = false },
        )
    }

    closeBusy?.let { tab ->
        val agent = state.agentLabel(tab) ?: "Its agent"
        AlertDialog(
            onDismissRequest = { closeBusy = null },
            title = { Text("Close a working tab?") },
            text = { Text("$agent is working in \"${tab.title ?: "Untitled chat"}\". Closing the tab stops it mid-turn.") },
            confirmButton = {
                TextButton(onClick = {
                    closeBusy = null
                    closeTab(tab, force = true)
                }) { Text("Stop and close") }
            },
            dismissButton = { TextButton(onClick = { closeBusy = null }) { Text("Keep it") } },
        )
    }

    otherAgent?.let { (b, needed) ->
        val name = state.nameOfAgent(needed)
        val front = state.frontAgentId?.let(state::nameOfAgent) ?: "another agent"
        val tab = state.tabRunning(needed)
        val startable = state.plasmaAgents?.firstOrNull { it.id == needed && it.ready }?.takeIf { tab == null && !actions.mustAskFor(it) }
        AlertDialog(
            onDismissRequest = { otherAgent = null },
            title = { Text("A chat with $name") },
            text = {
                Text(
                    "\"${b.title ?: "Untitled chat"}\" is a chat with $name, and the tab in front on the computer runs $front. " +
                        when {
                            tab != null -> "Bring $name's tab to the front, then open the bookmark again."
                            startable != null -> "Open a tab with $name, then open the bookmark again."
                            else -> "Open a tab with $name first (New tab with…), then open the bookmark again."
                        },
                )
            },
            confirmButton = {
                when {
                    tab?.tabId != null -> TextButton(onClick = {
                        otherAgent = null
                        val tabId = tab.tabId!!
                        scope.launch {
                            val why = actions.showTab(tabId)
                            if (why == null) onDismiss() else tabError = why
                        }
                    }) { Text("Show $name's tab") }
                    startable != null -> TextButton(onClick = {
                        otherAgent = null
                        onDismiss()
                        actions.newAgentTab(startable, b.cwd.ifEmpty { null })
                    }) { Text("New tab with $name") }
                    else -> TextButton(onClick = { otherAgent = null }) { Text("OK") }
                }
            },
            dismissButton = if (tab?.tabId != null || startable != null) {
                { TextButton(onClick = { otherAgent = null }) { Text("Cancel") } }
            } else {
                null
            },
        )
    }

    val browse = panel.browse
    if (browsing && browse != null) {
        BridgeFolderDialog(
            folders = browse.folders,
            bridgeUrl = browse.url,
            token = browse.token,
            start = folder.ifBlank { null },
            onPick = { path, _ ->
                browsing = false
                look(path)
            },
            onDismiss = { browsing = false },
        )
    }
}

/** The folder being looked at, with the folders used before and Browse in its menu. */
@Composable
private fun FolderRow(
    folder: String,
    recent: List<String>,
    canBrowse: Boolean,
    onPick: (String) -> Unit,
    onBrowse: () -> Unit,
    onForget: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val others = recent.filterNot { it == folder }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        Text(
            "📁  " + folder.ifBlank { "The agent's folder" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { open = true }, enabled = canBrowse || others.isNotEmpty()) { Text("Change") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                others.forEach { path ->
                    DropdownMenuItem(
                        text = { Text(path, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            open = false
                            onPick(path)
                        },
                        trailingIcon = {
                            IconButton(onClick = { onForget(path) }) { Icon(Icons.Default.Close, contentDescription = "Remove from list", Modifier.size(18.dp)) }
                        },
                    )
                }
                if (canBrowse) {
                    if (others.isNotEmpty()) HorizontalDivider()
                    DropdownMenuItem(text = { Text("Browse the computer's folders…") }, onClick = {
                        open = false
                        onBrowse()
                    })
                }
            }
        }
    }
}

@Composable
private fun BookmarkButton(saved: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        if (saved) {
            Icon(Icons.Default.Star, contentDescription = "Remove bookmark", tint = MaterialTheme.colorScheme.primary)
        } else {
            Icon(painterResource(R.drawable.ic_star_border), contentDescription = "Bookmark this chat")
        }
    }
}

/** A tab's Close button (PlasmaLLM 15), or its progress while it closes. */
@Composable
private fun CloseTabButton(state: ChatState, tab: PlasmaTab, closing: String?, onClose: () -> Unit) {
    if (!state.offersAgents || tab.tabId == null) return
    if (closing == tab.tabId) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    } else {
        IconButton(onClick = onClose, enabled = closing == null) {
            Icon(Icons.Default.Close, contentDescription = "Close this tab on the computer", Modifier.size(18.dp))
        }
    }
}

/** [tab]'s agent, when it isn't Claude (PlasmaLLM 15): Claude's tabs read as they always did. */
private fun otherThanClaude(state: ChatState, tab: PlasmaTab?): String? =
    tab?.takeIf { state.offersAgents && it.agentId != null && it.agentId != PlasmaLlm.CLAUDE }?.let(state::agentLabel)

/** "in another tab" or "working in another tab" for a chat another PlasmaLLM tab has open. */
private fun inTab(state: ChatState, sessionId: String): String? =
    state.tabWith(sessionId)?.let { if (it.busy) "working in another tab" else "in another tab" }

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

/** `/home/me/projects/app` as `…/projects/app`: the end of a path is the part that tells folders apart. */
internal fun shortFolder(path: String): String {
    val parts = path.trimEnd('/', '\\').split('/', '\\').filter { it.isNotEmpty() }
    return if (parts.size <= 2) path else "…/" + parts.takeLast(2).joinToString("/")
}
