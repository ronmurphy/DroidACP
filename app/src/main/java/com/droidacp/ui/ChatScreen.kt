@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.droidacp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.acp.OpenElsewhere
import com.droidacp.core.acp.OpenElsewhereChoice
import com.droidacp.core.chat.ChatBookmarks
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ChatView
import com.droidacp.core.chat.TabClose
import com.droidacp.core.chat.TabProblem
import com.droidacp.core.acp.PlasmaAgent
import com.droidacp.core.people.stripPeople
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.toolProgressNow
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
    /** A new chat in [cwd], or else the open chat's folder. */
    fun newChat(cwd: String?)

    /** List [cwd]'s chats for the Chats panel (null: the open chat's folder). */
    fun listChats(cwd: String?)
    fun openChat(sessionId: String, cwd: String?)

    /** Whether the agent still lists [sessionId] in [cwd] (null: it couldn't be checked). */
    suspend fun chatExists(sessionId: String, cwd: String): Boolean?

    /** Bookmark the chat, or remove its bookmark. [agentId]: PlasmaLLM's agent it's with (protocol 15). */
    fun toggleBookmark(sessionId: String, cwd: String, title: String?, agentId: String?)
    fun removeBookmark(sessionId: String)
    fun forgetFolder(path: String)
    fun authenticate(methodId: String)
    fun answerPermission(requestId: Long, optionId: String?)

    /** About a chat another Claude has open ([ChatState.openElsewhere]); null stays on the computer's chat. */
    fun answerOpenElsewhere(choice: OpenElsewhereChoice?)

    /** List the agents a new PlasmaLLM tab can run (protocol 15), into [ChatState.plasmaAgents]. */
    fun listAgents()

    /** Whether to ask before starting [agent]: PlasmaLLM downloads it, and the user hasn't said yes to this version. */
    fun mustAskFor(agent: PlasmaAgent): Boolean

    /** A new PlasmaLLM tab with [agent] in [cwd] (null: the front tab's folder); the user said yes, if asked. */
    fun newAgentTab(agent: PlasmaAgent, cwd: String?)

    /** Close PlasmaLLM's tab [tabId]; [force] also while its agent works. */
    suspend fun closeTab(tabId: String, force: Boolean): TabClose

    /** Bring PlasmaLLM's tab [tabId] to the front. Null when done, else why not. */
    suspend fun showTab(tabId: String): String?

    /** Send tab [tabId]'s person to the break room, or back (protocol 19). Null when done, else why not. */
    suspend fun setBreak(tabId: String, onBreak: Boolean): String?

    /** Show [cwd]'s to-do list (protocol 17; null: the open chat's folder), into [ChatState.todo]. */
    fun openTodo(cwd: String?)
    fun refreshTodo()
    fun closeTodo()

    /** The composer took the text of a message that couldn't be sent from a notification. */
    fun draftUsed()
    fun reconnect()
    fun disconnect()
    fun openLog()

    /** The developer view or the standard one, for every agent on this device ([ChatView]). */
    fun setDeveloperView(on: Boolean)

    /** The tablet bar's Stop: the turn, and the agent's use of this tablet until the next message. */
    fun stopTablet()

    /** 🛑 Hold: Stop, and the agent stays off this tablet until [releaseTablet]. */
    fun holdTablet()

    /** Let the agent use this tablet again after a hold (asked to confirm first). */
    fun releaseTablet()

    /** 🛑 in the message box while the agent works: stop it, and put the tablet on hold (plan D22). */
    fun halt()

    /** From a device that isn't the tablet: send "Let the tablet go on", which lifts a hold (plan D23). */
    fun letTabletGoOn()

    /** TabDesk → Customize → Agent, where DroidACP is approved. */
    fun openTabDesk()
}

@Composable
fun ChatScreen(
    state: ChatState,
    actions: ChatActions,
    attachments: List<AttachmentThumb>,
    focusRequest: Int,
    draft: String? = null,
    chats: ChatsPanel = ChatsPanel(),
    /** The developer view (tool cards, thinking, plans), or the standard one: [ChatView]. */
    developer: Boolean = true,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRelease by remember { mutableStateOf(false) }
    var confirmRemoteRelease by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Opened from the context bar: shown at "Context".
    var settingsAtContext by rememberSaveable { mutableStateOf(false) }
    var chatsOpen by rememberSaveable { mutableStateOf(false) }
    fun openChats() {
        chatsOpen = true
        actions.listChats(state.folder)
        // PlasmaLLM 15: its list of agents can change, so it's asked again each time.
        if (state.offersAgents) actions.listAgents()
    }

    // Switching to another chat while the agent works stops that work: ask first.
    // A switch waiting for "Stop and switch", and whether it was to a new chat (for the wording).
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingSwitchIsNew by remember { mutableStateOf(false) }

    /** Open [sessionId] (null: a new chat), asking first only if that stops the agent's work. */
    fun switchChat(sessionId: String?, go: () -> Unit) {
        if (state.switchStopsWork(sessionId)) {
            pendingSwitchIsNew = sessionId == null
            pendingSwitch = go
        } else {
            go()
        }
    }
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
                    // The title is the chat switcher: tap it for the Chats panel (folders, chats, bookmarks).
                    val saved = ChatBookmarks.contains(chats.bookmarks, state.sessionId)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = state.isConnected, onClickLabel = "Chats and bookmarks") { openChats() }
                            .padding(end = 4.dp),
                    ) {
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(state.sessionTitle ?: state.agentName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = subtitle(state),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (saved) Icon(Icons.Default.Star, contentDescription = "Bookmarked", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                        if (state.isConnected) Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                },
                actions = {
                    if (state.canChat) {
                        IconButton(onClick = { settingsAtContext = false; settingsOpen = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Session settings")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (state.isConnected) {
                                DropdownMenuItem(text = { Text("New chat") }, onClick = { menuOpen = false; switchChat(null) { actions.newChat(null) } })
                                DropdownMenuItem(text = { Text("Chats and bookmarks") }, onClick = { menuOpen = false; openChats() })
                                if (state.offersTodo) {
                                    DropdownMenuItem(text = { Text("To do list") }, onClick = { menuOpen = false; actions.openTodo(null) })
                                }
                                // 🛑 at any time, not only while the bar shows (plan D20).
                                state.tablet?.let { tablet ->
                                    if (tablet.held) {
                                        DropdownMenuItem(text = { Text("Let the agent use this tablet again…") }, onClick = { menuOpen = false; confirmRelease = true })
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("🛑 Hold: keep the agent off this tablet", color = MaterialTheme.colorScheme.error) },
                                            onClick = { menuOpen = false; actions.holdTablet() },
                                        )
                                    }
                                }
                                if (state.offersRemoteRelease) {
                                    // PlasmaLLM takes no message during a turn.
                                    DropdownMenuItem(
                                        text = { Text("Let the tablet go on…") },
                                        enabled = !state.busy,
                                        onClick = { menuOpen = false; confirmRemoteRelease = true },
                                    )
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
            Column {
                // The Pixel People: one per PlasmaLLM tab. Out of the way while typing.
                if (!keyboardTakesRoom()) {
                    PeopleStrip(
                        people = state.stripPeople(),
                        breakRoom = state.offersBreak,
                        onOpen = { person -> switchChat(person.sessionId) { actions.openChat(person.sessionId, person.cwd) } },
                        onBreak = actions::setBreak,
                    )
                }
                Composer(
                state = state,
                actions = actions,
                attachments = attachments,
                focusRequest = focusRequest,
                draft = draft,
                onSent = { scope.launch { follower.scrollToEnd() } },
                onReleaseTablet = { confirmRelease = true },
                onOpenContext = { settingsAtContext = true; settingsOpen = true },
                )
            }
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
                // A chat's history arrives one message at a time: it's built out of sight, and
                // the list shows once, at its end, instead of racing past as it fills.
                state.replaying -> CenterMessage(ChatView.openingText(state, developer), progress = true)
                state.items.isEmpty() && state.startingSession -> CenterMessage(startingText(state), progress = true)
                state.tabProblem != null && state.sessionId == null -> TabProblemPanel(state.tabProblem!!, actions, onChats = ::openChats)
                state.items.isEmpty() && state.canChat -> EmptyChat(state)
                else -> MessageList(state, listState, side, developer)
            }

            Column(Modifier.padding(horizontal = side, vertical = 6.dp)) {
                if (status is ConnectionStatus.Failed && state.items.isNotEmpty()) DisconnectedBanner(status.reason, actions)
                // The agent's plan is part of its work, which the standard view leaves out.
                if (developer) PlanBar(state.plans)
            }

            val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
            if (!atBottom && state.items.isNotEmpty() && !state.replaying) {
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
    // Another chat (PlasmaLLM switched, or one picked from the history): start at its end,
    // once its history is in.
    LaunchedEffect(state.sessionId, state.replaying) { if (!state.replaying) follower.scrollToEnd() }
    val last = state.items.lastOrNull()
    val lastSize = when (last) {
        is ChatItem.Agent -> last.text.length
        is ChatItem.Thought -> last.text.length
        is ChatItem.Tool -> last.call.status.length + last.call.content.size
        else -> 0
    }
    LaunchedEffect(state.items.size, lastSize, state.busy, state.replaying) { if (!state.replaying) follower.keepUp() }

    val permission = state.permissions.firstOrNull()
    if (permission != null) {
        val tool = state.items.lastOrNull { it is ChatItem.Tool && it.call.id == permission.toolCallId } as? ChatItem.Tool
        PermissionDialog(permission, tool?.call, onAnswer = { optionId -> actions.answerPermission(permission.requestId, optionId) })
    }
    if (settingsOpen) SessionSettingsSheet(state, actions, developer, atContext = settingsAtContext, onDismiss = { settingsOpen = false })
    if (state.todo != null) TodoSheet(state, actions, onDismiss = actions::closeTodo)
    if (chatsOpen) ChatsSheet(state, chats, actions, onSwitch = { id, go -> switchChat(id, go) }, onDismiss = { chatsOpen = false })
    // Under the stop question: answering it ("Keep working") brings this one back.
    val elsewhere = state.openElsewhere
    if (elsewhere != null && pendingSwitch == null) {
        OpenElsewhereDialog(elsewhere, onAnswer = { choice ->
            if (choice == null) actions.answerOpenElsewhere(null) else switchChat(elsewhere.sessionId) { actions.answerOpenElsewhere(choice) }
        })
    }
    if (confirmRemoteRelease) {
        LetTabletGoOnDialog(
            onSend = {
                confirmRemoteRelease = false
                actions.letTabletGoOn()
            },
            onDismiss = { confirmRemoteRelease = false },
        )
    }
    if (confirmRelease) {
        ReleaseTabletDialog(
            onRelease = {
                confirmRelease = false
                actions.releaseTablet()
            },
            onDismiss = { confirmRelease = false },
        )
    }
    pendingSwitch?.let { go ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("Stop the agent's work?") },
            text = { Text(stopWarning(state, pendingSwitchIsNew)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitch = null
                    go()
                }) { Text("Stop and switch") }
            },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text("Keep working") } },
        )
    }
}

/**
 * PlasmaLLM didn't open a chat because another Claude has it open (a terminal one, say):
 * close that one and open it here, open it here too, or stay. While that Claude works,
 * only waiting makes sense: closing it is refused, and a second Claude would fork the chat.
 */
@Composable
private fun OpenElsewhereDialog(e: OpenElsewhere, onAnswer: (OpenElsewhereChoice?) -> Unit) {
    val choices: List<Pair<String, OpenElsewhereChoice?>> = when {
        e.busy -> listOf("Try again" to OpenElsewhereChoice.RETRY, "OK" to null)
        e.closeFailed -> listOf(
            "Try again" to OpenElsewhereChoice.RETRY,
            "Open here anyway" to OpenElsewhereChoice.ANYWAY,
            "Cancel" to null,
        )
        else -> listOf(
            "Close that one and open here" to OpenElsewhereChoice.CLOSE,
            "Open here anyway" to OpenElsewhereChoice.ANYWAY,
            "Cancel" to null,
        )
    }
    val text = if (e.busy || e.closeFailed) e.summary else {
        e.summary + "\n\nOpening it here too means two Claudes on one chat, and neither sees what the other says."
    }
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(if (e.closeFailed) "That Claude didn't close" else "This chat is open in another Claude") },
        text = { Text(text) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                choices.forEach { (label, choice) -> TextButton(onClick = { onAnswer(choice) }) { Text(label) } }
            }
        },
    )
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

private fun stopWarning(state: ChatState, newChat: Boolean): String {
    val plasma = state.plasmaLlm
    return when {
        plasma == null -> "${state.agentName} is working on this chat. Opening another one stops that work."
        // PlasmaLLM 8 lists its tabs, so this is asked only when the chat would be replaced.
        plasma.tabs != null -> {
            val opens = if (newChat) "A new chat opens in this chat's PlasmaLLM tab" else "That chat isn't open in a PlasmaLLM tab, so it opens in this one"
            "${state.agentName} is working on this chat. $opens, which stops the work here, even if it was started on the " +
                "computer, and drops anything the agent planned to come back to here (its wake_me). Chats open in other tabs " +
                "don't stop anything."
        }
        else -> {
            val stops = "${state.agentName} is working on this chat. Opening another one stops that work, even if it was started on the " +
                "computer, and drops anything the agent planned to come back to in this chat (its wake_me)."
            // PlasmaLLM with tabs: a chat open in another tab comes to the front, and this one goes on in its own.
            if (plasma.protocol >= 4) "$stops If the chat you open is in another PlasmaLLM tab, this one keeps working in its tab instead." else stops
        }
    }
}

private fun subtitle(state: ChatState): String {
    val status = state.status
    return when {
        status is ConnectionStatus.Connecting -> "Connecting…"
        status is ConnectionStatus.Failed && state.reconnecting -> "Disconnected; connecting again soon…"
        status is ConnectionStatus.Failed -> "Disconnected"
        status is ConnectionStatus.Disconnected -> "Not connected"
        state.cancelling -> "Stopping…"
        state.permissions.isNotEmpty() -> "Waiting for your permission"
        state.busy && state.remoteTurn -> "Working (sent from another screen)…"
        state.busy -> "Working…"
        state.replaying -> "Opening the chat…"
        state.startingAgent != null -> "Starting ${state.startingAgent?.name}…"
        state.startingSession -> "Starting session…"
        state.tabProblem != null && state.sessionId == null -> "No chat in this tab"
        // The title line shows the chat; this one says where it is.
        else -> listOfNotNull(
            state.agentName.takeIf { state.sessionTitle != null },
            state.folder?.let(::shortFolder),
            state.currentModeName,
        ).joinToString(" · ").ifEmpty { "Ready" }
    }
}

@Composable
private fun MessageList(state: ChatState, listState: LazyListState, side: Dp, developer: Boolean) {
    // Leave room at the top for the plan bar, which floats over the list.
    val topPadding = if (developer && state.plans.isNotEmpty()) 64.dp else 8.dp
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = side, end = side, top = topPadding, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        val shown = ChatView.shown(state, developer)
        items(shown, key = { it.key }) { item ->
            when (item) {
                is ChatItem.User -> UserBubble(item)
                is ChatItem.Agent -> AgentMessage(item)
                is ChatItem.Thought -> ThoughtView(item, live = state.busy && item === state.items.lastOrNull())
                is ChatItem.Tool -> if (developer) {
                    ToolCallCard(
                        item.call,
                        waitingElsewhere = item.call.id in state.waitingElsewhere,
                        progress = state.progressOf(item.call),
                    )
                } else {
                    ToolPictures(item.call)
                }
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
                            // An action on this tablet, approved on the phone or the laptop.
                            state.waitingElsewhere.isNotEmpty() -> "Waiting for approval on your phone or laptop…"
                            // A file being copied, say: on its card in the developer view.
                            !developer && state.toolProgressNow != null -> state.toolProgressNow.orEmpty()
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

/** What the screen says while a chat starts: in a new PlasmaLLM tab with an agent (protocol 15), it names it. */
private fun startingText(state: ChatState): String {
    val agent = state.startingAgent ?: return "Starting a session…"
    val download = if (agent.downloads) "\n\nIts first start downloads it, which can take a minute or two." else ""
    return "Starting ${agent.name} in a new tab on the computer…$download"
}

/**
 * The tab in front on the computer is a new one whose agent didn't start a chat ([TabProblem]):
 * it stays open there showing the problem, so this screen shows it too, with Close.
 */
@Composable
private fun TabProblemPanel(problem: TabProblem, actions: ChatActions, onChats: () -> Unit) {
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var askForce by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun close(force: Boolean) {
        val tabId = problem.tabId ?: return
        closing = true
        error = null
        scope.launch {
            when (val r = actions.closeTab(tabId, force)) {
                TabClose.Closed -> {}
                TabClose.Busy -> askForce = true
                is TabClose.Refused -> error = r.message
            }
            closing = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 520.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (problem.signIn) "Sign in to ${problem.agentName} on the computer" else "${problem.agentName} didn't start",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(problem.message)
                Text(
                    if (problem.signIn) {
                        "Its tab stays open in PlasmaLLM, where you can sign in. Or close it, and this screen goes back to the tab in front."
                    } else {
                        "Its tab stays open in PlasmaLLM, showing the problem. Close it, and this screen goes back to the tab in front."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onChats) { Text("Chats") }
                    if (problem.tabId != null) {
                        Button(onClick = { close(force = false) }, enabled = !closing) { Text("Close the tab") }
                    }
                }
            }
        }
    }

    if (askForce) {
        AlertDialog(
            onDismissRequest = { askForce = false },
            title = { Text("Close a working tab?") },
            text = { Text("${problem.agentName} is working in that tab now. Closing it stops it mid-turn.") },
            confirmButton = {
                TextButton(onClick = {
                    askForce = false
                    close(force = true)
                }) { Text("Stop and close") }
            },
            dismissButton = { TextButton(onClick = { askForce = false }) { Text("Keep it") } },
        )
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
        // A PlasmaLLM tab names its own agent (protocol 15): "Codex, in Plasma LLM".
        val who = if (state.offersAgents) "${state.agentName}, in Plasma LLM" else state.agentName
        Text("Connected to $who", style = MaterialTheme.typography.titleMedium)
        val version = state.agentVersion
        if (version != null) Text("version $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(
                state.folder?.let { "Working in $it" },
                "Type / to see the agent's commands.".takeIf { state.commands.isNotEmpty() },
            ).joinToString("\n"),
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
                OutlinedButton(onClick = { actions.newChat(null) }, enabled = !state.startingSession, modifier = Modifier.align(Alignment.End)) { Text("Retry") }
            }
        }
    }
}

/**
 * Whether the on-screen keyboard takes room at the bottom, more than a hardware keyboard's
 * small toolbar does. Not `WindowInsets.isImeVisible`: Lenovo's tablets say the keyboard is
 * shown in their floating windows (and sometimes in landscape) with none on screen.
 */
@Composable
private fun keyboardTakesRoom(): Boolean {
    val density = LocalDensity.current
    val keyboard = WindowInsets.ime.getBottom(density) - WindowInsets.navigationBars.getBottom(density)
    return keyboard > with(density) { KEYBOARD_ROOM.roundToPx() }
}

private val KEYBOARD_ROOM = 100.dp
