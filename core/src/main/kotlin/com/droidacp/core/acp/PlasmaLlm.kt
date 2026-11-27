package com.droidacp.core.acp

import com.droidacp.core.rpc.RpcException
import com.droidacp.core.rpc.arr
import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * DroidACP's side of PlasmaLLM's companion protocol.
 *
 * PlasmaLLM is a desktop ACP client (Qt, for KDE Plasma). Its "Phone access" lets
 * DroidACP join the chat open on the computer, as a second screen: plain ACP over a
 * WebSocket, like `acp-bridge.js`, plus a few extensions whose names start with
 * `_plasmallm/`. The protocol is written down in PlasmaLLM's
 * `docs/companion-protocol.md`; this file follows its version [PROTOCOL].
 *
 * Everything here is optional: when the other end doesn't send `_meta.plasmallm`,
 * it isn't PlasmaLLM, and DroidACP talks plain ACP.
 */
object PlasmaLlm {
    /**
     * The companion protocol version DroidACP speaks: the highest whose additions it
     * understands. From 2, PlasmaLLM lets it offer this tablet's tools (see [TABLET_UNAVAILABLE]);
     * 3 to 7 added only things PlasmaLLM sends every client, which DroidACP handles; 8, the
     * tabs' chats in `_plasmallm/state` ([PlasmaTab]); 9, what each tab's agent is doing; 10,
     * the look each agent chose for its Pixel Person ([PlasmaPerson]); 11, its shirt colour; 12,
     * a tablet call names the tab that made it, which may be a back tab; 13, `session/cancel`
     * stops any tab's turn ([CANCEL_ANY_TAB]); 14, the shared folder: PlasmaLLM's file tools
     * send their chunks through this tablet, and `_plasmallm/tool_progress` comes ([FILES]); 15,
     * other agents than Claude, one per tab: the list ([PlasmaAgent]), a new tab with one
     * ([newTabMeta]), `show_tab` and `close_tab`, and each tab's `agentId` and `tabId` ([AGENTS]).
     */
    const val PROTOCOL = 15

    /** PlasmaLLM takes a tablet from a companion (protocol 2): `mcp/message` and the rest. */
    const val TABLET_PROTOCOL = 2

    /** `session/cancel` with any tab's chat stops that tab's turn (protocol 13); before, only the front tab's. */
    const val CANCEL_ANY_TAB = 13

    /**
     * PlasmaLLM sends the shared folder's chunk calls (`folder_*`) only to a tablet whose
     * companion says this or later, and `_plasmallm/tool_progress` only to such connections:
     * those following the chat, and the tablet's whichever chat it follows (protocol 14).
     */
    const val FILES = 14

    /**
     * Other agents than Claude, one per tab (protocol 15): `_plasmallm/agents` lists them,
     * `session/new` with [newTabMeta] opens a tab with one, [SHOW_TAB] and [CLOSE_TAB] act on
     * a tab by its `tabId`.
     */
    const val AGENTS = 15

    const val AGENTS_METHOD = "_plasmallm/agents"
    const val SHOW_TAB = "_plasmallm/show_tab"
    const val CLOSE_TAB = "_plasmallm/close_tab"

    /** The default agent's id in `_plasmallm/agents`: Claude, which every tab ran before protocol 15. */
    const val CLAUDE = "claude-acp"

    /** PlasmaLLM's phone access listens here unless the user changed it (the bridge uses 8765). */
    const val DEFAULT_PORT = 8766

    const val STATE = "_plasmallm/state"
    const val TURN = "_plasmallm/turn"
    const val PERMISSION_RESOLVED = "_plasmallm/permission_resolved"

    /** A permission request for a tablet action this device offered, asked on the others instead (protocol 2). */
    const val PERMISSION_ELSEWHERE = "_plasmallm/permission_elsewhere"

    /** Progress of one of PlasmaLLM's own tool calls, such as a file copied to or from the tablet (plan P11). */
    const val TOOL_PROGRESS = "_plasmallm/tool_progress"

    /** PlasmaLLM's request carrying one MCP message for the tablet this device offered (protocol 2). */
    const val MCP_MESSAGE = "mcp/message"

    /**
     * An `mcp/message` error: the tablet isn't reachable (TabDesk isn't installed, connected
     * or compatible, the relay is off on this device, or another serverId). PlasmaLLM tells
     * the agent "The tablet isn't available right now: " and the message.
     */
    const val TABLET_UNAVAILABLE = -33001

    /** An `mcp/message` error: the call failed on the way, without an MCP answer. */
    const val TABLET_FAILED = -33002

    /** Why PlasmaLLM refused a request: `error.data.plasmallm.reason`. */
    object Reason {
        const val NOT_OPEN_CHAT = "not_open_chat"
        const val BUSY = "busy"
        const val NO_CHAT = "no_chat"
        const val OPEN_FAILED = "open_failed"

        /**
         * A load or resume of a chat that isn't in the agent's list for that folder. The
         * computer stays on its chat, and `sessionId` names the chat asked for, not one
         * opened instead.
         */
        const val NOT_FOUND = "not_found"

        /**
         * A load or resume of a chat another Claude on the computer has open (in a terminal,
         * say). The computer stays on its chat, which `sessionId` names; `requested` is the
         * chat asked for, and `elsewhere` says where it's open. Retried with
         * [openElsewhereMeta], it closes that Claude first or opens the chat anyway.
         */
        const val OPEN_ELSEWHERE = "open_elsewhere"

        /** The retry said "close", and the other Claude didn't. Same data as [OPEN_ELSEWHERE]. */
        const val CLOSE_FAILED = "close_failed"
        const val SIGN_IN_NEEDED = "sign_in_needed"
        const val AGENT_NOT_RUNNING = "agent_not_running"
        const val NO_FOLDER = "no_folder"
        const val STILL_OPENING = "still_opening"
        const val TURN_ERROR = "turn_error"

        /** A new tab with an agent that isn't in `_plasmallm/agents` (protocol 15). */
        const val UNKNOWN_AGENT = "unknown_agent"

        /** A new tab with an agent that can't start now (`ready: false`); the message says why. */
        const val AGENT_UNAVAILABLE = "agent_unavailable"

        /** The Plasma widget, which has no tabs. */
        const val UNSUPPORTED = "unsupported"

        /** `show_tab` or `close_tab` with a `tabId` no tab has. */
        const val NO_TAB = "no_tab"

        /** `close_tab` of the window's last tab, which stays open. */
        const val LAST_TAB = "last_tab"

        /** The window didn't close the tab. */
        const val TAB_NOT_CLOSED = "tab_not_closed"
    }

    /**
     * Why PlasmaLLM didn't take a message, in words for the user, when the reason means
     * the agent never got it (so it can be offered again). Null for any other error.
     */
    fun refusedPrompt(reason: String?): String? = when (reason) {
        Reason.NOT_OPEN_CHAT -> "PlasmaLLM had moved to another chat."
        Reason.BUSY -> "PlasmaLLM was already working on a message."
        Reason.NO_CHAT -> "PlasmaLLM had no chat open."
        Reason.SIGN_IN_NEEDED -> "The agent needs you to sign in, on the computer."
        Reason.AGENT_NOT_RUNNING -> "PlasmaLLM's agent isn't running."
        Reason.STILL_OPENING -> "PlasmaLLM was still opening the chat."
        else -> null
    }

    /**
     * The WebSocket address for what the user typed: a computer name as the
     * Tailscale app lists it (`my-laptop`), an address (`100.101.102.103`), either
     * with a port (`my-laptop:9000`), or a full `ws://` / `wss://` URL.
     */
    fun url(host: String): String {
        val h = host.trim().trimEnd('/')
        if (h.startsWith("ws://", ignoreCase = true) || h.startsWith("wss://", ignoreCase = true)) return h
        val bare = h.removePrefix("http://").removePrefix("https://")
        val withPort = when {
            // [fd7a::1] or [fd7a::1]:9000
            bare.startsWith("[") -> if (Regex("^\\[.*]:\\d+$").matches(bare)) bare else "$bare:$DEFAULT_PORT"
            // fd7a::1 (an IPv6 address without brackets)
            bare.count { it == ':' } > 1 -> "[$bare]:$DEFAULT_PORT"
            bare.contains(':') -> bare
            else -> "$bare:$DEFAULT_PORT"
        }
        return "ws://$withPort"
    }

    /** The `_meta` of DroidACP's `initialize` params: "I am a companion, speaking this version." */
    fun initializeMeta(): JsonObject = buildJsonObject {
        putJsonObject("plasmallm") { put("protocol", PROTOCOL) }
    }

    /**
     * PlasmaLLM (protocol 4, tabs) answered a prompt `cancelled` only because another tab came
     * to the front: the turn goes on in its tab, and this device stopped following it.
     */
    fun stillRunning(promptMeta: JsonObject?): Boolean = promptMeta?.obj("plasmallm")?.bool("stillRunning") == true

    /**
     * The messageId PlasmaLLM (protocol 7) gives the agent's answer to the `/remote-control`
     * command ("Remote Control is active…", "…is off."), new for each command. Shown as a
     * note, as PlasmaLLM shows it, not as a reply.
     */
    const val REMOTE_CONTROL_MESSAGE = "plasmallm-remote-control-"

    /**
     * A prompt PlasmaLLM wrote to deliver notes between its tabs' agents: they talk to
     * each other this way, and the notes aren't for the user, so devices don't show them.
     */
    fun isAgentNote(text: String): Boolean = text.startsWith("[Note from ") || text.startsWith("[Notes from ")

    /**
     * The `_meta` of a `session/resume` that only declares the tablet on a connection already
     * open (protocol 2): no history, and PlasmaLLM never opens another chat for it.
     */
    fun declareOnlyMeta(): JsonObject = buildJsonObject {
        putJsonObject("plasmallm") { put("declareOnly", true) }
    }

    /** The `_meta` of a `session/new` that opens a new tab with [agentId] (protocol 15), in front. */
    fun newTabMeta(agentId: String): JsonObject = buildJsonObject {
        putJsonObject("plasmallm") { put("agent", agentId) }
    }

    /** The `_meta` of a load retried after [Reason.OPEN_ELSEWHERE]: what to do about the other Claude. */
    fun openElsewhereMeta(choice: String): JsonObject = buildJsonObject {
        putJsonObject("plasmallm") { put("openElsewhere", choice) }
    }
}

/** What the user chose to do about a chat another Claude has open ([PlasmaLlm.Reason.OPEN_ELSEWHERE]). */
enum class OpenElsewhereChoice(val wire: String?) {
    /** End the other Claude (PlasmaLLM does, only while it's idle), then open the chat here. */
    CLOSE("close"),

    /** Open it here too: two Claudes on one chat, each blind to what the other says. */
    ANYWAY("anyway"),

    /** Ask again as before, e.g. once the other Claude has finished its work. */
    RETRY(null),
}

/**
 * PlasmaLLM didn't open [sessionId] (in [cwd]) because another Claude has it open, or
 * ([closeFailed]) because it was asked to close that Claude and it didn't. The computer
 * stayed on its own chat.
 */
data class OpenElsewhere(
    val sessionId: String,
    val cwd: String,
    /** The other Claude's name, as other sessions see it ("DroidACP"). */
    val name: String?,
    /** It's working, so it can't be closed, and opening the chat here too would fork it. */
    val busy: Boolean,
    val tmuxWindow: String?,
    /** The Claude app can reach it too (it runs with Remote Control). */
    val remoteControl: Boolean,
    val closeFailed: Boolean,
) {
    /** "“DroidACP” has this chat open in tmux window 3", and what's going on there, in words for the user. */
    val summary: String
        get() {
            val who = name?.let { "“$it”" } ?: "Another Claude"
            val place = tmuxWindow?.let { "in tmux window $it" } ?: "on the computer"
            val app = if (remoteControl) " The Claude app can reach it too." else ""
            return when {
                closeFailed -> "$who ($place) didn't close. Close it there (/exit), then try again."
                busy -> "$who has this chat open $place, and it's working right now. Try again when it's finished.$app"
                else -> "$who has this chat open $place, and it's idle.$app"
            }
        }

    companion object {
        /** Null unless [e] is PlasmaLLM's open_elsewhere or close_failed. */
        fun parse(e: RpcException, sessionId: String, cwd: String): OpenElsewhere? {
            val p = (e.data as? JsonObject)?.obj("plasmallm") ?: return null
            val reason = p.str("reason")
            if (reason != PlasmaLlm.Reason.OPEN_ELSEWHERE && reason != PlasmaLlm.Reason.CLOSE_FAILED) return null
            val where = p.obj("elsewhere")
            return OpenElsewhere(
                sessionId = p.str("requested")?.ifEmpty { null } ?: sessionId,
                cwd = cwd,
                name = where?.str("name")?.ifBlank { null },
                busy = where?.str("status") == "busy",
                tmuxWindow = where?.str("tmuxWindow")?.ifBlank { null },
                remoteControl = where?.bool("remoteControl") ?: false,
                closeFailed = reason == PlasmaLlm.Reason.CLOSE_FAILED,
            )
        }
    }
}

/** The chat open in PlasmaLLM, from `initialize` and `_plasmallm/state`. */
data class PlasmaChat(val sessionId: String, val cwd: String, val title: String?, val busy: Boolean) {
    companion object {
        fun parse(o: JsonObject): PlasmaChat = PlasmaChat(
            sessionId = o.str("sessionId") ?: "",
            cwd = o.str("cwd") ?: "",
            title = o.str("title")?.takeIf { it.isNotBlank() },
            busy = o.bool("busy") ?: false,
        )
    }
}

/**
 * One of the tabs in PlasmaLLM's window, from `_plasmallm/state`'s `tabs` (protocol 8): its
 * chat ([sessionId] is empty while the tab starts one), whether its agent is working, and
 * whether it's the tab the devices follow ([front]). Opening a chat a tab has only brings
 * that tab to the front, so nothing stops; any other chat replaces the front tab's.
 */
data class PlasmaTab(
    val sessionId: String,
    val cwd: String,
    val title: String?,
    val busy: Boolean,
    val front: Boolean,
    /** The tab's agent, its ACP `agentInfo` name, and what it's doing (protocol 9); null before. */
    val agentName: String? = null,
    val agentTitle: String? = null,
    val activity: String? = null,
    /** What it's doing in a few words, e.g. the running tool's title (protocol 9); often empty. */
    val detail: String? = null,
    /** The look its agent chose for its Pixel Person (protocol 10); null until it has. */
    val person: PlasmaPerson? = null,
    /** Its agent's id in `_plasmallm/agents` ([PlasmaLlm.CLAUDE] for Claude), protocol 15; null before. */
    val agentId: String? = null,
    /** The tab's own id for `show_tab` and `close_tab`, also while it has no chat (protocol 15). */
    val tabId: String? = null,
    /**
     * Its chat has the tablet's tools (protocol 15): false until the chat has started, for an
     * agent that takes no HTTP MCP servers, and while the laptop's tablet setting is off.
     */
    val tabletTools: Boolean? = null,
) {
    /** It's opening its agent and has no chat yet (protocol 15: `activity: "starting"`). */
    val starting: Boolean get() = sessionId.isEmpty() && activity == "starting"

    /**
     * Its agent's name in words: its own (the ACP `agentInfo` title) when it has started, else
     * the list's name for [agentId], else the id. Null before protocol 15 says which agent.
     */
    fun agentLabel(agents: List<PlasmaAgent>?): String? {
        val id = agentId ?: return null
        return agentTitle ?: agents?.firstOrNull { it.id == id }?.name ?: agentName ?: id
    }

    companion object {
        /** Null when the state has no `tabs` (PlasmaLLM before protocol 8). */
        fun parseAll(o: JsonObject): List<PlasmaTab>? = o.arr("tabs")?.mapNotNull { e ->
            (e as? JsonObject)?.let {
                val agent = it.obj("agent")
                PlasmaTab(
                    sessionId = it.str("sessionId") ?: "",
                    cwd = it.str("cwd") ?: "",
                    title = it.str("title")?.takeIf { t -> t.isNotBlank() },
                    busy = it.bool("busy") ?: false,
                    front = it.bool("front") ?: false,
                    agentName = agent?.str("name")?.takeIf { t -> t.isNotBlank() },
                    agentTitle = agent?.str("title")?.takeIf { t -> t.isNotBlank() },
                    activity = it.str("activity")?.takeIf { t -> t.isNotBlank() },
                    detail = it.str("detail")?.takeIf { t -> t.isNotBlank() },
                    person = it.obj("person")?.let(PlasmaPerson::parse),
                    agentId = it.str("agentId")?.takeIf { t -> t.isNotBlank() },
                    tabId = it.str("tabId")?.takeIf { t -> t.isNotBlank() },
                    tabletTools = it.bool("tabletTools"),
                )
            }
        }
    }
}

/**
 * An agent a new PlasmaLLM tab can run, from `_plasmallm/agents` (protocol 15): Claude (the
 * default), the ACP Registry's (Codex, GitHub Copilot…), and the user's own. The list comes
 * in the order to show.
 */
data class PlasmaAgent(
    val id: String,
    val name: String,
    /** `default` (Claude), `npx` / `uvx` (downloaded on its first start), `binary`, `command` (the user's own). */
    val kind: String,
    /** It can start now; [why] says why not, in a sentence. */
    val ready: Boolean,
    val why: String? = null,
    /** Tried with a real account in PlasmaLLM (Claude, for now). The others may lack sign-in, its tools or reopening chats. */
    val tested: Boolean = false,
    /** Being made to work in PlasmaLLM (Codex, Copilot, Antigravity): listed after the default. */
    val featured: Boolean = false,
    val isDefault: Boolean = false,
    /** The registry's pinned version, for a downloaded one. */
    val version: String? = null,
    val description: String? = null,
    val website: String? = null,
) {
    /** PlasmaLLM downloads it from npm or PyPI the first time, so the first start can take a minute. */
    val downloads: Boolean get() = kind == "npx" || kind == "uvx"

    /** "Codex 2.12.1". */
    val nameAndVersion: String get() = version?.let { "$name $it" } ?: name

    companion object {
        /** The answer's `agents`, in the order given. Entries without an id are left out. */
        fun parseAll(result: JsonObject?): List<PlasmaAgent> = result?.arr("agents")?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.ifBlank { null } ?: return@mapNotNull null
            PlasmaAgent(
                id = id,
                name = o.str("name")?.ifBlank { null } ?: id,
                kind = o.str("kind") ?: "",
                ready = o.bool("ready") ?: false,
                why = o.str("why")?.ifBlank { null },
                tested = o.bool("tested") ?: false,
                featured = o.bool("featured") ?: false,
                isDefault = o.bool("isDefault") ?: false,
                version = o.str("version")?.ifBlank { null },
                description = o.str("description")?.ifBlank { null },
                website = o.str("website")?.ifBlank { null },
            )
        }.orEmpty()
    }
}

/**
 * The look an agent chose once for its Pixel Person (`tabs[].person`, protocol 10): option
 * ids of DroidACP's `sprites.txt`. [hairColor] may be missing (bald), [letter] is null for
 * none, [shirt] null for the agent's own colours. Ids DroidACP doesn't know are drawn as
 * that part of the default look.
 */
data class PlasmaPerson(
    val skin: String?,
    val hair: String?,
    val hairColor: String?,
    val accessories: List<String>,
    val letter: Char?,
    val shirt: String? = null,
) {
    companion object {
        fun parse(o: JsonObject): PlasmaPerson = PlasmaPerson(
            skin = o.str("skin")?.ifBlank { null },
            hair = o.str("hair")?.ifBlank { null },
            hairColor = o.str("haircolor")?.ifBlank { null },
            accessories = o.arr("accessories")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
            letter = o.str("letter")?.trim()?.singleOrNull(),
            shirt = o.str("shirt")?.ifBlank { null },
        )
    }
}

/**
 * What PlasmaLLM says about itself in its `initialize` result (`_meta.plasmallm`).
 * [tabletTools]: the laptop's setting "Let the agent use a connected tablet" (protocol 2;
 * kept up to date from `_plasmallm/state`), null when it didn't say. [tabs]: its tabs'
 * chats (protocol 8, from `_plasmallm/state`), null until it says.
 */
data class PlasmaHello(
    val protocol: Int,
    val version: String?,
    val chat: PlasmaChat,
    val tabletTools: Boolean? = null,
    val tabs: List<PlasmaTab>? = null,
) {
    companion object {
        /** Null when the other end isn't PlasmaLLM (no `_meta.plasmallm.protocol`). */
        fun parse(meta: JsonObject?): PlasmaHello? {
            val p = meta?.obj("plasmallm") ?: return null
            val protocol = p.long("protocol")?.toInt() ?: return null
            return PlasmaHello(protocol, p.str("version"), PlasmaChat.parse(p), p.bool("tabletTools"), PlasmaTab.parseAll(p))
        }
    }
}

/** A `_plasmallm/...` notification. */
sealed interface PlasmaEvent {
    /**
     * PlasmaLLM's chat, folder, title, busy state or tablet setting changed, or any tab's
     * (protocol 8). `sessionId` is empty for a moment while it switches. [tabletTools] and
     * [tabs]: as in [PlasmaHello].
     */
    data class State(val chat: PlasmaChat, val tabletTools: Boolean? = null, val tabs: List<PlasmaTab>? = null) : PlasmaEvent

    /**
     * A turn started or ended in [sessionId], whoever sent the prompt. [stillRunning]: it
     * "ended" (`cancelled`) only for devices, because another tab came to the front; it goes
     * on in its tab (protocol 4). [by], when started: `user` (a person typed it, on the laptop
     * or a device) or `app` (PlasmaLLM or Claude started it by itself); null if not said.
     */
    data class Turn(
        val sessionId: String,
        val started: Boolean,
        val stopReason: String?,
        val stillRunning: Boolean = false,
        val by: String? = null,
    ) : PlasmaEvent {
        /** A person sent it, so the tablet may go on after a Stop. */
        val byUser: Boolean get() = started && by == "user"
    }

    /** A permission request this device was asked was answered elsewhere, or dropped. */
    data class PermissionResolved(val sessionId: String, val toolCallId: String) : PlasmaEvent

    /**
     * A permission request for an action on this tablet goes to the laptop and the other
     * devices instead (protocol 2; [reason] `driven_device`). [PermissionResolved] follows.
     */
    data class PermissionElsewhere(val sessionId: String, val toolCallId: String, val reason: String?) : PlasmaEvent

    /**
     * How a tool call of PlasmaLLM's own is getting on (plan P11), as short plain text, at most
     * once a second: "Copying report.pdf from the tablet: 12 of 50 MB". Over when the call ends.
     */
    data class ToolProgress(val sessionId: String, val toolCallId: String, val text: String) : PlasmaEvent

    companion object {
        fun parse(method: String, params: JsonObject): PlasmaEvent? = when (method) {
            PlasmaLlm.STATE -> State(PlasmaChat.parse(params), params.bool("tabletTools"), PlasmaTab.parseAll(params))
            PlasmaLlm.TURN -> when (params.str("state")) {
                "started" -> Turn(params.str("sessionId") ?: "", started = true, stopReason = null, by = params.str("by"))
                "ended" -> Turn(
                    params.str("sessionId") ?: "",
                    started = false,
                    stopReason = params.str("stopReason"),
                    stillRunning = params.bool("stillRunning") ?: false,
                )
                else -> null
            }
            PlasmaLlm.PERMISSION_RESOLVED -> {
                val toolCallId = params.str("toolCallId")
                if (toolCallId.isNullOrEmpty()) null else PermissionResolved(params.str("sessionId") ?: "", toolCallId)
            }
            PlasmaLlm.PERMISSION_ELSEWHERE -> {
                val toolCallId = params.str("toolCallId")
                if (toolCallId.isNullOrEmpty()) null else PermissionElsewhere(params.str("sessionId") ?: "", toolCallId, params.str("reason"))
            }
            PlasmaLlm.TOOL_PROGRESS -> {
                val toolCallId = params.str("toolCallId")
                // One line, and short: it's plain text, shown as it came.
                val text = params.str("text")?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_PROGRESS_CHARS)
                if (toolCallId.isNullOrEmpty() || text.isNullOrEmpty()) null else ToolProgress(params.str("sessionId") ?: "", toolCallId, text)
            }
            else -> null
        }
    }
}

/** PlasmaLLM keeps a progress text under 200 characters; anything longer is cut here. */
private const val MAX_PROGRESS_CHARS = 200

/** PlasmaLLM's reason for an error (`error.data.plasmallm.reason`), or null for any other error. */
val RpcException.plasmaReason: String?
    get() = ((data as? JsonObject)?.obj("plasmallm"))?.str("reason")

/** The tab a PlasmaLLM error names (`error.data.plasmallm.tabId`, protocol 15): one opened for a new agent that then failed. */
val RpcException.plasmaTabId: String?
    get() = ((data as? JsonObject)?.obj("plasmallm"))?.str("tabId")?.takeIf { it.isNotEmpty() }

/** The chat a PlasmaLLM error names (`error.data.plasmallm.sessionId`), if any. */
val RpcException.plasmaSessionId: String?
    get() = ((data as? JsonObject)?.obj("plasmallm"))?.str("sessionId")?.takeIf { it.isNotEmpty() }
