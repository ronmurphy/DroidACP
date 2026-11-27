package com.droidacp.core.chat

import com.droidacp.core.acp.AgentCapabilities
import com.droidacp.core.acp.AuthMethod
import com.droidacp.core.acp.AvailableCommand
import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.Implementation
import com.droidacp.core.acp.PermissionOption
import com.droidacp.core.acp.OpenElsewhere
import com.droidacp.core.acp.PlasmaAgent
import com.droidacp.core.acp.PlasmaHello
import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.acp.PlasmaTab
import com.droidacp.core.acp.Plan
import com.droidacp.core.acp.SessionMode
import com.droidacp.core.acp.SessionSummary
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.tablet.TabletStatus

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

/**
 * This tablet, offered to PlasmaLLM's agent ("Let the agent use this tablet" is on): what
 * the bar above the message box shows, and the settings.
 */
data class TabletUse(
    /** TabDesk, as it last said. */
    val status: TabletStatus,
    /** PlasmaLLM takes a tablet (protocol 2 and `mcpCapabilities.acp`); false until connected. */
    val offered: Boolean = false,
    /** Offered to PlasmaLLM on this connection. */
    val declared: Boolean = false,
    /** The agent's calls running on the tablet now. */
    val calls: Int = 0,
    /** 🛑 On hold until the user lets it go on here (plan D20); every call is refused. */
    val held: Boolean = false,
) {
    /** The agent is using the tablet: the bar says so, with Stop. */
    val inUse: Boolean get() = status.control == TabletStatus.ACTIVE || calls > 0

    /** Stopped (here or on TabDesk) until the next message the user sends. */
    val stopped: Boolean get() = status.control == TabletStatus.STOPPED

    /** Paused by TabDesk until the next message the user sends: someone touched the screen (tool list 2), or another reason. */
    val paused: Boolean get() = status.control == TabletStatus.PAUSED

    /** Why it's [paused], for the bar and the notification. */
    val pausedText: String get() = if (status.reason == TabletStatus.TOUCHED) PAUSED_BY_TOUCH else PAUSED

    companion object {
        /** The bar and the notification while the agent uses the tablet. */
        const val IN_USE = "The agent is using this tablet"
        const val PAUSED_BY_TOUCH = "Paused because you touched the screen. Send a message to let the agent go on."
        const val PAUSED = "Paused on the tablet. Send a message to let the agent go on."
    }
}

/**
 * A tab PlasmaLLM opened for another agent, whose agent then didn't start a chat (protocol
 * 15): it stays open on the computer, showing the problem, and it's the tab in front, so the
 * phone shows this instead of a chat until the tab is closed or another chat opens.
 */
data class TabProblem(
    /** The tab, for `close_tab`; null if PlasmaLLM didn't say (it then can't be closed from here). */
    val tabId: String?,
    val agentName: String,
    /** PlasmaLLM's words for what went wrong, e.g. "The agent exited (code 1)." */
    val message: String,
    /** The agent needs signing in, which happens on the computer for now. */
    val signIn: Boolean,
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
    /** 🛑 was pressed: the turn is stopping, and a 🛑 message goes as soon as it has ended. */
    val halting: Boolean = false,
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
    /** This tablet, offered to PlasmaLLM's agent; null unless the agent's setting is on. */
    val tablet: TabletUse? = null,
    /**
     * Tool calls (ids) waiting for approval on another device or the laptop: PlasmaLLM asks
     * there, not on the tablet the agent is using (`_plasmallm/permission_elsewhere`).
     */
    val approvalElsewhere: Set<String> = emptySet(),
    /**
     * PlasmaLLM's progress for a running tool call, by its id (`_plasmallm/tool_progress`,
     * plan P11/D17): "Copying report.pdf from the tablet: 12 of 50 MB". A passing line on
     * the card and in the notification, never part of the chat's history.
     */
    val toolProgress: Map<String, String> = emptyMap(),
    /**
     * The tablet: another tab's progress line (its chat isn't the one shown here), for the
     * notification only. It goes a few seconds after PlasmaLLM stops sending it.
     */
    val tabletProgress: String? = null,
    /** The connection dropped, and DroidACP will connect again by itself shortly (the tablet's relay). */
    val reconnecting: Boolean = false,
    /** The agents a new PlasmaLLM tab can run (protocol 15), as last listed; null until asked. */
    val plasmaAgents: List<PlasmaAgent>? = null,
    val loadingAgents: Boolean = false,
    /** Why the last listing of [plasmaAgents] failed. */
    val agentsError: String? = null,
    /** A new tab with this agent is opening on the computer (protocol 15); its first start may download it. */
    val startingAgent: PlasmaAgent? = null,
    /** The new tab's agent didn't start a chat; see [TabProblem]. */
    val tabProblem: TabProblem? = null,
    val nextKey: Long = 1,
) {
    val isConnected: Boolean get() = status == ConnectionStatus.Connected

    /** [approvalElsewhere] as shown: only during a turn, so one left over never shows after it. */
    val waitingElsewhere: Set<String> get() = if (busy) approvalElsewhere else emptySet()

    /** [call]'s progress as shown: only while it runs, in a turn. */
    fun progressOf(call: ToolCall): String? =
        if (busy && (call.status == "pending" || call.status == "in_progress")) toolProgress[call.id] else null
    val canChat: Boolean get() = isConnected && sessionId != null && !startingSession

    /**
     * 🛑 is offered in the message box (plan D22): while the agent is working in a PlasmaLLM
     * chat whose agent may use a tablet, on any device, with TabDesk or without.
     */
    val canHalt: Boolean get() = busy && !halting && canChat && plasmaLlm != null && plasmaLlm.tabletTools != false

    /**
     * "Let the tablet go on…" is offered in the menu (plan D23): a PlasmaLLM chat whose agent
     * may use a tablet, on a device that isn't the tablet (that one lifts its hold itself).
     */
    val offersRemoteRelease: Boolean get() = canChat && tablet == null && plasmaLlm != null && plasmaLlm.tabletTools != false

    /**
     * Whether opening [sessionId] (null: a new chat) stops the agent's work in this chat, so
     * the user is asked first. PlasmaLLM 8 lists its tabs' chats: opening one a tab has only
     * brings that tab to the front, and this chat goes on in its own; any other chat replaces
     * this one. Without that list (another agent, an older PlasmaLLM), any switch mid-turn may.
     */
    fun switchStopsWork(sessionId: String?): Boolean {
        val tabs = plasmaLlm?.tabs
        val working = busy || tabs?.any { it.front && it.busy } == true
        if (!working) return false
        if (tabs == null || sessionId == null) return true
        return tabs.none { it.sessionId == sessionId }
    }

    /** PlasmaLLM's other tabs that have a chat (protocol 8): opening one of them stops nothing. */
    val otherTabs: List<PlasmaTab>
        get() = plasmaLlm?.tabs.orEmpty().filter { !it.front && it.sessionId.isNotEmpty() && it.sessionId != sessionId }

    /** The other PlasmaLLM tab that has [sessionId] open, if one does. */
    fun tabWith(sessionId: String): PlasmaTab? = otherTabs.firstOrNull { it.sessionId == sessionId }

    /** PlasmaLLM's tabs can run other agents than Claude, opened from here (protocol 15). */
    val offersAgents: Boolean get() = isConnected && (plasmaLlm?.protocol ?: 0) >= PlasmaLlm.AGENTS

    /** The PlasmaLLM tab whose chat is shown here, if its state lists it. */
    val shownTab: PlasmaTab? get() = sessionId?.let { id -> plasmaLlm?.tabs?.firstOrNull { it.sessionId == id } }

    /** The tab in front on the computer: `session/list` and `session/load` go to its agent. */
    val frontTab: PlasmaTab? get() = plasmaLlm?.tabs?.firstOrNull { it.front }

    /**
     * Tabs with no chat (protocol 15): one starting its agent, or one whose agent couldn't
     * start. They can only be closed from here.
     */
    val tabsWithoutChat: List<PlasmaTab>
        get() = if (!offersAgents) emptyList() else plasmaLlm?.tabs.orEmpty().filter { it.sessionId.isEmpty() && it.tabId != null }

    /**
     * The agent of the tab in front (protocol 15), whose chats the Chats panel lists: null
     * when PlasmaLLM doesn't say, as before protocol 15, when every tab ran Claude.
     */
    val frontAgentId: String? get() = if (offersAgents) frontTab?.agentId else null

    /**
     * The agent whose tab must be in front to open [bookmark] (protocol 15), when the tab in front
     * runs another: `session/load` goes to that tab's agent, and Claude can't open Codex's chats.
     * A bookmark saved before is Claude's. Null when it opens as it is, also when another tab
     * has it open (opening it brings that tab to the front).
     */
    fun agentNeededFor(bookmark: ChatBookmark): String? {
        val front = frontAgentId ?: return null
        if (tabWith(bookmark.sessionId) != null) return null
        return (bookmark.agentId ?: PlasmaLlm.CLAUDE).takeIf { it != front }
    }

    /** A tab in the back with a chat whose agent is [agentId], to bring to the front for its chats. */
    fun tabRunning(agentId: String): PlasmaTab? = otherTabs.firstOrNull { it.agentId == agentId && it.tabId != null }

    /** [tab]'s agent in words, if PlasmaLLM says which it is (protocol 15). */
    fun agentLabel(tab: PlasmaTab): String? = tab.agentLabel(plasmaAgents)

    /** The name of the agent with [agentId], from the list if it was fetched. */
    fun nameOfAgent(agentId: String): String =
        plasmaAgents?.firstOrNull { it.id == agentId }?.name
            ?: plasmaLlm?.tabs?.firstOrNull { it.agentId == agentId }?.let(::agentLabel)
            ?: if (agentId == PlasmaLlm.CLAUDE) "Claude" else agentId

    val currentModeName: String? get() = modes.firstOrNull { it.id == currentModeId }?.name ?: currentModeId
    val agentName: String get() = agent?.displayName ?: profile?.name ?: "Agent"

    /**
     * Who the open chat is with: [agentName], or for a PlasmaLLM tab running another agent than
     * Claude (protocol 15), that agent ("Codex").
     */
    val chatAgentName: String
        get() = shownTab?.takeIf { offersAgents && it.agentId != null && it.agentId != PlasmaLlm.CLAUDE }?.let(::agentLabel) ?: agentName

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
