package com.droidacp.core.tablet

import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.rpc.RpcErrorCodes
import com.droidacp.core.rpc.RpcException
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * The tablet's side of PlasmaLLM's "MCP over ACP" (companion protocol 2), for one
 * connection to PlasmaLLM: it offers TabDesk's tools to the agent in PlasmaLLM's chat
 * ([mcpServers]) and passes the agent's calls on to TabDesk ([call]).
 *
 * It reads only the envelope (the MCP request's method and id, the answer's result or
 * error), never what's inside. TabDesk's own refusals ("not approved", "switched off")
 * are MCP results, passed back unchanged; the relay refuses (-33001) only when TabDesk
 * can't be reached, when the user turned the relay off or put the tablet on hold ([held]),
 * and for another serverId.
 *
 * [connection] names this connection in TabDesk's Binder ids (`c3:mcp-request:7`), so
 * TabDesk's log names a call as PlasmaLLM's does, and a reconnect can't reuse an id
 * that's still running.
 */
class TabletRelay(
    private val link: TabletLink,
    val serverId: String,
    private val connection: String,
    allowed: Boolean,
    /** 🛑 The user put this tablet on hold ([Tablet.held]): every call is refused. */
    private val held: () -> Boolean = { false },
    /** Bytes waiting to go out to PlasmaLLM on this connection ([com.droidacp.core.acp.Transport.queuedBytes]). */
    private val queued: () -> Long = { 0L },
) {
    /** "Let the agent use this tablet (through TabDesk)" is on for this agent. */
    @Volatile
    var allowed: Boolean = allowed
        private set

    /** PlasmaLLM takes a tablet: it says protocol 2 or later and `mcpCapabilities.acp`. */
    @Volatile
    var offered: Boolean = false

    /** Declared to PlasmaLLM on this connection; that lasts until the connection closes. */
    @Volatile
    var declared: Boolean = false

    /** The chat of the latest call, the one a Stop on TabDesk cancels. */
    @Volatile
    var lastSessionId: String? = null
        private set

    /** Calls passed to TabDesk and not yet answered, by PlasmaLLM's requestId. */
    private val running = ConcurrentHashMap<String, Deferred<String>>()

    private val _calls = MutableStateFlow(0)

    /** How many calls are running, for the bar. */
    val calls: StateFlow<Int> = _calls.asStateFlow()

    /** It may be declared now: allowed, PlasmaLLM takes it, and TabDesk has approved DroidACP. */
    val declarable: Boolean get() = allowed && offered && link.status.value.approved

    /** The `mcpServers` entry for session/new, load and resume, when [declarable]. */
    fun mcpServers(): List<JsonObject> {
        if (!declarable) return emptyList()
        return listOf(
            buildJsonObject {
                put("type", "acp")
                put("name", link.status.value.name?.ifBlank { null } ?: DEFAULT_NAME)
                put("serverId", serverId)
            },
        )
    }

    /**
     * The user turned the relay on or off for this agent. Off, the running calls end with
     * -33001 and so do later ones; the registration stays until the connection closes,
     * which is how PlasmaLLM expects it to be withdrawn.
     */
    fun setAllowed(on: Boolean) {
        allowed = on
        if (!on) refuseRunning("it was turned off in DroidACP on the tablet")
    }

    /** The tablet was just put on hold: the running calls end refused, as later ones will. */
    fun hold() = refuseRunning(HELD)

    /** Why calls are refused now, or null: the relay is off, or the tablet on hold. */
    private fun refusal(): String? = when {
        !allowed -> "it's turned off in DroidACP on the tablet"
        held() -> HELD
        else -> null
    }

    /** Cancelled from here rather than by PlasmaLLM: the call is answered -33001 with [reason]. */
    private class Refused(val reason: String) : CancellationException(reason)

    // The reason goes with the cancel, so it holds even if the hold is lifted before the call ends.
    private fun refuseRunning(reason: String) = running.values.forEach { it.cancel(Refused(reason)) }

    /**
     * One `mcp/message` request from PlasmaLLM: [params] holds `serverId`, `requestId`, MCP's
     * `method` and `params`, `sessionId` and `toolCallId`. Returns `{result}` or `{error}`,
     * TabDesk's MCP answer as it came. Throws [RpcException] for the ACP side's errors.
     */
    suspend fun call(params: JsonObject): JsonObject {
        refusal()?.let { throw RpcException(PlasmaLlm.TABLET_UNAVAILABLE, it) }
        if (params.str("serverId") != serverId) throw RpcException(PlasmaLlm.TABLET_UNAVAILABLE, "this device offers no tablet under that serverId")
        val requestId = params.str("requestId")
        val method = params.str("method")
        val inner = params["params"]
        if (requestId.isNullOrEmpty() || method.isNullOrEmpty() || !(inner == null || inner is JsonNull || inner is JsonObject)) {
            throw RpcException(RpcErrorCodes.INVALID_PARAMS, "mcp/message needs a requestId, a method, and params as an object")
        }
        val id = "$connection:$requestId"
        if (id.length > MAX_ID_CHARS) throw RpcException(RpcErrorCodes.INVALID_PARAMS, "That requestId is too long")
        params.str("sessionId")?.ifEmpty { null }?.let { lastSessionId = it }

        // MCP over ACP carries only the method and params: the request is built here.
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", method)
            if (inner is JsonObject) put("params", inner)
        }.toString()
        checkSize(message)

        val answer = try {
            sendTracked(requestId, id, message)
        } catch (e: PausedOnTablet) {
            // TabDesk dropped the call when it paused and answers none. A tool call gets TabDesk's
            // own refusal, which the agent reads; anything else ends as cancelled.
            if (method == TOOLS_CALL) return buildJsonObject { put("result", TabletStatus.pausedRefusal(e.reason)) }
            throw RpcException(RpcErrorCodes.REQUEST_CANCELLED, "Paused on the tablet")
        }
        if (answer.length > MAX_ANSWER_CHARS) {
            throw RpcException(PlasmaLlm.TABLET_FAILED, "TabDesk's answer is too big to pass on (over ${MAX_ANSWER_CHARS / (1024 * 1024)} MiB)")
        }
        if (answer.length > BIG_ANSWER_CHARS) awaitRoom()
        return envelope(answer)
    }

    /**
     * A message over [MAX_SEND_CHARS] (the shared folder's chunks, plan D17) goes to TabDesk
     * through a pipe, which only a TabDesk with [TabletStatus.streams] takes, and at most
     * [MAX_STREAM_BYTES] of it. Anything bigger is refused here, and never sent.
     */
    private fun checkSize(message: String) {
        if (message.length <= MAX_SEND_CHARS) return
        if (!link.status.value.streams) {
            throw RpcException(PlasmaLlm.TABLET_FAILED, "TabDesk on the tablet is too old for a message this big (over 32 Ki characters): update TabDesk")
        }
        if (utf8Bytes(message) > MAX_STREAM_BYTES) {
            throw RpcException(RpcErrorCodes.INVALID_REQUEST, "The message is over 2 MiB, more than TabDesk takes")
        }
    }

    /**
     * A big answer (a screenshot, a chunk of a file) goes out only once the connection's
     * queue is nearly empty, so answers can't pile up past the 16 MiB at which OkHttp closes
     * the connection (plan D17). PlasmaLLM's own timeout for the call still applies.
     */
    private suspend fun awaitRoom() {
        val until = System.nanoTime() + ROOM_WAIT_MS * 1_000_000
        while (queued() > ROOM_BYTES) {
            if (System.nanoTime() > until) throw RpcException(PlasmaLlm.TABLET_FAILED, "The connection to PlasmaLLM is too slow to pass this answer on")
            delay(ROOM_POLL_MS)
        }
    }

    /** Sends [message] as Binder call [id], listed under [requestId] while it runs; TabDesk's answer. */
    private suspend fun sendTracked(requestId: String, id: String, message: String): String =
        coroutineScope {
            val sent = async(start = CoroutineStart.LAZY) { link.send(id, message) }
            if (!track(requestId, sent)) {
                sent.cancel()
                throw RpcException(RpcErrorCodes.INVALID_PARAMS, "requestId $requestId is already running")
            }
            // Turned off or put on hold since the check above: refuseRunning() may have missed this
            // call, as it wasn't listed yet.
            refusal()?.let {
                untrack(requestId, sent)
                sent.cancel()
                throw RpcException(PlasmaLlm.TABLET_UNAVAILABLE, it)
            }
            try {
                sent.start()
                sent.await()
            } catch (e: CancellationException) {
                // Cancelled from here (setAllowed(false), hold()), not by PlasmaLLM cancelling this request.
                if (sent.isCancelled && currentCoroutineContext().isActive) {
                    val reason = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<Refused>().firstOrNull()?.reason ?: refusal()
                    if (reason != null) throw RpcException(PlasmaLlm.TABLET_UNAVAILABLE, reason)
                }
                throw e
            } finally {
                untrack(requestId, sent)
            }
        }

    // The map and the count change together, so the count shown is never one already gone.
    private fun track(requestId: String, sent: Deferred<String>): Boolean = synchronized(running) {
        (running.putIfAbsent(requestId, sent) == null).also { if (it) _calls.value = running.size }
    }

    private fun untrack(requestId: String, sent: Deferred<String>) = synchronized(running) {
        running.remove(requestId, sent)
        _calls.value = running.size
    }

    /** TabDesk's JSON-RPC answer as PlasmaLLM wants it: `{result: …}` or `{error: …}`. */
    private fun envelope(text: String): JsonObject {
        val answer = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw RpcException(PlasmaLlm.TABLET_FAILED, "TabDesk's answer wasn't a JSON object")
        answer["result"]?.let { return buildJsonObject { put("result", it) } }
        (answer["error"] as? JsonObject)?.let { return buildJsonObject { put("error", it) } }
        throw RpcException(PlasmaLlm.TABLET_FAILED, "TabDesk's answer had neither a result nor an error")
    }

    companion object {
        /** The longest message TabDesk takes in one Binder call (`send`), in characters. */
        const val MAX_SEND_CHARS = 32 * 1024

        /** The longest message TabDesk takes through a pipe (`sendStream`), in UTF-8 bytes. */
        const val MAX_STREAM_BYTES = 2 * 1024 * 1024

        /** An answer bigger than this waits for room in the connection's queue. */
        const val BIG_ANSWER_CHARS = 1024 * 1024

        /** "Nearly empty": the connection's queue holds at most this. */
        const val ROOM_BYTES = 1024L * 1024

        private const val ROOM_POLL_MS = 50L
        private const val ROOM_WAIT_MS = 30_000L

        /** [s]'s length in UTF-8, without encoding it. */
        fun utf8Bytes(s: String): Long {
            var n = 0L
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return n
        }

        /** MCP's method for a tool call, the only one answered with a refusal when TabDesk pauses. */
        const val TOOLS_CALL = "tools/call"

        /** TabDesk takes Binder ids of up to this many characters. */
        const val MAX_ID_CHARS = 200

        /**
         * The biggest answer passed on. The WebSocket to PlasmaLLM (OkHttp) closes the whole
         * connection rather than queue more than 16 MiB, so answers stay well under that.
         */
        const val MAX_ANSWER_CHARS = 12 * 1024 * 1024

        /** The name declared when TabDesk hasn't said the tablet's own. */
        const val DEFAULT_NAME = "Tablet"

        /**
         * Why a call is refused while on hold. PlasmaLLM tells the agent "The tablet isn't
         * available right now: " and this.
         */
        const val HELD = "it's on hold (🛑), set in DroidACP on the tablet or by a 🛑 message in the chat. " +
            "Nothing on it can be read or used until the user lets it go on: “Let it go on” in DroidACP on the tablet, " +
            "or the user's own message “let the tablet go on” from any device. Asking again, another way, " +
            "a message you or PlasmaLLM sends, or Continue on TabDesk's notification won't lift it. " +
            "Tell the user it's on hold, and how it's lifted."

        /** Whole messages, besides any starting with 🛑, that put the tablet on hold. */
        private val HOLD_PHRASES = setOf("stop the device", "stop the tablet", "hold the device", "hold the tablet")

        /** Whole messages that let a held tablet go on (plan D23), with or without a 🟢 first. */
        private val RELEASE_PHRASES = setOf("let the tablet go on", "let the device go on", "release the tablet", "release the device")

        /** What the phone's "Let the tablet go on…" sends. */
        const val RELEASE_MESSAGE = "Let the tablet go on"

        /**
         * A message that puts this tablet on hold (plan D21), from whichever device it was typed
         * on: one starting with 🛑, or one that is just "stop the tablet" (or the device; "hold"
         * works too). Whole messages only, so "don't stop the tablet" doesn't. Never one that
         * lifts a hold: the agent can have PlasmaLLM send messages (wake_me, notes), so a
         * message lifting it would let the agent lift its own hold.
         */
        fun isHoldMessage(text: String): Boolean {
            val t = text.trim()
            if (t.startsWith("🛑")) return true
            return t.lowercase().trimEnd('.', '!', ' ') in HOLD_PHRASES
        }

        /**
         * A message that lets this tablet go on after a hold (plan D23): just "let the tablet go
         * on" or "release the tablet" (or the device), whole, so "don't let the tablet go on"
         * doesn't. It counts only when a person sent it (PlasmaLLM's `by: "user"`), never one
         * PlasmaLLM or the agent sent (wake_me, notes, Claude's own turns): the caller checks.
         */
        fun isReleaseMessage(text: String): Boolean {
            val t = text.trim().removePrefix("🟢").removePrefix("\uFE0F").trim()
            return t.lowercase().trimEnd('.', '!', ' ') in RELEASE_PHRASES
        }
    }
}
