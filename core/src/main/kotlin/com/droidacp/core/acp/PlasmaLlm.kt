package com.droidacp.core.acp

import com.droidacp.core.rpc.RpcException
import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.str
import kotlinx.serialization.json.JsonObject
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
    /** The companion protocol version DroidACP speaks. */
    const val PROTOCOL = 1

    /** PlasmaLLM's phone access listens here unless the user changed it (the bridge uses 8765). */
    const val DEFAULT_PORT = 8766

    const val STATE = "_plasmallm/state"
    const val TURN = "_plasmallm/turn"
    const val PERMISSION_RESOLVED = "_plasmallm/permission_resolved"

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
        const val SIGN_IN_NEEDED = "sign_in_needed"
        const val AGENT_NOT_RUNNING = "agent_not_running"
        const val NO_FOLDER = "no_folder"
        const val STILL_OPENING = "still_opening"
        const val TURN_ERROR = "turn_error"
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

/** What PlasmaLLM says about itself in its `initialize` result (`_meta.plasmallm`). */
data class PlasmaHello(val protocol: Int, val version: String?, val chat: PlasmaChat) {
    companion object {
        /** Null when the other end isn't PlasmaLLM (no `_meta.plasmallm.protocol`). */
        fun parse(meta: JsonObject?): PlasmaHello? {
            val p = meta?.obj("plasmallm") ?: return null
            val protocol = p.long("protocol")?.toInt() ?: return null
            return PlasmaHello(protocol, p.str("version"), PlasmaChat.parse(p))
        }
    }
}

/** A `_plasmallm/...` notification. */
sealed interface PlasmaEvent {
    /** PlasmaLLM's chat, folder, title or busy state changed. `sessionId` is empty for a moment while it switches. */
    data class State(val chat: PlasmaChat) : PlasmaEvent

    /** A turn started or ended in [sessionId], whoever sent the prompt. */
    data class Turn(val sessionId: String, val started: Boolean, val stopReason: String?) : PlasmaEvent

    /** A permission request this device was asked was answered elsewhere, or dropped. */
    data class PermissionResolved(val sessionId: String, val toolCallId: String) : PlasmaEvent

    companion object {
        fun parse(method: String, params: JsonObject): PlasmaEvent? = when (method) {
            PlasmaLlm.STATE -> State(PlasmaChat.parse(params))
            PlasmaLlm.TURN -> when (params.str("state")) {
                "started" -> Turn(params.str("sessionId") ?: "", started = true, stopReason = null)
                "ended" -> Turn(params.str("sessionId") ?: "", started = false, stopReason = params.str("stopReason"))
                else -> null
            }
            PlasmaLlm.PERMISSION_RESOLVED -> {
                val toolCallId = params.str("toolCallId")
                if (toolCallId.isNullOrEmpty()) null else PermissionResolved(params.str("sessionId") ?: "", toolCallId)
            }
            else -> null
        }
    }
}

/** PlasmaLLM's reason for an error (`error.data.plasmallm.reason`), or null for any other error. */
val RpcException.plasmaReason: String?
    get() = ((data as? JsonObject)?.obj("plasmallm"))?.str("reason")

/** The chat a PlasmaLLM error names (`error.data.plasmallm.sessionId`), if any. */
val RpcException.plasmaSessionId: String?
    get() = ((data as? JsonObject)?.obj("plasmallm"))?.str("sessionId")?.takeIf { it.isNotEmpty() }
