package com.droidacp.core.chat

import com.droidacp.core.acp.AgentCapabilities
import com.droidacp.core.acp.AuthMethod
import com.droidacp.core.acp.AvailableCommand
import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.Implementation
import com.droidacp.core.acp.PermissionOption
import com.droidacp.core.acp.OpenElsewhere
import com.droidacp.core.acp.PlasmaHello
import com.droidacp.core.acp.Plan
import com.droidacp.core.acp.SessionMode
import com.droidacp.core.acp.SessionSummary
import com.droidacp.core.acp.ToolCall

/** Where we are in the connection lifecycle. */
sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Failed(val reason: String) : ConnectionStatus
}

/** One row in the conversation. [key] is unique and stable (used by the UI list). */
sealed interface ChatItem {
    val key: Long

    /**
     * [messageId]: set when the agent sends one (PlasmaLLM does, for prompts typed on the computer).
     * [remote]: typed outside PlasmaLLM, in the Claude app or claude.ai (Remote Control).
     * [agentNote]: PlasmaLLM delivering notes from another tab's agent; kept, but not shown.
     */
    data class User(
        override val key: Long,
        val text: String,
        val attachments: List<ContentBlock> = emptyList(),
        val messageId: String? = null,
        val remote: Boolean = false,
        val agentNote: Boolean = false,
    ) : ChatItem

    data class Agent(
        override val key: Long,
        val messageId: String?,
        val text: String,
        val attachments: List<ContentBlock> = emptyList(),
    ) : ChatItem

    data class Thought(override val key: Long, val messageId: String?, val text: String) : ChatItem

    data class Tool(override val key: Long, val call: ToolCall) : ChatItem

    /** Something the app wants to tell the user: errors, stop reasons, notices. */
    /** [messageId]: set for an agent message shown as a note (PlasmaLLM's Remote Control answer), so its chunks join. */
    data class Note(
        override val key: Long,
        val text: String,
        val severity: Severity = Severity.Info,
        val messageId: String? = null,
    ) : ChatItem

    enum class Severity { Info, Warning, Error }
}

/** A permission question waiting for the user. The answer goes to [AgentController.answerPermission]. */
data class PendingPermission(
    val requestId: Long,
    val toolCallId: String,
    val title: String,
    val kind: String,
    val options: List<PermissionOption>,
)

data class UsageInfo(val used: Long, val size: Long, val costAmount: Double?, val costCurrency: String?) {
    val fraction: Float get() = if (size > 0) (used.toFloat() / size).coerceIn(0f, 1f) else 0f
}

/**
 * Everything the UI needs to draw the chat screen. Immutable: every change produces
 * a new copy (via the reducer in [Transcript] or the controller), which is what lets
 * Compose redraw efficiently and lets us update it safely from any thread.
 */
data class ChatState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val profile: Profile? = null,
    val agent: Implementation? = null,
    val capabilities: AgentCapabilities = AgentCapabilities(),
    val authMethods: List<AuthMethod> = emptyList(),
    val authRequired: Boolean = false,
    val sessionId: String? = null,
    val startingSession: Boolean = false,
    val sessionTitle: String? = null,
    val items: List<ChatItem> = emptyList(),
    val busy: Boolean = false,
    /** The running turn was started somewhere else (PlasmaLLM's window or another device), not by us. */
    val remoteTurn: Boolean = false,
    val cancelling: Boolean = false,
    val replaying: Boolean = false,
    val modes: List<SessionMode> = emptyList(),
    val currentModeId: String? = null,
    val configOptions: List<ConfigOption> = emptyList(),
    val commands: List<AvailableCommand> = emptyList(),
    val plans: Map<String, Plan> = emptyMap(),
    val usage: UsageInfo? = null,
    val permissions: List<PendingPermission> = emptyList(),
    /** The folder the open chat runs in, as it was opened (the agent's own folder may differ for PlasmaLLM: see [folder]). */
    val sessionCwd: String? = null,
    val pastSessions: List<SessionSummary>? = null,
    /** The folder [pastSessions] lists (null: the open chat's). */
    val pastSessionsCwd: String? = null,
    val loadingPastSessions: Boolean = false,
    /** Why the last listing of [pastSessionsCwd] failed. */
    val pastSessionsError: String? = null,
    /** Set when connected to PlasmaLLM: its version and the chat open there. */
    val plasmaLlm: PlasmaHello? = null,
    /** PlasmaLLM didn't open a chat another Claude has open; the user is asked what to do. */
    val openElsewhere: OpenElsewhere? = null,
    val nextKey: Long = 1,
) {
    val isConnected: Boolean get() = status == ConnectionStatus.Connected
    val canChat: Boolean get() = isConnected && sessionId != null && !startingSession
    val currentModeName: String? get() = modes.firstOrNull { it.id == currentModeId }?.name ?: currentModeId
    val agentName: String get() = agent?.displayName ?: profile?.name ?: "Agent"

    /**
     * The open chat's folder. For PlasmaLLM it's the folder open there, as its state
     * says (the saved agent's folder is often empty: "whatever PlasmaLLM has open").
     */
    val folder: String?
        get() = plasmaLlm?.chat?.cwd?.ifEmpty { null } ?: sessionCwd?.ifEmpty { null } ?: profile?.cwd?.ifEmpty { null }

    /** Other chats can be opened: the agent can reopen one, or it's PlasmaLLM (which always can). */
    val canSwitchChats: Boolean
        get() = isConnected && (capabilities.loadSession || capabilities.resumeSession || plasmaLlm != null)

    /** Text of the most recent agent reply, for notifications and the widget. */
    val lastAgentText: String?
        get() = items.asReversed().firstNotNullOfOrNull { (it as? ChatItem.Agent)?.text?.takeIf { t -> t.isNotBlank() } }

    /** Config options that aren't shown elsewhere (modes have their own picker if the agent uses legacy modes). */
    val hasModeConfigOption: Boolean get() = configOptions.any { it.category == "mode" }
}
