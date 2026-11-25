package com.droidacp.core.acp

import com.droidacp.core.rpc.JsonRpcConnection
import com.droidacp.core.rpc.RpcErrorCodes
import com.droidacp.core.rpc.RpcException
import com.droidacp.core.rpc.arr
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Callbacks for things the agent sends us without being asked. */
interface AcpClientHandler {
    /** A `session/update` notification. Called in arrival order, on the transport thread. */
    fun onSessionUpdate(sessionId: String, update: SessionUpdate)

    /** The agent wants permission to run a tool. Suspend until the user decides. */
    suspend fun requestPermission(request: PermissionRequest): PermissionOutcome

    /**
     * An extension notification (its method starts with `_`, e.g. `_plasmallm/state`).
     * Called in arrival order, like [onSessionUpdate]. Ignored unless overridden.
     */
    fun onExtNotification(method: String, params: JsonObject) {}
}

/**
 * The client side of the Agent Client Protocol, v1.
 *
 * Wraps a [JsonRpcConnection] with typed methods for each ACP call the app makes.
 * The capabilities we advertise in [initialize] are deliberately small: the agent
 * runs on another machine, so we don't offer file system or terminal access — the
 * agent uses its own tools for those and just reports them to us.
 */
class AcpClient(
    scope: CoroutineScope,
    send: (String) -> Boolean,
    private val handler: AcpClientHandler,
    log: ((outgoing: Boolean, text: String) -> Unit)? = null,
) {
    private val rpc = JsonRpcConnection(
        scope = scope,
        sendText = send,
        onRequest = ::handleRequest,
        onNotification = ::handleNotification,
        log = log,
    )

    fun receive(text: String) = rpc.receive(text)

    fun close(reason: String) = rpc.close(reason)

    /** [meta] goes into the params' `_meta` (e.g. [PlasmaLlm.initializeMeta]). */
    suspend fun initialize(clientName: String, clientTitle: String, clientVersion: String, meta: JsonObject? = null): InitializeResult {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            if (meta != null) put("_meta", meta)
            putJsonObject("clientCapabilities") {
                putJsonObject("fs") {
                    put("readTextFile", false)
                    put("writeTextFile", false)
                }
                put("terminal", false)
                putJsonObject("session") {
                    // We can show boolean config options (toggles) and notices.
                    putJsonObject("configOptions") { putJsonObject("boolean") {} }
                    putJsonObject("notices") {}
                }
                // We understand `plan_update` / `plan_removed`.
                putJsonObject("plan") {}
            }
            putJsonObject("clientInfo") {
                put("name", clientName)
                put("title", clientTitle)
                put("version", clientVersion)
            }
        }
        val result = rpc.request("initialize", params) as? JsonObject ?: JsonObject(emptyMap())
        return InitializeResult(
            protocolVersion = result.long("protocolVersion") ?: 0,
            agent = Implementation.parse(result.obj("agentInfo")),
            capabilities = AgentCapabilities.parse(result.obj("agentCapabilities")),
            authMethods = result.objects("authMethods").mapNotNull { AuthMethod.parse(it) },
            plasmaLlm = PlasmaHello.parse(result.obj("_meta")),
        )
    }

    suspend fun authenticate(methodId: String) {
        rpc.request("authenticate", buildJsonObject { put("methodId", methodId) })
    }

    suspend fun newSession(cwd: String): SessionSetup {
        val result = rpc.request("session/new", sessionParams(cwd)) as? JsonObject
        val sessionId = result?.str("sessionId") ?: throw RpcException(RpcErrorCodes.INTERNAL_ERROR, "Agent did not return a sessionId")
        return SessionSetup.parse(sessionId, result)
    }

    /** Re-open an old session. The agent replays its history as `session/update`s before responding. */
    suspend fun loadSession(sessionId: String, cwd: String): SessionSetup {
        val result = rpc.request("session/load", sessionParams(cwd, sessionId)) as? JsonObject
        return SessionSetup.parse(sessionId, result)
    }

    /** Re-attach to an old session without a history replay. */
    suspend fun resumeSession(sessionId: String, cwd: String): SessionSetup {
        val result = rpc.request("session/resume", sessionParams(cwd, sessionId)) as? JsonObject
        return SessionSetup.parse(sessionId, result)
    }

    /**
     * Tell the agent we're done with a session, so it stops any work in it and frees
     * what it holds for it. Only for agents with `sessionCapabilities.close`.
     */
    suspend fun closeSession(sessionId: String) {
        rpc.request("session/close", buildJsonObject { put("sessionId", sessionId) })
    }

    suspend fun listSessions(cwd: String?, maxPages: Int = 5): List<SessionSummary> {
        val all = mutableListOf<SessionSummary>()
        var cursor: String? = null
        repeat(maxPages) {
            val params = buildJsonObject {
                if (!cwd.isNullOrBlank()) put("cwd", cwd)
                if (cursor != null) put("cursor", cursor)
            }
            val result = rpc.request("session/list", params) as? JsonObject ?: return all
            result.objects("sessions").forEach { s ->
                val id = s.str("sessionId") ?: return@forEach
                all += SessionSummary(id, s.str("cwd") ?: "", s.str("title"), s.str("updatedAt"))
            }
            cursor = result.str("nextCursor") ?: return all
        }
        return all
    }

    /** Send a user turn. Suspends until the agent finishes; returns the stop reason. */
    suspend fun prompt(sessionId: String, blocks: List<JsonObject>): String {
        val params = buildJsonObject {
            put("sessionId", sessionId)
            put("prompt", JsonArray(blocks))
        }
        val result = rpc.request("session/prompt", params) as? JsonObject
        return result?.str("stopReason") ?: "end_turn"
    }

    /** Ask the agent to stop the current turn. It answers the pending prompt with `cancelled`. */
    fun cancel(sessionId: String) {
        rpc.notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })
    }

    suspend fun setMode(sessionId: String, modeId: String) {
        rpc.request(
            "session/set_mode",
            buildJsonObject {
                put("sessionId", sessionId)
                put("modeId", modeId)
            },
        )
    }

    /** Returns the agent's full, updated list of config options (or null if it sent none). */
    suspend fun setConfigOption(sessionId: String, configId: String, value: String): List<ConfigOption>? =
        parseConfigResult(
            rpc.request(
                "session/set_config_option",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("configId", configId)
                    put("value", value)
                },
            ),
        )

    suspend fun setConfigOption(sessionId: String, configId: String, value: Boolean): List<ConfigOption>? =
        parseConfigResult(
            rpc.request(
                "session/set_config_option",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("configId", configId)
                    put("type", "boolean")
                    put("value", value)
                },
            ),
        )

    private fun parseConfigResult(result: JsonElement): List<ConfigOption>? =
        (result as? JsonObject)?.arr("configOptions")?.let { arr -> ConfigOption.parseList(arr.mapNotNull { it as? JsonObject }) }

    private fun sessionParams(cwd: String, sessionId: String? = null) = buildJsonObject {
        if (sessionId != null) put("sessionId", sessionId)
        put("cwd", cwd)
        put("mcpServers", buildJsonArray { })
    }

    // --- agent → client ---

    private suspend fun handleRequest(method: String, params: JsonElement?): JsonElement {
        val p = params as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "session/request_permission" -> {
                val outcome = handler.requestPermission(PermissionRequest.parse(p))
                buildJsonObject {
                    putJsonObject("outcome") {
                        if (outcome.optionId == null) {
                            put("outcome", "cancelled")
                        } else {
                            put("outcome", "selected")
                            put("optionId", outcome.optionId)
                        }
                    }
                }
            }
            // We advertise no fs/terminal capabilities, so a well-behaved agent never
            // sends those. Anything else is answered with "method not found".
            else -> throw RpcException(RpcErrorCodes.METHOD_NOT_FOUND, "DroidACP does not support $method")
        }
    }

    private fun handleNotification(method: String, params: JsonElement?) {
        if (method.startsWith("_")) {
            handler.onExtNotification(method, params as? JsonObject ?: JsonObject(emptyMap()))
            return
        }
        if (method != "session/update") return
        val p = params as? JsonObject ?: return
        val sessionId = p.str("sessionId") ?: return
        val update = p.obj("update") ?: return
        handler.onSessionUpdate(sessionId, SessionUpdate.parse(update))
    }

    companion object {
        const val PROTOCOL_VERSION = 1

        /** JSON for a text content block, for building prompts. */
        fun textBlock(text: String): JsonObject = buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        /** JSON for an image content block (base64 data). */
        fun imageBlock(mimeType: String, base64: String): JsonObject = buildJsonObject {
            put("type", "image")
            put("mimeType", mimeType)
            put("data", base64)
        }
    }
}
