package com.droidacp.core.cloud

import com.droidacp.core.acp.TransportListener
import com.droidacp.core.agent.InProcessAgent
import com.droidacp.core.agent.InProcessTransport
import com.droidacp.core.agent.Models
import com.droidacp.core.rpc.JsonRpcConnection
import com.droidacp.core.rpc.RpcErrorCodes
import com.droidacp.core.rpc.RpcException
import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** What cloud mode needs from the app, plus the choices saved in the profile. */
class CloudAgentConfig(
    /** Where the ids of the cloud agent and environment we created are remembered. */
    val stateDir: File,
    val version: String,
    val apiFactory: (apiKey: String) -> CloudApi = { AnthropicCloudApi(it) },
)

/** How much the agent may do in its container without asking. These are the session modes. */
enum class CloudMode(val id: String, val title: String, val description: String) {
    Auto("auto", "Auto", "Anthropic's server checks each action: safe ones run, risky ones ask or are blocked"),
    Ask("ask", "Ask first", "Ask before running commands or changing files"),
    Allow("allow", "Don't ask", "Run everything in the container without asking"),
    ;

    companion object {
        fun fromId(id: String?): CloudMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The agent's half of ACP for cloud mode. To the app it looks like any other ACP
 * agent; behind it, every request becomes a call to Claude Managed Agents.
 *
 *     session/new     ->  create a session (and, the first time, the agent and environment)
 *     session/prompt  ->  send `user.message`, then relay events until the session is idle
 *     session/cancel  ->  send `user.interrupt`
 *     session/load    ->  fetch the session's events and replay them
 *
 * The conversation itself lives on Anthropic's side, so nothing is stored on the
 * device except a few ids and preferences ([CloudState]).
 */
class CloudAgent(
    private val scope: CoroutineScope,
    private val config: CloudAgentConfig,
    private val apiKey: String,
    private val repoUrl: String,
    private val repoToken: String,
    send: (String) -> Boolean,
) : InProcessAgent {
    private val rpc = JsonRpcConnection(scope, send, ::handleRequest, ::handleNotification)
    private val api: CloudApi by lazy { config.apiFactory(apiKey) }
    private val state = CloudState(File(config.stateDir, "cloud-${fingerprint(apiKey)}.json"))

    private inner class Session(val id: String) {
        /** While true, events are processed but nothing is shown. */
        @Volatile var muted = false
        val mapper = CloudEventMapper { update -> if (!muted) update(id, update) }

        @Volatile var interrupted = false
        @Volatile var running = false
        var titled = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    override fun receive(text: String) = rpc.receive(text)

    override fun close() {
        rpc.close("Agent stopped")
        api.close()
    }

    // ------------------------------------------------------------------
    // Requests from the app
    // ------------------------------------------------------------------

    private suspend fun handleRequest(method: String, params: JsonElement?): JsonElement {
        val p = params as? JsonObject ?: JsonObject(emptyMap())
        try {
            return when (method) {
                "initialize" -> initialize()
                "authenticate" -> JsonObject(emptyMap())
                "session/new" -> {
                    if (apiKey.isBlank()) throw RpcException(RpcErrorCodes.INTERNAL_ERROR, "No API key. Edit this agent and add your Anthropic API key.")
                    val id = createSession()
                    sessions[id] = Session(id)
                    setup { put("sessionId", id) }
                }
                "session/load", "session/resume" -> {
                    val id = p.str("sessionId") ?: throw invalid("sessionId is required")
                    val session = Session(id).also { it.titled = true }
                    // `load` shows the old conversation again. `resume` assumes the app still has
                    // it on screen, but we still read the history, without showing it, to learn
                    // which events are already handled and whether a tool call is waiting for an answer.
                    session.muted = method == "session/resume"
                    for (event in api.listEvents(id)) session.mapper.handle(event, live = false)
                    session.muted = false
                    sessions[id] = session
                    setup()
                }
                "session/list" -> {
                    val listed = state.agentId?.let { api.listSessions(it) }.orEmpty()
                    buildJsonObject {
                        putJsonArray("sessions") {
                            for (s in listed) {
                                add(
                                    buildJsonObject {
                                        put("sessionId", s.id)
                                        put("cwd", WORKSPACE)
                                        put("title", s.title ?: "Untitled")
                                        put("updatedAt", s.updatedAt)
                                    },
                                )
                            }
                        }
                    }
                }
                "session/close" -> {
                    // The app moved to another chat: stop what it's doing and forget it here. The
                    // conversation stays on Anthropic's side, so it can be opened again.
                    val session = sessions.remove(p.str("sessionId") ?: throw invalid("sessionId is required"))
                    if (session != null) interrupt(session)
                    JsonObject(emptyMap())
                }
                "session/prompt" -> prompt(session(p), p.objects("prompt"))
                "session/set_mode" -> {
                    val mode = CloudMode.fromId(p.str("modeId")) ?: throw invalid("Unknown mode ${p.str("modeId")}")
                    val session = session(p)
                    if (session.running) throw invalid("Stop the agent before changing the mode")
                    api.updateSession(session.id, tools = toolset(mode, state.webAccess))
                    state.mode = mode
                    update(session.id, buildJsonObject { put("sessionUpdate", "current_mode_update"); put("currentModeId", mode.id) })
                    JsonObject(emptyMap())
                }
                "session/set_config_option" -> setConfigOption(session(p), p)
                else -> throw RpcException(RpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (e: CloudException) {
            throw RpcException(RpcErrorCodes.INTERNAL_ERROR, e.message ?: "The request to Anthropic failed")
        }
    }

    private fun handleNotification(method: String, params: JsonElement?) {
        if (method != "session/cancel") return
        interrupt(sessions[(params as? JsonObject)?.str("sessionId")] ?: return)
    }

    private fun interrupt(session: Session) {
        if (!session.running) return
        session.interrupted = true
        // "Stop" is itself an event. The turn ends when the session reports it is idle.
        scope.launch {
            try {
                api.send(session.id, buildJsonObject { put("type", "user.interrupt") })
            } catch (e: CloudException) {
                // The turn loop will report whatever is wrong with the connection.
            }
        }
    }

    private fun initialize(): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        putJsonObject("agentInfo") {
            put("name", "droidacp-cloud")
            put("title", "Claude (cloud)")
            put("version", config.version)
        }
        putJsonObject("agentCapabilities") {
            put("loadSession", true)
            putJsonObject("promptCapabilities") {
                put("image", true)
                put("audio", false)
                put("embeddedContext", true)
            }
            putJsonObject("sessionCapabilities") {
                putJsonObject("list") {}
                putJsonObject("resume") {}
                putJsonObject("close") {}
            }
        }
        put("authMethods", buildJsonArray { })
    }

    // ------------------------------------------------------------------
    // Creating things on Anthropic's side
    // ------------------------------------------------------------------

    /**
     * Start a session, creating the agent and environment first if this API key has
     * none yet. Those two are made once and reused: their ids are kept in [state].
     */
    private suspend fun createSession(retry: Boolean = true): String {
        val agentId = state.agentId ?: api.createAgent("DroidACP", state.model, SYSTEM_PROMPT, toolset(CloudMode.Auto, web = false)).also { state.agentId = it }
        val environmentId = state.environmentId ?: api.createEnvironment(
            "DroidACP",
            buildJsonObject {
                put("type", "cloud")
                // A development machine needs to reach package registries, git hosts and so on.
                putJsonObject("networking") { put("type", "unrestricted") }
            },
        ).also { state.environmentId = it }

        val agent = buildJsonObject {
            // "With overrides": use the saved agent, but with this session's model and tool settings.
            put("type", "agent_with_overrides")
            put("id", agentId)
            putJsonObject("model") {
                put("id", state.model)
                put("effort", state.effort)
            }
            put("tools", toolset(state.mode, state.webAccess))
        }
        val resources = if (repoUrl.isBlank()) {
            emptyList()
        } else {
            listOf(
                buildJsonObject {
                    put("type", "github_repository")
                    put("url", repoUrl.trim())
                    // The token stays on Anthropic's side: git traffic is signed after it leaves the container.
                    put("authorization_token", repoToken.trim())
                },
            )
        }
        return try {
            api.createSession(agent, environmentId, "DroidACP session", resources)
        } catch (e: CloudException) {
            // The saved agent or environment was deleted in the Console: make new ones, once.
            if (!e.notFound || !retry) throw e
            state.agentId = null
            state.environmentId = null
            createSession(retry = false)
        }
    }

    /** The built-in tools (bash, read, write, edit, glob, grep, web) with their permission rules. */
    private fun toolset(mode: CloudMode, web: Boolean): JsonArray = buildJsonArray {
        fun policy(type: String) = buildJsonObject { put("type", type) }
        add(
            buildJsonObject {
                put("type", "agent_toolset_20260401")
                putJsonObject("default_config") {
                    put("enabled", true)
                    put("permission_policy", policy(if (mode == CloudMode.Auto) "auto" else "always_allow"))
                }
                putJsonArray("configs") {
                    if (mode == CloudMode.Ask) {
                        for (tool in listOf("bash", "write", "edit")) {
                            add(buildJsonObject { put("name", tool); put("permission_policy", policy("always_ask")) })
                        }
                    }
                    for (tool in listOf("web_fetch", "web_search")) {
                        add(buildJsonObject { put("name", tool); put("enabled", web) })
                    }
                }
            },
        )
    }

    // ------------------------------------------------------------------
    // One turn
    // ------------------------------------------------------------------

    private suspend fun prompt(session: Session, blocks: List<JsonObject>): JsonObject {
        if (session.running) throw invalid("A turn is already running in this session")
        session.running = true
        session.interrupted = false
        try {
            // A session that is waiting for an answer about a tool refuses new messages.
            // If such a question was left over (the app was closed mid-question), reject it first.
            for (stale in session.mapper.pendingAsks.toList()) confirm(session, stale, allow = false, message = "The user moved on without answering.")

            if (!session.titled) {
                session.titled = true
                val title = blocks.firstNotNullOfOrNull { it.str("text") }?.trim()?.lineSequence()?.firstOrNull()?.take(60) ?: "Image"
                api.updateSession(session.id, title = title)
                update(session.id, buildJsonObject { put("sessionUpdate", "session_info_update"); put("title", title) })
            }

            val message = buildJsonObject {
                put("type", "user.message")
                put("content", JsonArray(promptToCloudContent(blocks)))
            }
            val stopReason = when (val end = runTurn(session, message)) {
                is Step.Failed -> throw RpcException(RpcErrorCodes.INTERNAL_ERROR, end.message)
                else -> if (session.interrupted) "cancelled" else "end_turn"
            }
            return buildJsonObject { put("stopReason", stopReason) }
        } finally {
            session.running = false
        }
    }

    /**
     * Send [message] and relay events until the turn ends.
     *
     * The live stream only carries events that happen while it is open, and a mobile
     * connection can drop at any time. So: open the stream first, send the message
     * once it is up, and if the stream breaks, reconnect and fill the gap from the
     * session's event history. The mapper ignores events it has already handled.
     */
    private suspend fun runTurn(session: Session, message: JsonObject): Step {
        var sent = false
        var failures = 0
        while (true) {
            var end: Step? = null
            try {
                api.stream(session.id).transformWhile<JsonObject, Unit> { event ->
                    if (event.str("type") == CloudApi.STREAM_OPEN) {
                        if (!sent) {
                            api.send(session.id, message)
                            sent = true
                        } else {
                            for (missed in api.listEvents(session.id)) {
                                end = relay(session, missed) ?: end
                            }
                        }
                    } else {
                        end = relay(session, event)
                        failures = 0
                    }
                    end == null // false stops listening
                }.collect()
            } catch (e: CloudException) {
                if (++failures > MAX_RECONNECTS) throw e
                delay(1000L * failures)
            }
            end?.let { return it }
        }
    }

    /** Show one event; answer it if it is a question. Returns the step that ends the turn, if this is it. */
    private suspend fun relay(session: Session, event: JsonObject): Step? = when (val step = session.mapper.handle(event, live = true)) {
        is Step.Continue -> null
        is Step.Ask -> {
            confirm(session, step.toolEventId, allow = askUser(session, step))
            null
        }
        is Step.Done, is Step.Failed -> step
    }

    private suspend fun askUser(session: Session, ask: Step.Ask): Boolean {
        val result = rpc.request(
            "session/request_permission",
            buildJsonObject {
                put("sessionId", session.id)
                putJsonObject("toolCall") {
                    put("toolCallId", ask.toolEventId)
                    put("title", ask.title)
                    put("kind", ask.kind)
                }
                putJsonArray("options") {
                    add(buildJsonObject { put("optionId", "allow"); put("name", "Allow"); put("kind", "allow_once") })
                    add(buildJsonObject { put("optionId", "reject"); put("name", "Reject"); put("kind", "reject_once") })
                }
            },
        ) as? JsonObject
        val outcome = result?.obj("outcome")
        return outcome?.str("outcome") == "selected" && outcome.str("optionId") == "allow"
    }

    private suspend fun confirm(session: Session, toolEventId: String, allow: Boolean, message: String? = null) {
        session.mapper.pendingAsks -= toolEventId
        api.send(
            session.id,
            buildJsonObject {
                put("type", "user.tool_confirmation")
                put("tool_use_id", toolEventId)
                put("result", if (allow) "allow" else "deny")
                if (!allow) put("deny_message", message ?: "The user declined this action. Don't retry it; ask what they would like instead.")
            },
        )
        if (!allow) {
            update(
                session.id,
                buildJsonObject {
                    put("sessionUpdate", "tool_call_update")
                    put("toolCallId", toolEventId)
                    put("status", "failed")
                },
            )
        }
    }

    /** Translate ACP prompt blocks (what the app sends) into the cloud API's content blocks. */
    private fun promptToCloudContent(blocks: List<JsonObject>): List<JsonObject> = blocks.mapNotNull { block ->
        fun text(value: String) = buildJsonObject {
            put("type", "text")
            put("text", value)
        }
        when (block.str("type")) {
            "text" -> text(block.str("text") ?: "")
            "image" -> buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", block.str("mimeType") ?: "image/png")
                    put("data", block.str("data") ?: "")
                }
            }
            "resource" -> block.obj("resource")?.let { r -> text("File ${r.str("uri") ?: ""}:\n\n${r.str("text") ?: ""}") }
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Modes and options
    // ------------------------------------------------------------------

    private fun setConfigOption(session: Session, p: JsonObject): JsonObject {
        val value = p["value"] as? JsonPrimitive ?: throw invalid("value is required")
        when (p.str("configId")) {
            "model" -> state.model = value.content.takeIf { v -> Models.all.any { it.id == v } } ?: throw invalid("Unknown model ${value.content}")
            "effort" -> state.effort = value.content.takeIf { it in Models.efforts } ?: throw invalid("Unknown effort ${value.content}")
            "web" -> state.webAccess = value.booleanOrNull ?: throw invalid("value must be true or false")
            else -> throw invalid("Unknown config option ${p.str("configId")}")
        }
        // A cloud session keeps the model it was created with, so say when the change takes effect.
        update(
            session.id,
            buildJsonObject {
                put("sessionUpdate", "notice")
                put("severity", "info")
                put("title", "Saved. This applies from the next new session.")
            },
        )
        return buildJsonObject { put("configOptions", configOptions()) }
    }

    private fun setup(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        extra()
        putJsonObject("modes") {
            put("currentModeId", state.mode.id)
            putJsonArray("availableModes") {
                CloudMode.entries.forEach { mode ->
                    add(
                        buildJsonObject {
                            put("id", mode.id)
                            put("name", mode.title)
                            put("description", mode.description)
                        },
                    )
                }
            }
        }
        put("configOptions", configOptions())
    }

    private fun configOptions(): JsonArray = buildJsonArray {
        fun select(id: String, name: String, category: String, current: String, choices: List<Pair<String, String>>) = buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", "Used for new sessions")
            put("category", category)
            put("type", "select")
            put("currentValue", current)
            putJsonArray("options") {
                choices.forEach { (value, label) ->
                    add(
                        buildJsonObject {
                            put("value", value)
                            put("name", label)
                        },
                    )
                }
            }
        }
        add(select("model", "Model", "model", state.model, Models.all.map { it.id to it.name }))
        add(select("effort", "Effort", "thought_level", state.effort, Models.efforts.map { it to it.replaceFirstChar(Char::uppercase) }))
        add(
            buildJsonObject {
                put("id", "web")
                put("name", "Web search")
                put("description", "Let the agent search the web and read pages. Used for new sessions; searches are billed by Anthropic.")
                put("type", "boolean")
                put("currentValue", state.webAccess)
            },
        )
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun update(sessionId: String, update: JsonObject) {
        rpc.notify(
            "session/update",
            buildJsonObject {
                put("sessionId", sessionId)
                put("update", update)
            },
        )
    }

    private fun session(p: JsonObject): Session =
        sessions[p.str("sessionId")] ?: throw RpcException(RpcErrorCodes.RESOURCE_NOT_FOUND, "Unknown session ${p.str("sessionId")}")

    private fun invalid(message: String) = RpcException(RpcErrorCodes.INVALID_PARAMS, message)

    companion object {
        /** The folder the agent starts in inside its container. Repositories are cloned below it. */
        const val WORKSPACE = "/workspace"
        private const val MAX_RECONNECTS = 4

        private const val SYSTEM_PROMPT =
            "You are a coding agent working in a Linux container, used from DroidACP, an Android app. The user reads " +
                "your replies on a phone or tablet.\n\n" +
                "- Do what was asked, completely, then stop. Don't add features or files nobody asked for.\n" +
                "- Read the relevant code before changing it, and follow the project's conventions.\n" +
                "- After a change, build or test it when the project allows, and say plainly what you could and could not verify.\n" +
                "- If a GitHub repository is attached it is under /workspace. Commit and push only when asked.\n" +
                "- Be brief: lead with the answer. Replies are shown as Markdown; refer to code as `path:line`.\n" +
                "- Ask before anything destructive or hard to undo."

        /** A short, stable name for an API key, so each key gets its own saved ids without storing the key. */
        private fun fingerprint(apiKey: String): String =
            MessageDigest.getInstance("SHA-256").digest(apiKey.trim().toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    }
}

/** The few things cloud mode remembers on the device, as a small JSON file. */
class CloudState(private val file: File) {
    private var json: JsonObject = try {
        if (file.isFile) Json.parseToJsonElement(file.readText()) as JsonObject else JsonObject(emptyMap())
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

    private fun set(key: String, value: JsonPrimitive?) {
        json = JsonObject(if (value == null) json - key else json + (key to value))
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    var agentId: String?
        get() = json.str("agentId")
        set(value) = set("agentId", value?.let(::JsonPrimitive))
    var environmentId: String?
        get() = json.str("environmentId")
        set(value) = set("environmentId", value?.let(::JsonPrimitive))
    var model: String
        get() = json.str("model")?.takeIf { m -> Models.all.any { it.id == m } } ?: Models.DEFAULT
        set(value) = set("model", JsonPrimitive(value))
    var effort: String
        get() = json.str("effort")?.takeIf { it in Models.efforts } ?: Models.DEFAULT_EFFORT
        set(value) = set("effort", JsonPrimitive(value))
    var webAccess: Boolean
        get() = json.bool("webAccess") ?: false
        set(value) = set("webAccess", JsonPrimitive(value))
    var mode: CloudMode
        get() = CloudMode.fromId(json.str("mode")) ?: CloudMode.Auto
        set(value) = set("mode", JsonPrimitive(value.id))
}

/** Cloud mode, reached through the same in-memory pipe as the built-in agent. */
class CloudTransport(listener: TransportListener, config: CloudAgentConfig, apiKey: String, repoUrl: String, repoToken: String) :
    InProcessTransport(listener, { scope, send -> CloudAgent(scope, config, apiKey, repoUrl, repoToken, send) })
