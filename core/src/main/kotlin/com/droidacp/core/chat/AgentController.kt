package com.droidacp.core.chat

import com.droidacp.core.acp.AcpClient
import com.droidacp.core.acp.AcpClientHandler
import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.PermissionOutcome
import com.droidacp.core.acp.PermissionRequest
import com.droidacp.core.acp.SessionSetup
import com.droidacp.core.acp.SessionUpdate
import com.droidacp.core.acp.Transport
import com.droidacp.core.acp.TransportListener
import com.droidacp.core.acp.WebSocketTransport
import com.droidacp.core.rpc.ConnectionClosedException
import com.droidacp.core.rpc.RpcErrorCodes
import com.droidacp.core.rpc.RpcException
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One line of the protocol log (Settings → Protocol log in the app). */
data class LogEntry(val timeMillis: Long, val outgoing: Boolean, val text: String)

/** Things worth telling the user about even when the app is in the background. */
sealed interface AgentEvent {
    data class TurnFinished(val agentName: String, val stopReason: String, val lastText: String?) : AgentEvent
    data class PermissionRequested(val agentName: String, val title: String) : AgentEvent
    data class ConnectionLost(val agentName: String, val reason: String) : AgentEvent
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
 */
class AgentController(
    private val appScope: CoroutineScope,
    private val clientVersion: String,
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

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    /**
     * Connect to [profile]. If [resumeSessionId] is given we try to continue that
     * session (session/resume, else session/load); otherwise a new session starts.
     */
    fun connect(profile: Profile, resumeSessionId: String? = null, keepTranscript: Boolean = false) {
        val old = link
        // When we keep our own transcript (reconnect), session/resume is enough; otherwise
        // prefer session/load so the agent replays the conversation for us.
        val newLink = Link(profile, resumeSessionId, preferLoad = !keepTranscript)
        link = newLink // set first, so the old link's close callback knows it's no longer current
        old?.shutdown()
        _state.update { old ->
            val base = if (keepTranscript && old.profile?.id == profile.id) old else ChatState(nextKey = old.nextKey)
            base.copy(
                status = ConnectionStatus.Connecting,
                profile = profile,
                authRequired = false,
                busy = false,
                cancelling = false,
                startingSession = false,
                permissions = emptyList(),
                pastSessions = null,
                sessionId = if (keepTranscript) base.sessionId else null,
            )
        }
        newLink.transport.connect()
    }

    /** Reconnect after a dropped connection, continuing the same session if the agent allows it. */
    fun reconnect() {
        val s = _state.value
        val profile = s.profile ?: return
        connect(profile, resumeSessionId = s.sessionId, keepTranscript = true)
    }

    fun disconnect() {
        val l = link ?: return
        link = null
        l.shutdown()
        cancelAllPermissions()
        _state.update {
            it.copy(status = ConnectionStatus.Disconnected, busy = false, cancelling = false, startingSession = false, permissions = emptyList())
        }
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    fun newSession() {
        val l = link ?: return
        if (_state.value.busy) cancelTurn()
        _state.update { resetSession(it, clearTranscript = true) }
        l.scope.launch { l.startSession(target = null, preferLoad = false) }
    }

    fun refreshPastSessions() {
        val l = link ?: return
        if (!_state.value.capabilities.listSessions) return
        _state.update { it.copy(loadingPastSessions = true) }
        l.scope.launch {
            try {
                val list = l.client.listSessions(l.profile.cwd)
                _state.update { it.copy(pastSessions = list, loadingPastSessions = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { Transcript.addNote(it.copy(loadingPastSessions = false), "Couldn't list sessions: ${e.message}", ChatItem.Severity.Warning) }
            }
        }
    }

    /** Open a session from the history list, showing its old messages if the agent supports `session/load`. */
    fun openPastSession(sessionId: String) {
        val l = link ?: return
        if (_state.value.busy) cancelTurn()
        _state.update { resetSession(it, clearTranscript = true) }
        l.scope.launch { l.startSession(target = sessionId, preferLoad = true) }
    }

    fun authenticate(methodId: String) {
        val l = link ?: return
        _state.update { it.copy(startingSession = true) }
        l.scope.launch {
            try {
                l.client.authenticate(methodId)
                _state.update { it.copy(authRequired = false) }
                l.startSession(target = l.resumeSessionId, preferLoad = l.preferLoad)
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

        val blocks = buildList {
            images.forEach { add(AcpClient.imageBlock(it.mimeType, it.base64)) }
            if (text.isNotBlank()) add(AcpClient.textBlock(text))
        }
        val shown = images.map { ContentBlock.Image(it.mimeType, it.base64, null) }
        _state.update { Transcript.addUser(it, text, shown).copy(busy = true, cancelling = false) }

        l.scope.launch {
            var stopReason: String? = null
            try {
                stopReason = l.client.prompt(sessionId, blocks)
                Transcript.describeStopReason(stopReason)?.let { note -> _state.update { Transcript.addNote(it, note) } }
            } catch (e: RpcException) {
                stopReason = "error"
                if (e.code == RpcErrorCodes.AUTH_REQUIRED) {
                    _state.update { it.copy(authRequired = true) }
                }
                _state.update { Transcript.addNote(it, "Agent error: ${e.message}", ChatItem.Severity.Error) }
            } catch (e: ConnectionClosedException) {
                // The close handler reports this.
            } finally {
                _state.update { it.copy(busy = false, cancelling = false) }
            }
            // Only report turns that really ended (not ones cut off by a dropped connection).
            if (stopReason != null && l === link) {
                val s2 = _state.value
                _events.tryEmit(AgentEvent.TurnFinished(s2.agentName, stopReason, s2.lastAgentText))
            }
        }
        return true
    }

    /** Ask the agent to stop. Pending permission questions are answered "cancelled", as ACP requires. */
    fun cancelTurn() {
        val l = link ?: return
        val s = _state.value
        val sessionId = s.sessionId ?: return
        if (!s.busy) return
        l.client.cancel(sessionId)
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
        cancelling = false,
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
        val preferLoad: Boolean,
    ) : TransportListener, AcpClientHandler {
        private val job = SupervisorJob(appScope.coroutineContext[Job])
        val scope = CoroutineScope(appScope.coroutineContext + job)
        val client = AcpClient(scope, { text -> transport.send(text) }, this, ::record)
        val transport: Transport = transports.create(profile, this)

        private val isCurrent: Boolean get() = link === this

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
            job.cancel()
            if (!isCurrent) return
            link = null
            cancelAllPermissions()
            val wasConnected = _state.value.isConnected
            _state.update {
                it.copy(
                    status = ConnectionStatus.Failed(reason),
                    busy = false,
                    cancelling = false,
                    startingSession = false,
                    permissions = emptyList(),
                )
            }
            if (wasConnected) _events.tryEmit(AgentEvent.ConnectionLost(_state.value.agentName, reason))
        }

        private suspend fun initialize() {
            try {
                val init = client.initialize(clientName = "droidacp", clientTitle = "DroidACP", clientVersion = clientVersion)
                _state.update {
                    it.copy(status = ConnectionStatus.Connected, agent = init.agent, capabilities = init.capabilities, authMethods = init.authMethods)
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
                startSession(resumeSessionId, preferLoad)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (isCurrent) {
                    _state.update { Transcript.addNote(it, "Couldn't start: ${e.message}", ChatItem.Severity.Error) }
                    transport.close()
                }
            }
        }

        /**
         * Open a session. [target] null = new session. For an existing one,
         * [preferLoad] picks session/load (replays history) over session/resume.
         */
        suspend fun startSession(target: String?, preferLoad: Boolean) {
            val caps = _state.value.capabilities
            val cwd = profile.cwd
            _state.update { it.copy(startingSession = true, authRequired = false) }
            try {
                val setup: SessionSetup = when {
                    target == null -> client.newSession(cwd)
                    caps.loadSession && (preferLoad || !caps.resumeSession) -> {
                        // History is replayed as session/update notifications, so start clean.
                        _state.update { it.copy(sessionId = target, replaying = true, items = emptyList()) }
                        client.loadSession(target, cwd)
                    }
                    caps.resumeSession -> {
                        _state.update { it.copy(sessionId = target) }
                        client.resumeSession(target, cwd)
                    }
                    else -> {
                        _state.update { Transcript.addNote(resetSession(it, clearTranscript = false), "This agent can't continue old sessions, so a new one was started.") }
                        client.newSession(cwd)
                    }
                }
                _state.update {
                    it.copy(
                        sessionId = setup.sessionId,
                        modes = setup.modes,
                        currentModeId = setup.currentModeId,
                        configOptions = setup.configOptions ?: it.configOptions,
                        authRequired = false,
                    )
                }
            } catch (e: RpcException) {
                if (e.code == RpcErrorCodes.AUTH_REQUIRED) {
                    _state.update { it.copy(authRequired = true, sessionId = null) }
                } else if (target != null) {
                    // Couldn't reopen it (maybe it expired) — fall back to a fresh session.
                    _state.update { Transcript.addNote(resetSession(it, clearTranscript = false), "Couldn't reopen the session (${e.message}); starting a new one.", ChatItem.Severity.Warning) }
                    _state.update { it.copy(startingSession = false, replaying = false) }
                    startSession(null, preferLoad = false)
                    return
                } else {
                    _state.update { Transcript.addNote(it, "Couldn't create a session: ${e.message}", ChatItem.Severity.Error) }
                }
            } finally {
                _state.update { it.copy(startingSession = false, replaying = false) }
            }
        }

        // --- AcpClientHandler ---

        override fun onSessionUpdate(sessionId: String, update: SessionUpdate) {
            if (!isCurrent) return
            _state.update { s ->
                // Accept updates for our session, or before we know our session id (they can race the response).
                if (s.sessionId == null || s.sessionId == sessionId) Transcript.apply(s, update) else s
            }
        }

        override suspend fun requestPermission(request: PermissionRequest): PermissionOutcome {
            if (!isCurrent) return PermissionOutcome(null)
            val id = nextPermissionId.getAndIncrement()
            val answer = CompletableDeferred<PermissionOutcome>()
            permissionWaiters[id] = answer

            _state.update { s ->
                val withTool = Transcript.applyToolPatch(s, request.toolCall)
                val tool = withTool.items.lastOrNull { it is ChatItem.Tool && it.call.id == request.toolCall.id } as? ChatItem.Tool
                withTool.copy(
                    permissions = withTool.permissions + PendingPermission(
                        requestId = id,
                        toolCallId = request.toolCall.id,
                        title = request.toolCall.title ?: tool?.call?.title ?: "The agent wants to use a tool",
                        kind = request.toolCall.kind ?: tool?.call?.kind ?: "other",
                        options = request.options,
                    ),
                )
            }
            _events.tryEmit(AgentEvent.PermissionRequested(_state.value.agentName, request.toolCall.title ?: "Permission needed"))

            return try {
                answer.await()
            } finally {
                permissionWaiters.remove(id)
                _state.update { s -> s.copy(permissions = s.permissions.filterNot { it.requestId == id }) }
            }
        }
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 400
        private const val MAX_LOG_CHARS = 4000

        /** Convenience for tests and simple setups: bridge agents only. */
        fun create(scope: CoroutineScope, version: String = "dev"): AgentController {
            val http = WebSocketTransport.defaultClient()
            return AgentController(scope, version) { profile, listener -> WebSocketTransport(http, profile.url, profile.token, listener) }
        }
    }
}
