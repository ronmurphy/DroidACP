package com.droidacp.core.agent

import com.droidacp.core.acp.Transport
import com.droidacp.core.acp.TransportListener
import com.droidacp.core.rpc.JsonRpcConnection
import com.droidacp.core.rpc.RpcErrorCodes
import com.droidacp.core.rpc.RpcException
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** What the built-in agent needs from the app it lives in. */
class LocalAgentConfig(
    /** Where conversations are saved. */
    val sessionsDir: File,
    /** Shown to the model, e.g. "Android 15 (Pixel Tablet)". */
    val platform: String,
    val version: String,
    /** How to run a shell command. The command line is added as the last argument. */
    val shell: List<String> = defaultShell(),
    val shellEnv: Map<String, String> = emptyMap(),
    /** Makes the model client for an API key. Tests swap in a scripted fake. */
    val modelFactory: (apiKey: String) -> ModelClient = { AnthropicModelClient(it) },
) {
    companion object {
        /** Android keeps its shell in /system/bin; everywhere else `sh` is on the PATH. */
        fun defaultShell(): List<String> = listOf(if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh", "-c")
    }
}

/**
 * The agent's half of the Agent Client Protocol.
 *
 * The app's `AcpClient` sends requests like `session/new` and `session/prompt`; this
 * class answers them. It is the same job `claude-agent-acp` does on a computer, and
 * it speaks the same JSON-RPC, which is why the rest of the app can't tell (and
 * doesn't care) that this agent lives in the same process.
 */
class LocalAgent(
    private val scope: CoroutineScope,
    private val config: LocalAgentConfig,
    private val apiKey: String,
    send: (String) -> Boolean,
) : ClientPort {
    // JsonRpcConnection is symmetric, so the class the client uses works for the agent too.
    private val rpc = JsonRpcConnection(scope, send, ::handleRequest, ::handleNotification)
    private val store = SessionStore(config.sessionsDir)
    private val model: ModelClient by lazy { config.modelFactory(apiKey) }

    private val sessions = ConcurrentHashMap<String, AgentSession>()

    /** The turn currently running in each session, so `session/cancel` can stop it. */
    private val turns = ConcurrentHashMap<String, Deferred<String>>()

    fun receive(text: String) = rpc.receive(text)

    fun close() {
        rpc.close("Agent stopped")
        (model as? AutoCloseable)?.close()
    }

    // ------------------------------------------------------------------
    // Requests from the app
    // ------------------------------------------------------------------

    private suspend fun handleRequest(method: String, params: JsonElement?): JsonElement {
        val p = params as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> initialize()
            "authenticate" -> JsonObject(emptyMap())
            "session/new" -> {
                val session = open(SessionData(id = UUID.randomUUID().toString(), cwd = p.str("cwd") ?: throw invalid("cwd is required")))
                announceCommands(session)
                setup(session) { put("sessionId", session.data.id) }
            }
            "session/load", "session/resume" -> {
                val id = p.str("sessionId") ?: throw invalid("sessionId is required")
                val session = sessions[id] ?: open(store.load(id) ?: throw RpcException(RpcErrorCodes.RESOURCE_NOT_FOUND, "Session not found"))
                // `load` shows the old conversation again; `resume` assumes the app still has it on screen.
                if (method == "session/load") session.replay()
                announceCommands(session)
                setup(session)
            }
            "session/list" -> buildJsonObject { put("sessions", JsonArray(store.list(p.str("cwd")))) }
            "session/prompt" -> prompt(session(p), p.objects("prompt"))
            "session/set_mode" -> {
                val mode = AgentMode.fromId(p.str("modeId")) ?: throw invalid("Unknown mode ${p.str("modeId")}")
                session(p).setMode(mode)
                JsonObject(emptyMap())
            }
            "session/set_config_option" -> setConfigOption(session(p), p)
            else -> throw RpcException(RpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun handleNotification(method: String, params: JsonElement?) {
        if (method == "session/cancel") {
            val id = (params as? JsonObject)?.str("sessionId") ?: return
            turns[id]?.cancel()
        }
    }

    private fun initialize(): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        putJsonObject("agentInfo") {
            put("name", "droidacp-agent")
            put("title", "Claude (on this device)")
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
            }
        }
        // No sign-in flow: the API key comes from the saved profile.
        put("authMethods", buildJsonArray { })
    }

    /** Make an [AgentSession] for [data], after checking we can actually work in its folder. */
    private fun open(data: SessionData): AgentSession {
        val cwd = File(data.cwd)
        if (!cwd.exists()) cwd.mkdirs()
        if (!cwd.isDirectory || !cwd.canRead()) {
            throw invalid("Can't open the folder ${data.cwd}. Check that it exists and that DroidACP is allowed to access files there.")
        }
        val ctx = ToolContext(cwd, config.shell, config.shellEnv)
        return AgentSession(data, model, ctx, config.platform, this, store).also { sessions[data.id] = it }
    }

    private suspend fun prompt(session: AgentSession, blocks: List<JsonObject>): JsonObject {
        val id = session.data.id
        if (turns[id]?.isActive == true) throw invalid("A turn is already running in this session")
        if (apiKey.isBlank()) throw RpcException(RpcErrorCodes.INTERNAL_ERROR, "No API key. Edit this agent and add your Anthropic API key.")

        // The turn runs as its own job so `session/cancel` can stop just the turn.
        val turn = scope.async { session.prompt(blocks) }
        turns[id] = turn
        val stopReason = try {
            turn.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // if the whole agent is shutting down, just go
            "cancelled"
        } catch (e: ModelException) {
            throw RpcException(RpcErrorCodes.INTERNAL_ERROR, e.message ?: "The model request failed")
        } finally {
            turns.remove(id, turn)
        }
        return buildJsonObject { put("stopReason", stopReason) }
    }

    private fun setConfigOption(session: AgentSession, p: JsonObject): JsonObject {
        val data = session.data
        val value = p["value"] as? JsonPrimitive ?: throw invalid("value is required")
        when (p.str("configId")) {
            CONFIG_MODEL -> data.model = value.content.takeIf { v -> Models.all.any { it.id == v } } ?: throw invalid("Unknown model ${value.content}")
            CONFIG_EFFORT -> data.effort = value.content.takeIf { it in Models.efforts } ?: throw invalid("Unknown effort ${value.content}")
            CONFIG_WEB -> data.webAccess = value.booleanOrNull ?: throw invalid("value must be true or false")
            else -> throw invalid("Unknown config option ${p.str("configId")}")
        }
        return buildJsonObject { put("configOptions", configOptions(data)) }
    }

    // ------------------------------------------------------------------
    // What a session looks like to the app
    // ------------------------------------------------------------------

    /** The result of `session/new`, `session/load` and `session/resume`. */
    private fun setup(session: AgentSession, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        extra()
        putJsonObject("modes") {
            put("currentModeId", session.data.mode.id)
            putJsonArray("availableModes") {
                AgentMode.entries.forEach { mode ->
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
        put("configOptions", configOptions(session.data))
    }

    private fun configOptions(data: SessionData): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("id", CONFIG_MODEL)
                put("name", "Model")
                put("category", "model")
                put("type", "select")
                put("currentValue", data.model)
                putJsonArray("options") {
                    Models.all.forEach { m ->
                        add(
                            buildJsonObject {
                                put("value", m.id)
                                put("name", m.name)
                                put("description", m.description)
                            },
                        )
                    }
                }
            },
        )
        add(
            buildJsonObject {
                put("id", CONFIG_EFFORT)
                put("name", "Effort")
                put("description", "How hard the model thinks. Higher is more thorough, slower and costs more.")
                put("category", "thought_level")
                put("type", "select")
                put("currentValue", data.effort)
                putJsonArray("options") {
                    Models.efforts.forEach { e ->
                        add(
                            buildJsonObject {
                                put("value", e)
                                put("name", e.replaceFirstChar(Char::uppercase))
                            },
                        )
                    }
                }
            },
        )
        add(
            buildJsonObject {
                put("id", CONFIG_WEB)
                put("name", "Web search")
                put("description", "Let the model search the web and read pages. Searches are billed by Anthropic.")
                put("type", "boolean")
                put("currentValue", data.webAccess)
            },
        )
    }

    private fun announceCommands(session: AgentSession) {
        update(
            session.data.id,
            buildJsonObject {
                put("sessionUpdate", "available_commands_update")
                putJsonArray("availableCommands") {
                    add(
                        buildJsonObject {
                            put("name", "init")
                            put("description", "Write a CLAUDE.md that describes this project")
                        },
                    )
                }
            },
        )
    }

    private fun session(p: JsonObject): AgentSession =
        sessions[p.str("sessionId")] ?: throw RpcException(RpcErrorCodes.RESOURCE_NOT_FOUND, "Unknown session ${p.str("sessionId")}")

    private fun invalid(message: String) = RpcException(RpcErrorCodes.INVALID_PARAMS, message)

    // ------------------------------------------------------------------
    // ClientPort: messages from the agent to the app
    // ------------------------------------------------------------------

    override fun update(sessionId: String, update: JsonObject) {
        rpc.notify(
            "session/update",
            buildJsonObject {
                put("sessionId", sessionId)
                put("update", update)
            },
        )
    }

    override suspend fun requestPermission(sessionId: String, toolCall: JsonObject, options: JsonArray): String? {
        val result = rpc.request(
            "session/request_permission",
            buildJsonObject {
                put("sessionId", sessionId)
                put("toolCall", toolCall)
                put("options", options)
            },
        ) as? JsonObject
        val outcome = result?.obj("outcome")
        return if (outcome?.str("outcome") == "selected") outcome.str("optionId") else null
    }

    private companion object {
        const val CONFIG_MODEL = "model"
        const val CONFIG_EFFORT = "effort"
        const val CONFIG_WEB = "web"
    }
}

/**
 * Connects the app to a [LocalAgent] in the same process.
 *
 * It behaves like a network socket without the network: each direction is a queue
 * (a coroutine [Channel]) with one coroutine delivering messages in order. Using
 * queues rather than calling the other side directly means a sender never waits for
 * the receiver, exactly as with a real connection.
 */
class LocalTransport(
    private val listener: TransportListener,
    config: LocalAgentConfig,
    apiKey: String,
) : Transport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toAgent = Channel<String>(Channel.UNLIMITED)
    private val toClient = Channel<String>(Channel.UNLIMITED)
    private val finished = AtomicBoolean(false)
    private val agent = LocalAgent(scope, config, apiKey) { text -> toClient.trySend(text).isSuccess }

    override fun connect() {
        scope.launch {
            listener.onOpen()
            for (text in toClient) listener.onMessage(text)
        }
        scope.launch {
            for (text in toAgent) agent.receive(text)
        }
    }

    override fun send(text: String): Boolean = !finished.get() && toAgent.trySend(text).isSuccess

    override fun close() {
        if (!finished.compareAndSet(false, true)) return
        agent.close()
        scope.cancel() // stops a running turn, including its HTTP request and any shell command
        listener.onClosed("Disconnected", null)
    }
}
