package com.droidacp.core.chat

import com.droidacp.core.acp.AcpClient
import com.droidacp.core.acp.AcpClientHandler
import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.OpenElsewhere
import com.droidacp.core.acp.OpenElsewhereChoice
import com.droidacp.core.acp.PermissionOutcome
import com.droidacp.core.acp.PermissionRequest
import com.droidacp.core.acp.PlasmaChat
import com.droidacp.core.acp.PlasmaEvent
import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.acp.SessionSetup
import com.droidacp.core.acp.SessionUpdate
import com.droidacp.core.acp.Transport
import com.droidacp.core.acp.TransportListener
import com.droidacp.core.acp.WebSocketTransport
import com.droidacp.core.acp.plasmaReason
import com.droidacp.core.acp.plasmaSessionId
import com.droidacp.core.rpc.ConnectionClosedException
import com.droidacp.core.rpc.RpcErrorCodes
import com.droidacp.core.rpc.RpcException
import com.droidacp.core.tablet.Tablet
import com.droidacp.core.tablet.TabletRelay
import com.droidacp.core.tablet.TabletStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 */
class AgentController(
    private val appScope: CoroutineScope,
    private val clientVersion: String,
    private val tablet: Tablet? = null,
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
        val old = link
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
                tablet = tablet?.takeIf { profile.offersTablet }?.let { TabletUse(it.link.status.value) },
                approvalElsewhere = emptySet(),
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
        val l = link ?: return
        link = null
        l.shutdown()
        tablet?.link?.setWanted(false)
        cancelAllPermissions()
        _state.update {
            it.copy(
                status = ConnectionStatus.Disconnected,
                busy = false,
                remoteTurn = false,
                cancelling = false,
                startingSession = false,
                permissions = emptyList(),
                tablet = null,
                approvalElsewhere = emptySet(),
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
        _state.update {
            it.copy(
                profile = updated,
                tablet = tablet?.takeIf { updated.offersTablet }?.let { t -> it.tablet ?: TabletUse(t.link.status.value) },
            )
        }
        // Off: the running calls end as refused (-33001) before TabDesk is let go of.
        if (!updated.offersTablet) link?.tabletAllowed(false)
        tablet?.link?.setWanted(updated.offersTablet)
        if (updated.offersTablet) link?.tabletAllowed(true)
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
        _state.update { Transcript.addUser(it, text, shown).copy(busy = true, remoteTurn = false, cancelling = false, approvalElsewhere = emptySet()) }
        l.ownTurn = sessionId
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
                    if (it.remoteTurn || it.sessionId != sessionId) it else it.copy(busy = false, cancelling = false, approvalElsewhere = emptySet())
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
        val scope = CoroutineScope(appScope.coroutineContext + job)
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
            TabletRelay(it.link, it.serverId, "c${nextConnection.getAndIncrement()}", allowed = profile.useTablet)
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
            _state.update {
                it.copy(
                    status = ConnectionStatus.Failed(reason),
                    busy = false,
                    remoteTurn = false,
                    cancelling = false,
                    startingSession = false,
                    permissions = emptyList(),
                    openElsewhere = null,
                    tablet = it.tablet?.copy(offered = false, declared = false, calls = 0),
                    approvalElsewhere = emptySet(),
                )
            }
            if (wasConnected) _events.tryEmit(AgentEvent.ConnectionLost(_state.value.agentName, reason))
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
         * into a load or resume's `_meta` (the answer to PlasmaLLM's open_elsewhere).
         */
        suspend fun startSession(target: String?, preferLoad: Boolean, cwd: String, meta: JsonObject? = null) {
            sessionLock.withLock { openSession(target, preferLoad, cwd, meta) }
            publishTablet()
            // TabDesk's approval may have come while it opened, too late for that request.
            if (relay?.let { it.declarable && !it.declared } == true) declareAgain()
            if (needsFollow()) followHost()
        }

        private suspend fun openSession(target: String?, preferLoad: Boolean, cwd: String, meta: JsonObject? = null) {
            // Going back to a chat we left: its messages count again.
            if (target != null) left.remove(target)
            val caps = _state.value.capabilities
            _state.update { it.copy(startingSession = true, authRequired = false) }
            // The tablet goes with every new chat, load and resume while it may be offered.
            val servers = relay?.mcpServers().orEmpty()
            try {
                val setup: SessionSetup = when {
                    target == null -> client.newSession(cwd, servers)
                    // PlasmaLLM always takes session/load, whatever its agent says.
                    plasma || (caps.loadSession && (preferLoad || !caps.resumeSession)) -> {
                        // History is replayed as session/update notifications, so start clean.
                        // PlasmaLLM told us the open chat's title already; show it while it loads.
                        val title = host?.takeIf { plasma && it.sessionId == target }?.title
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
                _state.update {
                    it.copy(
                        sessionId = setup.sessionId,
                        sessionTitle = host?.takeIf { h -> plasma && h.sessionId == setup.sessionId }?.title ?: it.sessionTitle,
                        sessionCwd = cwd.ifEmpty { null } ?: host?.cwd?.takeIf { plasma },
                        modes = setup.modes,
                        currentModeId = setup.currentModeId,
                        configOptions = setup.configOptions ?: it.configOptions,
                        authRequired = false,
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
                _state.update { it.copy(startingSession = false, replaying = false) }
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
            if (on) scope.launch { declareAgain() }
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
            if (r.allowed && r.declared) tablet?.link?.resume()
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
            // Stop on TabDesk's indicator stops the agent's turn too. (`caller` is our own Stop coming back.)
            if (status.control == TabletStatus.STOPPED && before != TabletStatus.STOPPED && status.reason == TabletStatus.BY_TABDESK && r.allowed) {
                stoppedOnTablet(r)
            }
            publishTablet()
            if (r.declarable && !r.declared && opened) scope.launch { declareAgain() }
        }

        /**
         * Stop was pressed on TabDesk: cancel the turn that used the tablet. PlasmaLLM cancels
         * the turn of the chat it has in front, so only when that's the one the calls came from.
         */
        private fun stoppedOnTablet(r: TabletRelay) {
            val s = _state.value
            val shown = s.sessionId ?: return
            if ((r.lastSessionId ?: shown) != shown || !s.busy) return
            client.cancel(shown)
            cancelAllPermissions()
            _state.update { it.copy(cancelling = true, permissions = emptyList()) }
        }

        fun publishTablet() {
            val r = relay ?: return
            val t = tablet ?: return
            if (!isCurrent) return
            _state.update { s ->
                s.copy(tablet = if (r.allowed) TabletUse(t.link.status.value, r.offered, r.declared, r.calls.value) else null)
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
            _state.update { s ->
                // Accept updates for our session, or before we know our session id (they can race the response).
                if (s.sessionId == null || s.sessionId == sessionId) Transcript.apply(s, update) else s
            }
        }

        override fun onExtNotification(method: String, params: JsonObject) {
            if (!isCurrent || !plasma) return
            when (val event = PlasmaEvent.parse(method, params)) {
                is PlasmaEvent.State -> onHostState(event.chat, event.tabletTools)
                is PlasmaEvent.Turn -> onHostTurn(event)
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
                null -> {}
            }
        }

        // --- PlasmaLLM's requests ---

        override suspend fun mcpMessage(params: JsonObject): JsonElement {
            val r = relay?.takeIf { isCurrent && plasma } ?: throw RpcException(PlasmaLlm.TABLET_UNAVAILABLE, "this device offers no tablet")
            return r.call(params)
        }

        private fun onHostState(chat: PlasmaChat, tabletTools: Boolean?) {
            // An empty id means PlasmaLLM is switching chats; the next state names the new one.
            if (chat.sessionId.isEmpty()) return
            host = chat
            _state.update { s ->
                s.copy(
                    plasmaLlm = s.plasmaLlm?.copy(chat = chat, tabletTools = tabletTools ?: s.plasmaLlm.tabletTools),
                    sessionTitle = if (s.sessionId == chat.sessionId && chat.title != null) chat.title else s.sessionTitle,
                )
            }
            if (needsFollow()) followHost()
        }

        private fun onHostTurn(turn: PlasmaEvent.Turn) {
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
                _state.update { it.copy(busy = false, remoteTurn = false, cancelling = false, approvalElsewhere = emptySet()) }
                return
            }
            // Our own turn ends with the session/prompt answer instead.
            if (!s.remoteTurn) return
            val reason = turn.stopReason ?: "end_turn"
            _state.update { st ->
                val ended = st.copy(busy = false, remoteTurn = false, cancelling = false, approvalElsewhere = emptySet())
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

        /** Convenience for tests and simple setups: bridge agents only. */
        fun create(scope: CoroutineScope, version: String = "dev"): AgentController {
            val http = WebSocketTransport.defaultClient()
            return AgentController(scope, version) { profile, listener -> WebSocketTransport(http, profile.url, profile.token, listener) }
        }
    }
}
