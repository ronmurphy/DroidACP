package com.droidacp.core.chat

import com.droidacp.core.acp.AcpClient
import com.droidacp.core.acp.AcpClientHandler
import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.acp.ConnectionRefusedException
import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.OpenElsewhere
import com.droidacp.core.acp.OpenElsewhereChoice
import com.droidacp.core.acp.PermissionOutcome
import com.droidacp.core.acp.PermissionRequest
import com.droidacp.core.acp.PlasmaAgent
import com.droidacp.core.acp.PlasmaChat
import com.droidacp.core.acp.PlasmaEvent
import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.acp.PlasmaTab
import com.droidacp.core.acp.SessionSetup
import com.droidacp.core.acp.SessionUpdate
import com.droidacp.core.acp.TodoList
import com.droidacp.core.acp.Transport
import com.droidacp.core.acp.TransportListener
import com.droidacp.core.acp.WebSocketTransport
import com.droidacp.core.acp.plasmaReason
import com.droidacp.core.acp.plasmaSessionId
import com.droidacp.core.acp.plasmaTabId
import com.droidacp.core.rpc.ConnectionClosedException
import com.droidacp.core.rpc.RpcErrorCodes
import com.droidacp.core.rpc.RpcException
import com.droidacp.core.tablet.Tablet
import com.droidacp.core.tablet.TabletRelay
import com.droidacp.core.tablet.TabletStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One line of the protocol log (Settings → Protocol log in the app). */
data class LogEntry(val timeMillis: Long, val outgoing: Boolean, val text: String)

/** Things worth telling the user about even when the app is in the background. */
sealed interface AgentEvent {
    /** A turn ended in [sessionId], the chat a reply from the notification goes to. */
    data class TurnFinished(val agentName: String, val stopReason: String, val lastText: String?, val sessionId: String? = null) : AgentEvent
    data class PermissionRequested(val agentName: String, val title: String) : AgentEvent
    data class ConnectionLost(val agentName: String, val reason: String) : AgentEvent

    /** A message failed. [unsent] is its text when the agent never got it, so it isn't lost. */
    data class PromptFailed(val agentName: String, val message: String, val unsent: String?) : AgentEvent
}

/** An image to attach to a prompt (already base64 encoded). */
data class ImageAttachment(val mimeType: String, val base64: String)

/** How closing a PlasmaLLM tab went ([AgentController.closeTab]). */
sealed interface TabClose {
    /** Closed (or already gone). */
    data object Closed : TabClose

    /** Its agent is working: closing it anyway cuts the turn, so the user is asked first. */
    data object Busy : TabClose

    /** Not closed, and why, in words for the user. */
    data class Refused(val message: String) : TabClose
}

/**
 * Makes the right [Transport] for a profile: a WebSocket for a bridge agent, an
 * in-memory pipe for the built-in one. Passed to [AgentController] so the controller
 * itself never needs to know which kind it is talking to.
 */
fun interface TransportFactory {
    fun create(profile: Profile, listener: TransportListener): Transport
}

/**
 * Owns the connection to one agent and turns it into a [ChatState] the UI can draw.
 *
 * Lives as long as the app process (not tied to a screen), so rotating the tablet
 * or leaving the chat screen doesn't drop the connection. All public methods are
 * safe to call from the main thread; they never block.
 *
 * [tablet]: this device's tablet (TabDesk), which a PlasmaLLM agent with
 * [Profile.useTablet] on may use: see [TabletRelay]. Null where there's none to offer.
 * While it's offered, a dropped connection is made again by itself, after each of
 * [reconnectDelaysMs] in turn (the last one repeats).
 */
class AgentController(
    private val appScope: CoroutineScope,
    private val clientVersion: String,
    private val tablet: Tablet? = null,
    private val reconnectDelaysMs: List<Long> = RECONNECT_DELAYS_MS,
    /** Another tab's progress line goes from the tablet's notification after this long without a new one. */
    private val progressTimeoutMs: Long = PROGRESS_TIMEOUT_MS,
    private val transports: TransportFactory,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    @Volatile
    private var link: Link? = null
    private val permissionWaiters = ConcurrentHashMap<Long, CompletableDeferred<PermissionOutcome>>()
    private val nextPermissionId = AtomicLong(1)

    /** Numbers each connection, for TabDesk's Binder ids (`c3:mcp-request:7`). */
    private val nextConnection = AtomicLong(1)

    /** Connecting again by itself after a drop (the tablet's relay), and how many tries so far. */
    @Volatile
    private var retry: Job? = null

    @Volatile
    private var retries = 0

    /**
     * The user disconnected: nothing connects again by itself until they connect. Checked again
     * when a wait ends, because a drop at the same moment can schedule one just after
     * [disconnect] has cancelled what was waiting.
     */
    @Volatile
    private var userDisconnected = false

    /** An edit of the connected agent, saved while connected: the next connection uses it, a reconnect too. */
    private var savedWhileConnected: Profile? = null

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    /**
     * Connect to [profile]. If [resumeSessionId] is given we try to continue that
     * session (session/resume, else session/load); otherwise a new session starts.
     *
     * PlasmaLLM is different: the phone is a second screen for the chat open on the
     * computer, so it always joins that chat (and [resumeSessionId] is ignored), unless
     * [startNew] asks for a new chat there.
     *
     * [resumeCwd] is the folder [resumeSessionId] runs in, when it isn't the profile's.
     */
    fun connect(
        profile: Profile,
        resumeSessionId: String? = null,
        keepTranscript: Boolean = false,
        startNew: Boolean = false,
        resumeCwd: String? = null,
    ) {
        userDisconnected = false
        cancelRetry()
        open(profile, resumeSessionId, keepTranscript, startNew, resumeCwd)
    }

    private fun open(
        profile: Profile,
        resumeSessionId: String?,
        keepTranscript: Boolean = false,
        startNew: Boolean = false,
        resumeCwd: String? = null,
    ) {
        val old = link
        // This connection uses [profile]; an edit saved during the old one is in it (or superseded).
        savedWhileConnected = null
        // When we keep our own transcript (reconnect), session/resume is enough; otherwise
        // prefer session/load so the agent replays the conversation for us.
        val newLink = Link(profile, resumeSessionId, resumeCwd?.trim()?.ifEmpty { null }, preferLoad = !keepTranscript, startNew = startNew)
        link = newLink // set first, so the old link's close callback knows it's no longer current
        old?.shutdown()
        tablet?.link?.setWanted(profile.offersTablet)
        _state.update { old ->
            val base = if (keepTranscript && old.profile?.id == profile.id) old else ChatState(nextKey = old.nextKey)
            base.copy(
                status = ConnectionStatus.Connecting,
                profile = profile,
                authRequired = false,
                busy = false,
                remoteTurn = false,
                cancelling = false,
                startingSession = false,
                permissions = emptyList(),
                pastSessions = null,
                pastSessionsCwd = null,
                pastSessionsError = null,
                plasmaLlm = null,
                sessionId = if (keepTranscript) base.sessionId else null,
                tablet = tablet?.takeIf { profile.offersTablet }?.let { TabletUse(it.link.status.value, held = it.held.value) },
                approvalElsewhere = emptySet(),
                toolProgress = emptyMap(),
                tabletProgress = null,
                reconnecting = false,
            )
        }
        newLink.transport.connect()
    }

    /** Reconnect after a dropped connection, continuing the same session if the agent allows it. */
    fun reconnect() {
        val s = _state.value
        val profile = s.profile ?: return
        connect(profile, resumeSessionId = s.sessionId, keepTranscript = true, resumeCwd = s.sessionCwd)
    }

    fun disconnect() {
        userDisconnected = true
        cancelRetry()
        // Also while a reconnect was waiting, when there's no link but TabDesk is still bound.
        tablet?.link?.setWanted(false)
        val l = link
        if (l == null) {
            // A failed connection (maybe waiting to try again) ends too: the user is done with it,
            // and the tablet's relay isn't connected again after a reboot (AfterReboot).
            _state.update {
                val status = if (it.status is ConnectionStatus.Failed) ConnectionStatus.Disconnected else it.status
                if (it.tablet != null || status != it.status) it.copy(tablet = null, status = status) else it
            }
            return
        }
        link = null
        l.shutdown()
        cancelAllPermissions()
        // An edit saved while connected counts from now on (Reconnect uses the state's profile).
        val saved = savedWhileConnected
        savedWhileConnected = null
        _state.update {
            it.copy(
                status = ConnectionStatus.Disconnected,
                profile = saved?.takeIf { p -> p.id == it.profile?.id } ?: it.profile,
                busy = false,
                remoteTurn = false,
                cancelling = false,
                startingSession = false,
                permissions = emptyList(),
                tablet = null,
                approvalElsewhere = emptySet(),
                toolProgress = emptyMap(),
                tabletProgress = null,
            )
        }
    }

    /**
     * "Let the agent use this tablet" changed for the agent in use. On, the tablet is
     * offered to PlasmaLLM on the connection already open once TabDesk has approved (a
     * `declareOnly` resume). Off, running calls end and later ones are refused (-33001):
     * PlasmaLLM keeps a registration until the connection closes, so refusing is how it's
     * withdrawn, without reconnecting.
     */
    fun setTabletAllowed(on: Boolean) {
        val s = _state.value
        val profile = s.profile ?: return
        if (profile.useTablet == on) return
        val updated = profile.copy(useTablet = on)
        // Not connected, TabDesk waits for the next connection (open() binds it then).
        val bind = updated.offersTablet && link != null
        _state.update {
            it.copy(
                profile = updated,
                tablet = tablet?.takeIf { bind }?.let { t -> it.tablet ?: TabletUse(t.link.status.value, held = t.held.value) },
            )
        }
        // Off: the running calls end as refused (-33001) before TabDesk is let go of.
        if (!updated.offersTablet) {
            link?.tabletAllowed(false)
            cancelRetry()
        }
        tablet?.link?.setWanted(bind)
        if (bind) link?.tabletAllowed(true)
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    /**
     * Start a new chat in [cwd], or else in the open chat's folder. For PlasmaLLM, no
     * folder means the one open on the computer, which is the chat on screen's folder.
     */
    fun newSession(cwd: String? = null) {
        val l = link ?: return
        val s = _state.value
        if (s.busy) cancelTurn()
        leave(l)
        _state.update { resetSession(it, clearTranscript = true).copy(openElsewhere = null) }
        val folder = cwd?.trim()?.ifEmpty { null } ?: if (l.plasma) "" else s.sessionCwd?.ifEmpty { null } ?: l.profile.cwd
        l.scope.launch { l.startSession(target = null, preferLoad = false, cwd = folder) }
    }

    /** List the chats in [cwd], or else in the open chat's folder. */
    fun refreshPastSessions(cwd: String? = null) {
        val l = link ?: return
        if (!_state.value.capabilities.listSessions) return
        val folder = cwd?.trim()?.ifEmpty { null } ?: l.listCwd()
        _state.update {
            it.copy(
                loadingPastSessions = true,
                pastSessionsError = null,
                // Another folder's chats must never show under this one's name, even for a moment.
                pastSessions = if (it.pastSessionsCwd == folder) it.pastSessions else null,
                pastSessionsCwd = folder,
            )
        }
        l.scope.launch {
            try {
                val list = l.client.listSessions(folder)
                _state.update { if (it.pastSessionsCwd == folder) it.copy(pastSessions = list, loadingPastSessions = false) else it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update {
                    if (it.pastSessionsCwd != folder) it else it.copy(loadingPastSessions = false, pastSessionsError = "Couldn't list the chats: ${e.message}")
                }
            }
        }
    }

    /**
     * Open another chat, showing its old messages if the agent supports `session/load`.
     * [cwd] is its folder (a bookmark knows it); otherwise the list's, else the open chat's.
     */
    fun openPastSession(sessionId: String, cwd: String? = null) {
        val l = link ?: return
        val s = _state.value
        if (sessionId == s.sessionId) return
        // PlasmaLLM: pass the chat's own folder as the list gave it, so opening a chat
        // doesn't switch the computer to another folder by mistake.
        val listed = cwd?.trim()?.ifEmpty { null } ?: s.pastSessions?.firstOrNull { it.sessionId == sessionId }?.cwd?.ifEmpty { null }
        val folder = if (l.plasma) listed ?: l.host?.cwd ?: "" else listed ?: s.sessionCwd?.ifEmpty { null } ?: l.profile.cwd
        // PlasmaLLM: leaving a chat mid-turn is the computer's call (it answers "cancelled"); don't stop it from here.
        if (s.busy && !l.plasma) cancelTurn()
        leave(l)
        _state.update { resetSession(it, clearTranscript = true).copy(openElsewhere = null) }
        l.scope.launch { l.startSession(target = sessionId, preferLoad = true, cwd = folder) }
    }

    /**
     * The user's answer about a chat another Claude has open ([ChatState.openElsewhere]):
     * PlasmaLLM is asked again with that [choice], or null leaves the phone on the
     * computer's chat. Leaving that chat mid-turn stops its turn, as any switch does.
     */
    fun answerOpenElsewhere(choice: OpenElsewhereChoice?) {
        val l = link ?: return
        val asked = _state.value.openElsewhere ?: return
        _state.update { it.copy(openElsewhere = null) }
        if (choice == null || asked.sessionId == _state.value.sessionId) return
        leave(l)
        _state.update { resetSession(it, clearTranscript = true) }
        val meta = choice.wire?.let(PlasmaLlm::openElsewhereMeta)
        l.scope.launch { l.startSession(target = asked.sessionId, preferLoad = true, cwd = asked.cwd, meta = meta) }
    }

    // ------------------------------------------------------------------
    // PlasmaLLM's tabs with other agents (protocol 15)
    // ------------------------------------------------------------------

    /** List the agents a new PlasmaLLM tab can run, for the picker: asked again each time it opens. */
    fun refreshAgents() {
        val l = link ?: return
        if (!l.plasma || !_state.value.offersAgents) return
        _state.update { it.copy(loadingAgents = true, agentsError = null) }
        l.scope.launch {
            try {
                val agents = l.client.plasmaAgents()
                _state.update { it.copy(plasmaAgents = agents, loadingAgents = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loadingAgents = false, agentsError = "Couldn't list PlasmaLLM's agents: ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------------
    // A project folder's to-do list (protocol 17), read only
    // ------------------------------------------------------------------

    /** Show [cwd]'s to-do list (null: the open chat's folder), read from PlasmaLLM. */
    fun openTodo(cwd: String? = null) {
        val folder = cwd?.ifEmpty { null } ?: _state.value.folder.orEmpty()
        // The same folder's list stays on screen while it's read again.
        _state.update { it.copy(todo = TodoView(folder, list = it.todo?.takeIf { t -> samePath(t.cwd, folder) }?.list)) }
        readTodo(folder)
    }

    /** Read the open list again: the user asked, or PlasmaLLM said it changed. */
    fun refreshTodo() {
        val todo = _state.value.todo ?: return
        _state.update { s -> s.todo?.let { s.copy(todo = it.copy(loading = true, error = null)) } ?: s }
        readTodo(todo.cwd)
    }

    fun closeTodo() {
        _state.update { it.copy(todo = null) }
    }

    private fun readTodo(folder: String) {
        val l = link?.takeIf { it.plasma && _state.value.offersTodo }
        if (l == null) {
            todoRead(folder, null, "DroidACP isn't connected to PlasmaLLM.")
            return
        }
        l.scope.launch {
            try {
                todoRead(folder, l.client.todo(folder.ifEmpty { null }), null)
            } catch (e: RpcException) {
                val why = when (e.plasmaReason) {
                    PlasmaLlm.Reason.TOO_BIG -> "This to-do list is too big to show here (over 256 KB)."
                    PlasmaLlm.Reason.UNREADABLE -> "This folder has a to-do list, but PlasmaLLM couldn't read it."
                    PlasmaLlm.Reason.NO_FOLDER -> "PlasmaLLM can't open this folder: it isn't there, or isn't one it allows."
                    else -> e.message
                }
                todoRead(folder, null, why ?: "PlasmaLLM couldn't read the to-do list.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                todoRead(folder, null, "Couldn't read the to-do list: ${e.message}")
            }
        }
    }

    /** [folder]'s list was read ([list]) or not ([error]); only for the list still open. */
    private fun todoRead(folder: String, list: TodoList?, error: String?) {
        _state.update { s ->
            val t = s.todo
            if (t == null || !samePath(t.cwd, folder)) {
                s
            } else {
                // PlasmaLLM names the folder it read: the open chat's, when none was asked.
                s.copy(todo = t.copy(cwd = list?.cwd?.ifEmpty { null } ?: t.cwd, list = list ?: t.list, loading = false, error = error))
            }
        }
    }

    /** One folder written two ways ("/p" and "/p/"); an empty one, the open chat's, matches any. */
    private fun samePath(a: String, b: String): Boolean = a.isEmpty() || b.isEmpty() || a.trimEnd('/') == b.trimEnd('/')

    /**
     * Open a new PlasmaLLM tab with [agent] in [cwd] (empty: the front tab's folder), in front,
     * and show its new chat here. The chat shown now goes on in its own tab, so nothing stops,
     * even mid-turn. An agent downloaded on its first start can take a minute or more.
     */
    fun newAgentTab(agent: PlasmaAgent, cwd: String? = null) {
        val l = link ?: return
        if (!l.plasma || !_state.value.offersAgents) return
        leave(l)
        _state.update { resetSession(it, clearTranscript = true).copy(openElsewhere = null, startingAgent = agent) }
        l.scope.launch { l.startSession(target = null, preferLoad = false, cwd = cwd?.trim().orEmpty(), agent = agent) }
    }

    /**
     * Close PlasmaLLM's tab [tabId]. [force] closes it while its agent works, which cuts its
     * turn: ask the user first ([TabClose.Busy] says it's working).
     */
    suspend fun closeTab(tabId: String, force: Boolean = false): TabClose {
        val l = link ?: return TabClose.Refused("DroidACP isn't connected to PlasmaLLM.")
        if (!l.plasma || !_state.value.offersAgents) return TabClose.Refused("This PlasmaLLM can't close its tabs from here.")
        return try {
            l.client.closeTab(tabId, force)
            closed(tabId)
            TabClose.Closed
        } catch (e: RpcException) {
            when (e.plasmaReason) {
                PlasmaLlm.Reason.BUSY -> TabClose.Busy
                // Closed meanwhile, on the computer or another device.
                PlasmaLlm.Reason.NO_TAB -> TabClose.Closed.also { closed(tabId) }
                PlasmaLlm.Reason.LAST_TAB -> TabClose.Refused("That's PlasmaLLM's last tab, which stays open.")
                else -> TabClose.Refused(e.message ?: "PlasmaLLM didn't close the tab.")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            TabClose.Refused("PlasmaLLM didn't answer: ${e.message}")
        }
    }

    /** A tab that's gone can't be the problem shown here; PlasmaLLM's state names the tab in front next. */
    private fun closed(tabId: String) {
        _state.update { if (it.tabProblem?.tabId == tabId) it.copy(tabProblem = null) else it }
    }

    /**
     * Bring PlasmaLLM's tab [tabId] to the front; this screen follows it, as it follows the
     * computer. Null when done, else why not, in words for the user.
     */
    suspend fun showTab(tabId: String): String? {
        val l = link ?: return "DroidACP isn't connected to PlasmaLLM."
        if (!l.plasma || !_state.value.offersAgents) return "This PlasmaLLM can't bring its tabs to the front from here."
        return try {
            l.client.showTab(tabId)
            null
        } catch (e: RpcException) {
            if (e.plasmaReason == PlasmaLlm.Reason.NO_TAB) "That tab isn't open any more." else e.message ?: "PlasmaLLM didn't show the tab."
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "PlasmaLLM didn't answer: ${e.message}"
        }
    }

    /**
     * Whether [sessionId] is still in the agent's list for [cwd], checked before a
     * bookmark opens: a chat that's gone makes PlasmaLLM start a new one on the computer,
     * closing the one open there. Null when it couldn't be checked (the agent can't list
     * chats, or the list failed).
     */
    suspend fun chatExists(sessionId: String, cwd: String): Boolean? {
        val l = link ?: return null
        if (!_state.value.capabilities.listSessions) return null
        return try {
            // More pages than the panel lists: an old bookmark may be far down a busy folder.
            l.client.listSessions(cwd.trim().ifEmpty { null }, maxPages = 20).any { it.sessionId == sessionId }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    /**
     * Leaving the open chat for another: nothing of it may reach the next one. Its
     * permission questions are answered "cancelled" (PlasmaLLM ignores that answer, and
     * still asks on the computer), anything it still sends is ignored, and an agent that
     * can close sessions is told to, so it frees what it holds. PlasmaLLM isn't: the
     * chat stays open on the computer.
     */
    private fun leave(l: Link) {
        val old = _state.value.sessionId ?: return
        l.left += old
        cancelAllPermissions()
        if (l.plasma || !_state.value.capabilities.closeSession) return
        l.scope.launch {
            try {
                l.client.closeSession(old)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The protocol log shows it; the next chat opens either way.
            }
        }
    }

    fun authenticate(methodId: String) {
        val l = link ?: return
        _state.update { it.copy(startingSession = true) }
        l.scope.launch {
            try {
                l.client.authenticate(methodId)
                _state.update { it.copy(authRequired = false) }
                l.startSession(target = l.resumeSessionId, preferLoad = l.preferLoad, cwd = l.startCwd)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { Transcript.addNote(it.copy(startingSession = false), "Sign-in failed: ${e.message}", ChatItem.Severity.Error) }
            }
        }
    }

    /**
     * The user saved [p]. If it's the agent in use, what can change while connected does:
     * the tablet setting and quiet mode. The rest takes effect on the next connection,
     * including a reconnect that's waiting.
     */
    fun profileSaved(p: Profile) {
        if (_state.value.profile?.id != p.id) return
        setTabletAllowed(p.useTablet)
        if (link == null) {
            _state.update { it.copy(profile = p) }
        } else {
            savedWhileConnected = p
            _state.update { it.copy(profile = it.profile?.copy(quietHere = p.quietHere)) }
        }
    }

    /**
     * Stop in the tablet's bar: the agent's turn, if one is running in the chat shown, and
     * the agent's use of the tablet either way, until the next message the user sends.
     */
    fun stopTablet() {
        if (_state.value.busy) cancelTurn() else link?.stopTablet()
    }

    /**
     * 🛑 Hold (plan D20): the agent may not use this tablet at all until the user lets it go
     * on with [releaseTablet]: not after their next message, nor after a restart. Saved first,
     * so it holds even if what follows fails. Running calls end refused and TabDesk stops, as
     * with Stop; the turn is cancelled too, but only if it's using the tablet right now, so a
     * precaution taken from the menu doesn't end the agent's other work. Its next tablet call
     * is refused, with the reason.
     */
    fun holdTablet() = hold(cancelTurnUsingIt = true)

    /**
     * [cancelTurnUsingIt]: also the turn using the tablet now (the buttons). Not for a 🛑
     * message, which is usually the turn itself: its calls are refused all the same.
     */
    private fun hold(cancelTurnUsingIt: Boolean) {
        val t = tablet ?: return
        val l = link
        val s = _state.value
        // Asked before the calls are refused, which ends them.
        val turnUsesIt = cancelTurnUsingIt && l != null && s.busy && s.sessionId != null && l.tabletInUse()
        t.setHeld(true)
        l?.tabletHeld()
        // cancelTurn() stops TabDesk too.
        if (turnUsesIt) cancelTurn() else l?.stopTablet()
    }

    /** The user let the agent use this tablet again, after a hold. */
    fun releaseTablet() {
        val t = tablet ?: return
        if (!t.held.value) return
        t.setHeld(false)
        link?.tabletReleased()
    }

    /**
     * The menu's "Let the tablet go on…" on a device that isn't the tablet (plan D23): sends
     * [TabletRelay.RELEASE_MESSAGE], which the tablet's DroidACP takes as the user's own lift,
     * since a person sent it. False if it can't be sent now (a turn is running).
     */
    fun askTabletToGoOn(): Boolean = sendPrompt(TabletRelay.RELEASE_MESSAGE)

    /**
     * 🛑 in the message box, while the agent is working (plan D22): stop the turn, and put the
     * tablet on hold, from any device. PlasmaLLM takes no message during a turn, so the turn is
     * stopped first and a 🛑 message goes as soon as it has ended; the DroidACP that offers the
     * tablet holds on it (D21), and the agent reads why it was stopped. On that device the hold
     * is set here at once as well, before anything else happens.
     */
    fun halt() {
        val l = link ?: return
        val s = _state.value
        if (!s.canHalt) return
        // Before the turn is cancelled, so its running calls end refused, as on hold.
        l.holdIfAsked(HALT_MESSAGE)
        _state.update { Transcript.addNote(it, "🛑 Stopping the agent, then sending 🛑 to put the tablet on hold.").copy(halting = true) }
        cancelTurn()
        l.scope.launch {
            var sent = false
            try {
                // The turn's end, and that of any other started meanwhile (from the computer).
                while (!sent) {
                    _state.first { !it.busy && it.canChat }
                    if (l !== link) return@launch
                    sent = sendPrompt(HALT_MESSAGE)
                }
            } finally {
                _state.update {
                    val done = it.copy(halting = false)
                    if (sent) {
                        done
                    } else {
                        Transcript.addNote(done, "The 🛑 wasn't sent: the connection closed first. Send 🛑 when you're connected again.", ChatItem.Severity.Warning)
                    }
                }
            }
        }
    }

    /** Wait, then connect again to the same agent and chat: the tablet should stay reachable. */
    private fun scheduleReconnect(profile: Profile) {
        if (userDisconnected) return
        val wait = reconnectDelaysMs[minOf(retries, reconnectDelaysMs.size - 1)]
        retries++
        _state.update { it.copy(reconnecting = true) }
        retry = appScope.launch {
            kotlinx.coroutines.delay(wait)
            val s = _state.value
            val current = s.profile
            // The user disconnected, something else connected meanwhile, or the setting went off.
            if (userDisconnected || link != null || current?.id != profile.id || !current.offersTablet) {
                _state.update { it.copy(reconnecting = false) }
                return@launch
            }
            open(current, resumeSessionId = s.sessionId, keepTranscript = true, resumeCwd = s.sessionCwd)
        }
    }

    private fun cancelRetry() {
        retry?.cancel()
        retry = null
        retries = 0
        _state.update { if (it.reconnecting) it.copy(reconnecting = false) else it }
    }

    // ------------------------------------------------------------------
    // Prompt turns
    // ------------------------------------------------------------------

    /** Send a message. Returns false if we can't right now (not connected, or a turn is running). */
    fun sendPrompt(text: String, images: List<ImageAttachment> = emptyList()): Boolean {
        val l = link ?: return false
        val s = _state.value
        val sessionId = s.sessionId ?: return false
        if (!s.canChat || s.busy) return false
        if (text.isBlank() && images.isEmpty()) return false
        startPrompt(l, sessionId, text, images)
        return true
    }

    /**
     * Send a reply typed into a notification. It only goes to [sessionId], the chat the
     * notification came from. Returns null when it was sent, otherwise why it wasn't.
     */
    fun sendReply(sessionId: String, text: String): String? {
        val l = link
        val s = _state.value
        val who = if (l?.plasma == true) "PlasmaLLM" else s.agentName
        return when {
            l == null || !s.isConnected -> "DroidACP isn't connected to ${s.agentName} any more."
            text.isBlank() -> "The reply was empty."
            s.sessionId != sessionId -> if (l.plasma) "PlasmaLLM has moved to another chat." else "Another session is open now."
            s.busy -> "$who is already working on a message."
            !s.canChat -> "$who isn't ready for a message yet."
            else -> {
                startPrompt(l, sessionId, text.trim(), emptyList())
                null
            }
        }
    }

    private fun startPrompt(l: Link, sessionId: String, text: String, images: List<ImageAttachment>) {
        val blocks = buildList {
            images.forEach { add(AcpClient.imageBlock(it.mimeType, it.base64)) }
            if (text.isNotBlank()) add(AcpClient.textBlock(text))
        }
        val shown = images.map { ContentBlock.Image(it.mimeType, it.base64, null) }
        _state.update { Transcript.addUser(it, text, shown).copy(busy = true, remoteTurn = false, cancelling = false, approvalElsewhere = emptySet(), toolProgress = emptyMap()) }
        l.ownTurn = sessionId
        // 🛑 typed here holds the tablet before the turn could let it go on.
        l.holdIfAsked(text)
        // "Let the tablet go on" typed here, by the person holding this device.
        l.releaseIfAsked(text, elsewhere = false)
        // The user sent a message: the tablet may go on after a Stop.
        l.userTurn()

        // Notes go to the chat the prompt was sent in; PlasmaLLM may have switched us to another by now.
        fun noteHere(text: String, severity: ChatItem.Severity = ChatItem.Severity.Info) =
            _state.update { if (it.sessionId == sessionId) Transcript.addNote(it, text, severity) else it }

        l.scope.launch {
            var stopReason: String? = null
            // PlasmaLLM's tabs: "cancelled" only because another tab came to the front. The turn
            // goes on in its tab, so it neither "stopped" nor "finished".
            var stillRunning = false
            try {
                val result = l.client.prompt(sessionId, blocks)
                stopReason = result.stopReason
                stillRunning = l.plasma && stopReason == "cancelled" && PlasmaLlm.stillRunning(result.meta)
                if (!stillRunning) Transcript.describeStopReason(stopReason)?.let { noteHere(it) }
            } catch (e: RpcException) {
                if (e.code == RpcErrorCodes.AUTH_REQUIRED && !l.plasma) {
                    _state.update { it.copy(authRequired = true) }
                }
                // PlasmaLLM says why it didn't take the message; the agent never got it.
                val refused = if (l.plasma) PlasmaLlm.refusedPrompt(e.plasmaReason) else null
                val message = if (refused != null) "Your message wasn't sent: $refused" else "Agent error: ${e.message}"
                if (l.plasma && e.plasmaReason == PlasmaLlm.Reason.NOT_OPEN_CHAT) {
                    // The computer has another chat open: show that one instead.
                    l.followHost(e.plasmaSessionId, note = "Your message wasn't sent: PlasmaLLM had switched to this chat.")
                } else {
                    noteHere(message, if (refused != null) ChatItem.Severity.Warning else ChatItem.Severity.Error)
                }
                if (l === link) _events.tryEmit(AgentEvent.PromptFailed(_state.value.agentName, message, unsent = text.takeIf { refused != null && it.isNotBlank() }))
            } catch (e: ConnectionClosedException) {
                // The close handler reports this.
            } finally {
                if (l.ownTurn == sessionId) l.ownTurn = null
                // A turn started elsewhere may be running by now (after PlasmaLLM switched chats), or
                // the user moved to another chat and started one there; leave those alone.
                _state.update {
                    if (it.remoteTurn || it.sessionId != sessionId) it else it.copy(busy = false, cancelling = false, approvalElsewhere = emptySet(), toolProgress = emptyMap())
                }
            }
            // Only report turns that really ended (not ones cut off by a dropped connection, or
            // still running in a PlasmaLLM tab).
            if (stopReason != null && !stillRunning && l === link) {
                val s2 = _state.value
                _events.tryEmit(AgentEvent.TurnFinished(s2.agentName, stopReason, s2.lastAgentText, sessionId))
            }
        }
    }

    /** Ask the agent to stop. Pending permission questions are answered "cancelled", as ACP requires. */
    fun cancelTurn() {
        val l = link ?: return
        val s = _state.value
        val sessionId = s.sessionId ?: return
        if (!s.busy) return
        // For a turn started elsewhere this works too: PlasmaLLM stops it, whoever sent it.
        l.client.cancel(sessionId)
        // One Stop: the agent's use of this tablet stops too, until the next message.
        l.stopTablet()
        cancelAllPermissions()
        _state.update { it.copy(cancelling = true, permissions = emptyList()) }
    }

    fun answerPermission(requestId: Long, optionId: String?) {
        permissionWaiters.remove(requestId)?.complete(PermissionOutcome(optionId))
        _state.update { s -> s.copy(permissions = s.permissions.filterNot { it.requestId == requestId }) }
    }

    // ------------------------------------------------------------------
    // Modes and config options
    // ------------------------------------------------------------------

    fun setMode(modeId: String) {
        val l = link ?: return
        val sessionId = _state.value.sessionId ?: return
        l.scope.launch {
            try {
                l.client.setMode(sessionId, modeId)
                _state.update { it.copy(currentModeId = modeId) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { Transcript.addNote(it, "Couldn't change mode: ${e.message}", ChatItem.Severity.Warning) }
            }
        }
    }

    fun setConfigOption(option: ConfigOption, value: Any) {
        val l = link ?: return
        val sessionId = _state.value.sessionId ?: return
        l.scope.launch {
            try {
                val updated = when (value) {
                    is Boolean -> l.client.setConfigOption(sessionId, option.id, value)
                    else -> l.client.setConfigOption(sessionId, option.id, value.toString())
                }
                _state.update { s ->
                    s.copy(
                        configOptions = updated ?: s.configOptions.map { o ->
                            when {
                                o.id != option.id -> o
                                o is ConfigOption.Select -> o.copy(currentValue = value.toString())
                                o is ConfigOption.Toggle && value is Boolean -> o.copy(currentValue = value)
                                else -> o
                            }
                        },
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { Transcript.addNote(it, "Couldn't change ${option.name}: ${e.message}", ChatItem.Severity.Warning) }
            }
        }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun resetSession(s: ChatState, clearTranscript: Boolean) = s.copy(
        sessionId = null,
        sessionTitle = null,
        items = if (clearTranscript) emptyList() else s.items,
        modes = emptyList(),
        currentModeId = null,
        configOptions = emptyList(),
        commands = emptyList(),
        plans = emptyMap(),
        usage = null,
        permissions = emptyList(),
        busy = false,
        remoteTurn = false,
        cancelling = false,
        approvalElsewhere = emptySet(),
        toolProgress = emptyMap(),
        tabletProgress = null,
    )

    private fun cancelAllPermissions() {
        val waiting = permissionWaiters.values.toList()
        permissionWaiters.clear()
        waiting.forEach { it.complete(PermissionOutcome(null)) }
    }

    private fun record(outgoing: Boolean, text: String) {
        val entry = LogEntry(System.currentTimeMillis(), outgoing, if (text.length > MAX_LOG_CHARS) text.take(MAX_LOG_CHARS) + "…" else text)
        _log.update { (it + entry).takeLast(MAX_LOG_ENTRIES) }
    }

    /**
     * Everything that belongs to one connection. When it closes, its
     * coroutines are cancelled, and callbacks from an old Link are ignored.
     */
    private inner class Link(
        val profile: Profile,
        val resumeSessionId: String?,
        val resumeCwd: String?,
        val preferLoad: Boolean,
        val startNew: Boolean,
    ) : TransportListener, AcpClientHandler {
        private val job = SupervisorJob(appScope.coroutineContext[Job])

        /**
         * When the connection closes, every request still waiting fails with
         * [ConnectionClosedException] (a ping without its pong, on a weak link). A job of this link
         * that ends that way is expected: [onClosed] reconnects. Anything else is a bug, and goes
         * on to the app's own handling, as before (on Android, a crash).
         */
        private val closedHandler = CoroutineExceptionHandler { context, e ->
            if (e is ConnectionClosedException) return@CoroutineExceptionHandler
            val app = appScope.coroutineContext[CoroutineExceptionHandler]
            if (app != null) {
                app.handleException(context, e)
            } else {
                val thread = Thread.currentThread()
                thread.uncaughtExceptionHandler.uncaughtException(thread, e)
            }
        }
        val scope = CoroutineScope(appScope.coroutineContext + job + closedHandler)
        val client = AcpClient(scope, { text -> transport.send(text) }, this, ::record)
        val transport: Transport = transports.create(profile, this)

        private val isCurrent: Boolean get() = link === this

        /** One session/new, load or resume at a time (PlasmaLLM refuses a second one anyway). */
        private val sessionLock = Mutex()

        /** The folder the first session opens in: [resumeSessionId]'s own, if we know it. */
        val startCwd: String get() = if (resumeSessionId != null) resumeCwd ?: profile.cwd else profile.cwd

        /** Chats the user left. Whatever they still send (late updates, permission questions) is ignored. */
        val left: MutableSet<String> = ConcurrentHashMap.newKeySet()

        // --- PlasmaLLM (see PlasmaLlm.kt) ---

        /** True once `initialize` showed the other end is PlasmaLLM, speaking a version we know. */
        @Volatile
        var plasma = false

        /** The chat open in PlasmaLLM, as it last told us (never one with an empty id). */
        @Volatile
        var host: PlasmaChat? = null

        /** The chat a prompt we sent is running in (so PlasmaLLM's turn notifications for it are ours, not remote). */
        @Volatile
        var ownTurn: String? = null

        /**
         * A person started the running turn (`_plasmallm/turn` `by: "user"`, which comes before
         * its message); false between turns and for one PlasmaLLM or Claude started by itself.
         * Only then can a message let a held tablet go on (D23).
         */
        @Volatile
        private var turnByUser = false

        /** The first item key of the remote turn running, to tell whether it showed anything but notes. */
        @Volatile
        private var remoteTurnFrom = 0L

        /** Following the computer's chat failed this many times in a row; we stop trying after a few. */
        private var followFailures = 0

        /**
         * The chat on screen, and its title, when PlasmaLLM said its turn goes on in its tab
         * while another tab came to the front: the note about moving says so.
         */
        @Volatile
        private var leftRunning: Pair<String, String?>? = null

        /** Said once the computer's chat is shown again, after an open that didn't happen. */
        @Volatile
        private var noteAfterFollow: String? = null

        /** Permission requests waiting here, by tool call, so `_plasmallm/permission_resolved` can close them. */
        private val permissionTools = ConcurrentHashMap<Long, String>()

        // --- This tablet, offered to PlasmaLLM (TabletRelay.kt) ---

        /** For any PlasmaLLM agent on a device with TabDesk, so the setting can go on while connected. */
        val relay: TabletRelay? = tablet?.takeIf { profile.isPlasmaLlm }?.let {
            TabletRelay(
                it.link, it.serverId, "c${nextConnection.getAndIncrement()}", allowed = profile.useTablet,
                held = { it.held.value }, queued = { transport.queuedBytes },
            )
        }

        /** A session was opened (or tried) on this connection, so later declarations need a resume of their own. */
        @Volatile
        private var opened = false

        /** TabDesk's control state as last seen, to notice it changing to `stopped`. */
        @Volatile
        private var lastControl: String? = null

        fun shutdown() {
            transport.close()
        }

        override fun onOpen() {
            scope.launch { initialize() }
        }

        override fun onMessage(text: String) {
            if (isCurrent) client.receive(text)
        }

        override fun onClosed(reason: String, error: Throwable?) {
            client.close(reason)
            // Also cancels the tablet calls running, at TabDesk too.
            job.cancel()
            if (!isCurrent) return
            link = null
            cancelAllPermissions()
            val wasConnected = _state.value.isConnected
            // An edit saved while connected counts from now on, for a reconnect too.
            val current = savedWhileConnected?.takeIf { it.id == _state.value.profile?.id } ?: _state.value.profile
            savedWhileConnected = null
            // The tablet's relay needs the connection: make it again by itself, unless the other
            // end refused it (a rejected token, an invalid address), which trying again won't change.
            val again = current != null && current.id == profile.id && current.offersTablet && tablet != null &&
                error !is ConnectionRefusedException && !userDisconnected
            val wasRetrying = retries > 0
            _state.update {
                it.copy(
                    status = ConnectionStatus.Failed(reason),
                    profile = current ?: it.profile,
                    busy = false,
                    remoteTurn = false,
                    cancelling = false,
                    startingSession = false,
                    permissions = emptyList(),
                    openElsewhere = null,
                    tablet = it.tablet?.copy(offered = false, declared = false, calls = 0),
                    approvalElsewhere = emptySet(),
                    toolProgress = emptyMap(),
                    tabletProgress = null,
                    // With the failure, so nothing sees the connection as over while a reconnect waits
                    // (the foreground service would stop).
                    reconnecting = again,
                )
            }
            // A drop it recovers from by itself needs no alert (the ongoing notification says it's
            // connecting again); one it gives up on does, also after reconnecting for a while.
            if ((wasConnected || wasRetrying) && !again) _events.tryEmit(AgentEvent.ConnectionLost(_state.value.agentName, reason))
            if (again) scheduleReconnect(current!!) else retries = 0
        }

        private suspend fun initialize() {
            try {
                val companion = profile.isPlasmaLlm
                val init = client.initialize(
                    clientName = "droidacp",
                    clientTitle = "DroidACP",
                    clientVersion = clientVersion,
                    meta = if (companion) PlasmaLlm.initializeMeta() else null,
                )
                val hello = init.plasmaLlm?.takeIf { companion && it.protocol >= 1 }
                plasma = hello != null
                host = hello?.chat?.takeIf { it.sessionId.isNotEmpty() }
                relay?.offered = hello != null && hello.protocol >= PlasmaLlm.TABLET_PROTOCOL && init.capabilities.mcpOverAcp
                watchTablet()
                _state.update {
                    it.copy(
                        status = ConnectionStatus.Connected,
                        agent = init.agent,
                        capabilities = init.capabilities,
                        authMethods = init.authMethods,
                        plasmaLlm = hello,
                    )
                }
                // A list open on screen: PlasmaLLM only says when it changes after this connection reads it.
                if (plasma) _state.value.todo?.let { readTodo(it.cwd) }
                if (init.protocolVersion != AcpClient.PROTOCOL_VERSION.toLong()) {
                    _state.update {
                        Transcript.addNote(
                            it,
                            "This agent speaks ACP v${init.protocolVersion}; DroidACP speaks v${AcpClient.PROTOCOL_VERSION}. Some things may not work.",
                            ChatItem.Severity.Warning,
                        )
                    }
                }
                if (companion && hello == null) {
                    _state.update {
                        Transcript.addNote(
                            it,
                            "This computer answered, but not as PlasmaLLM, so DroidACP talks to it as a plain ACP agent. " +
                                "Is it the bridge (port 8765) instead of PlasmaLLM's phone access (port 8766)?",
                            ChatItem.Severity.Warning,
                        )
                    }
                }
                val joined = host
                when {
                    // PlasmaLLM: "Start a new session" makes a new chat on the computer.
                    plasma && startNew -> startSession(target = null, preferLoad = false, cwd = profile.cwd)
                    // PlasmaLLM: join the chat open on the computer (load gives us its history).
                    plasma && joined != null -> {
                        // Back after a dropped connection, and the computer has moved on: say so.
                        val shown = _state.value.sessionId
                        startSession(target = joined.sessionId, preferLoad = true, cwd = joined.cwd)
                        if (shown != null && shown != joined.sessionId) noteMoved(joined.sessionId)
                    }
                    plasma -> startSession(target = null, preferLoad = false, cwd = profile.cwd)
                    else -> startSession(resumeSessionId, preferLoad, cwd = startCwd)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (isCurrent) {
                    _state.update { Transcript.addNote(it, "Couldn't start: ${e.message}", ChatItem.Severity.Error) }
                    transport.close()
                }
            }
        }

        /** The folder to list past sessions for: PlasmaLLM's open folder, else the open chat's, else the profile's. */
        fun listCwd(): String =
            if (plasma) host?.cwd?.ifEmpty { null } ?: profile.cwd else _state.value.sessionCwd?.ifEmpty { null } ?: profile.cwd

        /**
         * Open a session. [target] null = new session. For an existing one,
         * [preferLoad] picks session/load (replays history) over session/resume. [meta] goes
         * into a load or resume's `_meta` (the answer to PlasmaLLM's open_elsewhere). [agent]:
         * a new chat in a new PlasmaLLM tab with that agent (protocol 15).
         */
        suspend fun startSession(target: String?, preferLoad: Boolean, cwd: String, meta: JsonObject? = null, agent: PlasmaAgent? = null) {
            sessionLock.withLock { openSession(target, preferLoad, cwd, meta, agent) }
            publishTablet()
            // TabDesk's approval may have come while it opened, too late for that request.
            if (relay?.let { it.declarable && !it.declared } == true) declareAgain()
            if (needsFollow()) followHost()
        }

        private suspend fun openSession(target: String?, preferLoad: Boolean, cwd: String, meta: JsonObject? = null, agent: PlasmaAgent? = null) {
            // Going back to a chat we left: its messages count again.
            if (target != null) left.remove(target)
            val caps = _state.value.capabilities
            _state.update { it.copy(startingSession = true, authRequired = false) }
            // The tablet goes with every new chat, load and resume while it may be offered.
            val servers = relay?.mcpServers().orEmpty()
            try {
                val setup: SessionSetup = when {
                    target == null && agent != null -> client.newSession(cwd, servers, PlasmaLlm.newTabMeta(agent.id))
                    target == null -> client.newSession(cwd, servers)
                    // PlasmaLLM always takes session/load, whatever its agent says.
                    plasma || (caps.loadSession && (preferLoad || !caps.resumeSession)) -> {
                        // History is replayed as session/update notifications, so start clean.
                        // PlasmaLLM told us the chat's title already (the open chat's, or one of
                        // its tabs'); show it while it loads.
                        val title = if (!plasma) {
                            null
                        } else {
                            host?.takeIf { it.sessionId == target }?.title
                                ?: _state.value.plasmaLlm?.tabs?.firstOrNull { it.sessionId == target }?.title
                        }
                        _state.update { it.copy(sessionId = target, sessionTitle = title ?: it.sessionTitle, replaying = true, items = emptyList()) }
                        client.loadSession(target, cwd, meta, servers)
                    }
                    caps.resumeSession -> {
                        _state.update { it.copy(sessionId = target) }
                        client.resumeSession(target, cwd, meta, servers)
                    }
                    else -> {
                        _state.update { Transcript.addNote(resetSession(it, clearTranscript = false), "This agent can't continue old sessions, so a new one was started.") }
                        client.newSession(cwd, servers)
                    }
                }
                if (plasma) {
                    // We just opened this chat, so PlasmaLLM has it open now, whatever older state said.
                    val old = host
                    host = if (old?.sessionId == setup.sessionId) old else PlasmaChat(setup.sessionId, cwd.ifEmpty { old?.cwd ?: "" }, null, false)
                    followFailures = 0
                }
                // Connected and in a chat: the next drop waits the shortest time again.
                retries = 0
                _state.update {
                    it.copy(
                        sessionId = setup.sessionId,
                        sessionTitle = host?.takeIf { h -> plasma && h.sessionId == setup.sessionId }?.title ?: it.sessionTitle,
                        sessionCwd = cwd.ifEmpty { null } ?: host?.cwd?.takeIf { plasma },
                        modes = setup.modes,
                        currentModeId = setup.currentModeId,
                        configOptions = setup.configOptions ?: it.configOptions,
                        authRequired = false,
                        // A chat is open: whatever tab had no chat isn't the one shown any more.
                        tabProblem = null,
                    )
                }
            } catch (e: RpcException) {
                val elsewhere = if (plasma && target != null) OpenElsewhere.parse(e, target, cwd) else null
                if (e.code == RpcErrorCodes.AUTH_REQUIRED && !plasma) {
                    _state.update { it.copy(authRequired = true, sessionId = null) }
                } else if (elsewhere != null) {
                    // Another Claude has that chat open, and PlasmaLLM stayed on its own chat:
                    // show that one again (followHost, below) and ask what to do
                    // (answerOpenElsewhere). Like not_found, it isn't a failure to follow.
                    _state.update { resetSession(it, clearTranscript = false).copy(openElsewhere = elsewhere) }
                    val stays = e.plasmaSessionId
                    if (stays != null && host?.sessionId != stays) host = PlasmaChat(stays, host?.cwd ?: "", null, false)
                } else if (plasma && agent != null) {
                    tabFailed(agent, e)
                } else if (plasma && e.plasmaReason == PlasmaLlm.Reason.NOT_FOUND) {
                    // The chat is gone, and PlasmaLLM stayed on the chat it had open: show that one
                    // again (followHost, below). The error names the chat asked for, not one opened
                    // instead, so it isn't followed, and it isn't a failure to follow the computer.
                    _state.update { resetSession(it, clearTranscript = false) }
                    noteAfterFollow = "That chat no longer exists, so PlasmaLLM stayed on this one."
                } else if (plasma) {
                    // No fallback to a new chat here: PlasmaLLM has already opened one if it
                    // could (the error names it, and _plasmallm/state announces it); we follow that.
                    followFailures++
                    val what = if (target == null) "start a new chat" else "open that chat"
                    _state.update { Transcript.addNote(resetSession(it, clearTranscript = false), "PlasmaLLM couldn't $what: ${e.message}", ChatItem.Severity.Warning) }
                    val instead = e.plasmaSessionId
                    if (instead != null && host?.sessionId != instead) host = PlasmaChat(instead, cwd, null, false)
                } else if (target != null) {
                    // Couldn't reopen it (maybe it expired) — fall back to a fresh session.
                    _state.update { Transcript.addNote(resetSession(it, clearTranscript = false), "Couldn't reopen the session (${e.message}); starting a new one.", ChatItem.Severity.Warning) }
                    _state.update { it.copy(startingSession = false, replaying = false) }
                    openSession(null, preferLoad = false, cwd = cwd)
                    return
                } else {
                    _state.update { Transcript.addNote(it, "Couldn't create a session: ${e.message}", ChatItem.Severity.Error) }
                }
            } finally {
                // PlasmaLLM takes a declaration whatever happens to the request it came with.
                if (servers.isNotEmpty()) relay?.declared = true
                opened = true
                _state.update { it.copy(startingSession = false, replaying = false, startingAgent = null) }
            }
        }

        /**
         * A new tab with [agent] didn't give a chat (protocol 15). With a `tabId` the tab opened and
         * stays open on the computer, in front, showing the problem: the phone shows it too
         * ([TabProblem]) and follows nothing until PlasmaLLM names a chat in front again (the tab
         * closed, or its agent started after all). Without one, nothing opened, and the phone goes
         * back to the chat the computer stayed on, saying why.
         */
        private fun tabFailed(agent: PlasmaAgent, e: RpcException) {
            val tabId = e.plasmaTabId
            val reason = e.plasmaReason
            val message = e.message?.ifBlank { null } ?: "PlasmaLLM couldn't start ${agent.name}."
            if (tabId != null) {
                host = null
                _state.update {
                    resetSession(it, clearTranscript = false).copy(
                        tabProblem = TabProblem(tabId, agent.name, message, signIn = reason == PlasmaLlm.Reason.SIGN_IN_NEEDED),
                    )
                }
                return
            }
            val note = "PlasmaLLM couldn't open a tab with ${agent.name}: $message"
            _state.update { resetSession(it, clearTranscript = false) }
            if (host != null) {
                noteAfterFollow = note
            } else {
                _state.update { Transcript.addNote(it, note, ChatItem.Severity.Warning) }
            }
        }

        /**
         * Offer the tablet on this connection now, with a `session/resume` that only declares
         * it (protocol 2): the setting went on, or TabDesk's approval came, after the chat
         * opened. It names the chat of PlasmaLLM's latest state, and never opens another.
         */
        private suspend fun declareAgain() {
            val r = relay ?: return
            var other: String? = null
            sessionLock.withLock {
                if (!plasma || !opened || r.declared || !r.declarable) return@withLock
                val chat = host ?: return@withLock
                try {
                    client.resumeSession(chat.sessionId, chat.cwd, PlasmaLlm.declareOnlyMeta(), r.mcpServers())
                } catch (e: RpcException) {
                    // It counts whatever the answer. not_open_chat: the laptop has another chat open
                    // by now, which the phone follows as usual.
                    if (e.plasmaReason == PlasmaLlm.Reason.NOT_OPEN_CHAT) other = e.plasmaSessionId
                } catch (e: ConnectionClosedException) {
                    // Gone meanwhile; the next connection declares it with its load.
                    return
                }
                r.declared = true
            }
            publishTablet()
            if (other != null) followHost(other) else if (needsFollow()) followHost()
        }

        /** The setting changed (see [AgentController.setTabletAllowed]). */
        fun tabletAllowed(on: Boolean) {
            val r = relay ?: return
            r.setAllowed(on)
            publishTablet()
            if (on) startDeclaring()
        }

        /**
         * Asks to declare again. TabDesk's state can change often while one runs: they run one
         * at a time, and asks made meanwhile make one more run after it, never a missed one.
         */
        private val declareAsks = Channel<Unit>(Channel.CONFLATED)
        private val declarer by lazy { scope.launch { for (ask in declareAsks) declareAgain() } }

        private fun startDeclaring() {
            declarer.start()
            declareAsks.trySend(Unit)
        }

        /** DroidACP's Stop: the agent's use of the tablet stops too, until the user's next message. */
        fun stopTablet() {
            val r = relay ?: return
            if (r.allowed && r.declared) tablet?.link?.stop()
        }

        /**
         * A turn a person sent, here or elsewhere: the tablet may go on after a Stop or a pause.
         * Never for one PlasmaLLM or the agent started by itself (`by: "app"`).
         */
        fun userTurn() {
            val r = relay ?: return
            val t = tablet ?: return
            // On hold, only the user's "let it" lifts it, never a message.
            if (t.held.value) return
            if (r.allowed && r.declared) t.link.resume()
        }

        /** The agent is using the tablet now: a call running, or TabDesk says it's active. */
        fun tabletInUse(): Boolean {
            val r = relay ?: return false
            return r.calls.value > 0 || tablet?.link?.status?.value?.control == TabletStatus.ACTIVE
        }

        /**
         * A message that holds the tablet (`TabletRelay.isHoldMessage`), on the device whose
         * tablet it is: this one offers it. Notes from another tab's agent don't count.
         */
        fun holdIfAsked(text: String) {
            if (relay?.allowed != true || tablet?.held?.value != false) return
            if (PlasmaLlm.isAgentNote(text) || !TabletRelay.isHoldMessage(text)) return
            hold(cancelTurnUsingIt = false)
        }

        /**
         * A message that lets the tablet go on after a hold (`TabletRelay.isReleaseMessage`, D23),
         * which a person sent: typed here, or [elsewhere] in a turn PlasmaLLM says a person
         * started. On the device whose tablet it is, as for a hold.
         */
        fun releaseIfAsked(text: String, elsewhere: Boolean) {
            if (relay?.allowed != true || tablet?.held?.value != true) return
            if (PlasmaLlm.isAgentNote(text) || !TabletRelay.isReleaseMessage(text)) return
            releaseTablet()
            val where = if (elsewhere) "by your message from another device" else "by your message"
            _state.update { Transcript.addNote(it, "Let go on $where: the agent can use this tablet again.") }
        }

        /** The tablet was put on hold: running calls end refused, and the bar says so. */
        fun tabletHeld() {
            relay?.hold()
            publishTablet()
        }

        /**
         * The hold was lifted: TabDesk lets the agent go on again, as after a Stop. Whenever
         * TabDesk is connected, declared to PlasmaLLM yet or not, so it isn't left stopped
         * from the hold.
         */
        fun tabletReleased() {
            val r = relay ?: return
            val t = tablet ?: return
            if (r.allowed && t.link.status.value.connected) t.link.resume()
            publishTablet()
        }

        /** Follow TabDesk's state and the calls running, for the bar, Stop, and declaring. */
        private fun watchTablet() {
            val r = relay ?: return
            val t = tablet ?: return
            if (!r.offered) return
            lastControl = t.link.status.value.control
            scope.launch { combine(t.link.status, r.calls) { status, _ -> status }.collect { onTablet(it) } }
        }

        private fun onTablet(status: TabletStatus) {
            val r = relay ?: return
            if (!isCurrent) return
            val before = lastControl
            lastControl = status.control
            // Stop on TabDesk's indicator stops the agent's turn too, and so does a pause: the user
            // touched the screen (D11). (`caller` is our own Stop coming back.)
            val stoppedThere = status.control == TabletStatus.STOPPED && status.reason == TabletStatus.BY_TABDESK
            val paused = status.control == TabletStatus.PAUSED
            if ((stoppedThere || paused) && before != status.control && r.allowed) {
                stoppedOnTablet(r)
            }
            publishTablet()
            if (r.declarable && !r.declared && opened) startDeclaring()
        }

        /**
         * Stop was pressed on TabDesk, or it paused because the user touched the screen: cancel
         * the turn that used the tablet, the one the last call came from. Before protocol 13, PlasmaLLM cancels only the chat it has in front,
         * so a back tab's turn goes on there (TabDesk still refuses its calls).
         */
        private fun stoppedOnTablet(r: TabletRelay) {
            val s = _state.value
            val shown = s.sessionId ?: return
            val from = r.lastSessionId ?: shown
            if (from == shown) {
                if (!s.busy) return
                client.cancel(shown)
                cancelAllPermissions()
                _state.update { it.copy(cancelling = true, permissions = emptyList()) }
            } else if ((s.plasmaLlm?.protocol ?: 0) >= PlasmaLlm.CANCEL_ANY_TAB && s.plasmaLlm?.tabs?.any { it.sessionId == from } == true) {
                // Another tab's turn: PlasmaLLM stops it in its tab, and this chat goes on.
                client.cancel(from)
            }
        }

        fun publishTablet() {
            val r = relay ?: return
            val t = tablet ?: return
            if (!isCurrent) return
            _state.update { s ->
                s.copy(tablet = if (r.allowed) TabletUse(t.link.status.value, r.offered, r.declared, r.calls.value, t.held.value) else null)
            }
        }

        /**
         * PlasmaLLM: if the computer has another chat open than the one we show, open that
         * one too (the phone is a second screen). [sessionId] is a chat an error just named.
         * [note] is added once that chat is open.
         */
        fun followHost(sessionId: String? = null, note: String? = null) {
            if (!plasma) return
            if (sessionId != null && host?.sessionId != sessionId) host = PlasmaChat(sessionId, host?.cwd ?: "", null, false)
            scope.launch {
                sessionLock.withLock {
                    val target = host ?: return@withLock
                    // Leaving a chat on screen, not one that just failed to open (that says why itself).
                    // A message of ours on its way explains it too: PlasmaLLM refuses it ("wasn't sent").
                    var moved = false
                    var stillWorking: Pair<String, String?>? = null
                    if (target.sessionId != _state.value.sessionId && followFailures < MAX_FOLLOW_FAILURES) {
                        _state.value.sessionId?.let {
                            left += it
                            moved = ownTurn == null
                            stillWorking = leftRunning?.takeIf { r -> r.first == it }
                        }
                        leftRunning = null
                        _state.update { resetSession(it, clearTranscript = true) }
                        openSession(target.sessionId, preferLoad = true, cwd = target.cwd)
                    }
                    // Also when a state notification got us there first.
                    val shown = note ?: noteAfterFollow
                    if (shown != null && _state.value.sessionId == target.sessionId) {
                        noteAfterFollow = null
                        _state.update { Transcript.addNote(it, shown, ChatItem.Severity.Warning) }
                    } else if (moved) {
                        noteMoved(target.sessionId, stillWorking)
                    }
                }
                // The computer may have moved on again while we were loading.
                if (needsFollow()) followHost()
            }
        }

        /**
         * The phone moved to [sessionId] because the computer did, from another chat it
         * showed: say so, or the chat seems to change by itself (another device may have
         * opened it). [stillWorking] is the chat left (id and title) when its turn goes on
         * in its PlasmaLLM tab.
         */
        private fun noteMoved(sessionId: String, stillWorking: Pair<String, String?>? = null) {
            val s = _state.value
            if (s.sessionId != sessionId) return
            val title = host?.takeIf { it.sessionId == sessionId }?.title ?: s.sessionTitle
            val what = title?.let { "“$it”" } ?: "another chat"
            val working = stillWorking?.let { (_, left) ->
                " " + (left?.let { "“$it”" } ?: "The chat you were on") + " is still working in its tab."
            } ?: ""
            _state.update { Transcript.addNote(it, "The computer is on $what now, so this screen moved there too.$working") }
        }

        private fun needsFollow(): Boolean {
            val h = host ?: return false
            return plasma && h.sessionId != _state.value.sessionId && followFailures < MAX_FOLLOW_FAILURES
        }

        // --- AcpClientHandler ---

        override fun onSessionUpdate(sessionId: String, update: SessionUpdate) {
            if (!isCurrent || sessionId in left) return
            // 🛑 typed on another device (the phone, the laptop, the Claude app). Not one replayed
            // from the chat's history, which would hold the tablet each time the chat opens.
            if (update is SessionUpdate.UserMessageChunk && !_state.value.replaying) {
                (update.content as? ContentBlock.Text)?.text?.let {
                    holdIfAsked(it)
                    if (turnByUser) releaseIfAsked(it, elsewhere = true)
                }
            }
            _state.update { s ->
                // Accept updates for our session, or before we know our session id (they can race the response).
                if (s.sessionId == null || s.sessionId == sessionId) Transcript.apply(s, update) else s
            }
        }

        override fun onExtNotification(method: String, params: JsonObject) {
            if (!isCurrent || !plasma) return
            when (val event = PlasmaEvent.parse(method, params)) {
                is PlasmaEvent.State -> onHostState(event.chat, event.tabletTools, event.tabs)
                is PlasmaEvent.Turn -> onHostTurn(event)
                is PlasmaEvent.TodoChanged -> if (_state.value.todo?.let { samePath(it.cwd, event.cwd) } == true) refreshTodo()
                is PlasmaEvent.PermissionResolved -> {
                    // Answered on the computer or another device (or dropped): close ours. Our
                    // "cancelled" reply is ignored by PlasmaLLM; it doesn't stop the turn.
                    permissionTools.filterValues { it == event.toolCallId }.keys.forEach { answerPermission(it, null) }
                    _state.update { it.copy(approvalElsewhere = it.approvalElsewhere - event.toolCallId) }
                }
                is PlasmaEvent.PermissionElsewhere -> {
                    // An action on this tablet, approved on the phone or the laptop instead.
                    if (event.sessionId in left) return
                    _state.update { s ->
                        if (s.sessionId == null || s.sessionId == event.sessionId) s.copy(approvalElsewhere = s.approvalElsewhere + event.toolCallId) else s
                    }
                }
                // The newest text replaces the last. PlasmaLLM sends the tablet another tab's too (protocol
                // 14): its notification is the only sign of a copy while the screen is off.
                is PlasmaEvent.ToolProgress -> when {
                    event.sessionId == _state.value.sessionId -> _state.update { s ->
                        if (s.sessionId != event.sessionId) s else s.copy(toolProgress = s.toolProgress + (event.toolCallId to event.text))
                    }
                    relay?.declared == true -> showTabletProgress(event.text)
                }
                null -> {}
            }
        }

        /** Clears [ChatState.tabletProgress] once no new line has come for a while. */
        private var progressExpiry: Job? = null

        /**
         * Another tab's progress, for this tablet's notification. That tab's chat isn't shown here,
         * so its tool call's end isn't seen: the line goes when PlasmaLLM stops sending it.
         */
        private fun showTabletProgress(text: String) {
            _state.update { it.copy(tabletProgress = text) }
            progressExpiry?.cancel()
            progressExpiry = appScope.launch {
                kotlinx.coroutines.delay(progressTimeoutMs)
                _state.update { if (it.tabletProgress == text) it.copy(tabletProgress = null) else it }
            }
        }

        // --- PlasmaLLM's requests ---

        override suspend fun mcpMessage(params: JsonObject): JsonElement {
            val r = relay?.takeIf { isCurrent && plasma } ?: throw RpcException(PlasmaLlm.TABLET_UNAVAILABLE, "this device offers no tablet")
            return r.call(params)
        }

        private fun onHostState(chat: PlasmaChat, tabletTools: Boolean?, tabs: List<PlasmaTab>?) {
            // An empty id means PlasmaLLM is switching chats, and the next state names the new one;
            // or (protocol 15) the tab in front has no chat: its agent is starting, or couldn't.
            // Nothing to follow, but the tabs count: the starting one is listed.
            val agentBefore = _state.value.frontAgentId
            if (chat.sessionId.isEmpty()) {
                _state.update { s ->
                    s.copy(plasmaLlm = s.plasmaLlm?.copy(tabletTools = tabletTools ?: s.plasmaLlm.tabletTools, tabs = tabs ?: s.plasmaLlm.tabs))
                }
                relistIfAgentChanged(agentBefore)
                return
            }
            host = chat
            _state.update { s ->
                s.copy(
                    plasmaLlm = s.plasmaLlm?.copy(
                        chat = chat,
                        tabletTools = tabletTools ?: s.plasmaLlm.tabletTools,
                        tabs = tabs ?: s.plasmaLlm.tabs,
                    ),
                    sessionTitle = if (s.sessionId == chat.sessionId && chat.title != null) chat.title else s.sessionTitle,
                )
            }
            relistIfAgentChanged(agentBefore)
            if (needsFollow()) followHost()
        }

        /**
         * Each agent keeps its own chats (protocol 15), and `session/list` asks the front tab's: when
         * a tab with another agent comes to the front, the chats listed are the other agent's, so
         * the old list goes, and the folder is listed again.
         */
        private fun relistIfAgentChanged(agentBefore: String?) {
            val now = _state.value
            val agentNow = now.frontAgentId
            val cwd = now.pastSessionsCwd
            if (agentBefore == null || agentNow == null || agentBefore == agentNow || cwd == null) return
            _state.update { it.copy(pastSessions = null, pastSessionsError = null) }
            refreshPastSessions(cwd)
        }

        private fun onHostTurn(turn: PlasmaEvent.Turn) {
            // Set for the turn's own message, which follows; cleared when it ends, so a message
            // whose turn wasn't said to be a person's never counts as one.
            turnByUser = turn.byUser
            // Whichever chat it's in: a message a person sent lets the tablet go on after a Stop.
            if (turn.byUser) userTurn()
            val s = _state.value
            // Only turns in the chat we show (or are opening). A turn "ended" for a chat the
            // computer just left is followed by a state for the new one.
            if (s.sessionId != null && s.sessionId != turn.sessionId) return
            if (turn.sessionId in left) return
            if (turn.started) {
                // Our own prompt: sendPrompt already shows it as running.
                if (ownTurn == turn.sessionId) return
                remoteTurnFrom = s.nextKey
                _state.update { it.copy(busy = true, remoteTurn = true, cancelling = false) }
                return
            }
            if (turn.stillRunning) {
                // Another PlasmaLLM tab came to the front; this turn goes on in its tab, and the
                // state naming the front tab's chat comes next. Not "Stopped.", not "finished":
                // the note about moving says it's still working. Our own prompt in it is over for
                // us now (it's answered "cancelled"), so the move isn't put down to a refusal.
                leftRunning = turn.sessionId to s.sessionTitle
                if (ownTurn == turn.sessionId) ownTurn = null
                _state.update { it.copy(busy = false, remoteTurn = false, cancelling = false, approvalElsewhere = emptySet(), toolProgress = emptyMap()) }
                return
            }
            // Our own turn ends with the session/prompt answer instead.
            if (!s.remoteTurn) return
            val reason = turn.stopReason ?: "end_turn"
            _state.update { st ->
                val ended = st.copy(busy = false, remoteTurn = false, cancelling = false, approvalElsewhere = emptySet(), toolProgress = emptyMap())
                Transcript.describeStopReason(reason)?.let { Transcript.addNote(ended, it) } ?: ended
            }
            val after = _state.value
            // A turn that only showed a note (PlasmaLLM's Remote Control switch) isn't worth a
            // "finished", and nor is one answering notes between agents (not for the user).
            val turnItems = after.items.filter { it.key >= remoteTurnFrom }
            if (turnItems.all { it is ChatItem.Note }) return
            if (turnItems.any { it is ChatItem.User && it.agentNote }) return
            _events.tryEmit(AgentEvent.TurnFinished(after.agentName, reason, after.lastAgentText, turn.sessionId))
        }

        override suspend fun requestPermission(request: PermissionRequest): PermissionOutcome {
            // A question from a chat the user left must never show over the next one.
            if (!isCurrent || request.sessionId in left) return PermissionOutcome(null)
            val id = nextPermissionId.getAndIncrement()
            val answer = CompletableDeferred<PermissionOutcome>()
            permissionWaiters[id] = answer
            permissionTools[id] = request.toolCall.id

            _state.update { s ->
                val withTool = Transcript.applyToolPatch(s, request.toolCall)
                val tool = withTool.items.lastOrNull { it is ChatItem.Tool && it.call.id == request.toolCall.id } as? ChatItem.Tool
                withTool.copy(
                    permissions = withTool.permissions + PendingPermission(
                        requestId = id,
                        toolCallId = request.toolCall.id,
                        title = (request.toolCall.title ?: tool?.call?.title)?.let(ToolNames::display) ?: "The agent wants to use a tool",
                        kind = request.toolCall.kind ?: tool?.call?.kind ?: "other",
                        options = request.options,
                    ),
                )
            }
            _events.tryEmit(AgentEvent.PermissionRequested(_state.value.agentName, request.toolCall.title?.let(ToolNames::display) ?: "Permission needed"))

            return try {
                answer.await()
            } finally {
                permissionWaiters.remove(id)
                permissionTools.remove(id)
                _state.update { s -> s.copy(permissions = s.permissions.filterNot { it.requestId == id }) }
            }
        }
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 400
        private const val MAX_LOG_CHARS = 4000

        /** Give up following PlasmaLLM's chat after this many failed opens in a row (reconnecting resets it). */
        private const val MAX_FOLLOW_FAILURES = 3

        /** What 🛑 in the message box sends once the turn has stopped: a hold, by D21's rule. */
        const val HALT_MESSAGE = "🛑"

        /** The waits before connecting again by itself, growing; the last one repeats. */
        /** PlasmaLLM sends progress at most once a second; this long without one, another tab's copy is over. */
        const val PROGRESS_TIMEOUT_MS = 5_000L

        val RECONNECT_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L)

        /** Convenience for tests and simple setups: bridge agents only. */
        fun create(scope: CoroutineScope, version: String = "dev"): AgentController {
            val http = WebSocketTransport.defaultClient()
            return AgentController(scope, version) { profile, listener -> WebSocketTransport(http, profile.url, profile.token, listener) }
        }
    }
}
