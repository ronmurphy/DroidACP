package com.droidacp.core.cloud

import com.droidacp.core.acp.ToolContent
import com.droidacp.core.chat.AgentController
import com.droidacp.core.chat.AgentEvent
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A pretend Managed Agents service. A test gives it a script: what the "cloud"
 * does in answer to each event the app sends.
 */
class FakeCloud(private val react: suspend FakeCloud.(sent: JsonObject) -> Unit) : CloudApi {
    val created = mutableListOf<String>() // "agent", "environment", "session" in the order they were made
    var sessionAgent: JsonObject? = null
    var sessionResources: List<JsonObject> = emptyList()
    val sent = mutableListOf<JsonObject>()
    val history = mutableListOf<JsonObject>()
    val updates = mutableListOf<Pair<String?, JsonArray?>>()
    private var live = Channel<JsonObject>(Channel.UNLIMITED)
    private var nextId = 1

    /** If set, the next live stream breaks after this many events (to test reconnecting). */
    var breakStreamAfter: Int? = null

    /** The cloud emits an event: it goes into history and to whoever is listening. */
    fun emit(json: String) {
        val event = Json.parseToJsonElement(json) as JsonObject
        val withId = if (event["id"] == null && event.str("type")?.startsWith("event_") != true) {
            JsonObject(event + ("id" to kotlinx.serialization.json.JsonPrimitive("sevt_${nextId++}")))
        } else {
            event
        }
        if (withId.str("type")?.startsWith("event_") != true) history += withId
        live.trySend(withId)
    }

    override suspend fun createAgent(name: String, model: String, system: String, tools: JsonArray): String = "agent_1".also { created += "agent" }

    override suspend fun createEnvironment(name: String, config: JsonObject): String = "env_1".also { created += "environment" }

    override suspend fun createSession(agent: JsonObject, environmentId: String, title: String, resources: List<JsonObject>): String {
        created += "session"
        sessionAgent = agent
        sessionResources = resources
        return "sesn_${created.count { it == "session" }}"
    }

    override suspend fun updateSession(sessionId: String, title: String?, tools: JsonArray?) {
        updates += title to tools
    }

    override suspend fun listSessions(agentId: String) = listOf(CloudSessionInfo("sesn_1", "Fix the bug", "2026-11-24T10:00:00Z"))

    override suspend fun listEvents(sessionId: String): List<JsonObject> = history.toList()

    override suspend fun send(sessionId: String, event: JsonObject) {
        sent += event
        if (event.str("type") == "user.message") history += JsonObject(event + ("id" to kotlinx.serialization.json.JsonPrimitive("sevt_${nextId++}")))
        react(event)
    }

    override fun stream(sessionId: String): Flow<JsonObject> = flow {
        emit(buildJsonObject { put("type", CloudApi.STREAM_OPEN) })
        var count = 0
        for (event in live) {
            emit(event)
            if (++count == breakStreamAfter) {
                breakStreamAfter = null
                // Events sent while nobody is listening are lost from the live stream.
                live = Channel(Channel.UNLIMITED)
                throw CloudException("connection reset")
            }
        }
    }
}

class CloudAgentTest {
    private val stateDir: File = Files.createTempDirectory("droidacp-cloud").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        stateDir.deleteRecursively()
    }

    private fun controller(cloud: FakeCloud): AgentController {
        val config = CloudAgentConfig(stateDir, "test", apiFactory = { cloud })
        return AgentController(scope, "test") { profile, listener -> CloudTransport(listener, config, profile.apiKey, profile.repoUrl, profile.repoToken) }
    }

    private val profile = Profile(
        name = "cloud", kind = ProfileKind.Cloud, apiKey = "sk-test", cwd = "/workspace",
        repoUrl = "https://github.com/me/app", repoToken = "ghp_x",
    )

    private suspend fun AgentController.connectAndWait(resume: String? = null): ChatState {
        connect(profile, resumeSessionId = resume)
        val ready = state.first { it.canChat || it.status is ConnectionStatus.Failed }
        assertEquals(ConnectionStatus.Connected, ready.status, "connect failed: ${ready.items}")
        return ready
    }

    private suspend fun AgentController.promptAndWait(text: String): ChatState {
        // UNDISPATCHED: start listening right now, before the prompt is sent, so a fast reply can't slip past.
        val finished = scope.launch(start = CoroutineStart.UNDISPATCHED) { events.first { it is AgentEvent.TurnFinished } }
        assertTrue(sendPrompt(text))
        finished.join()
        return state.first { !it.busy }
    }

    private fun ChatState.tools() = items.filterIsInstance<ChatItem.Tool>().map { it.call }

    /** A turn with streamed text, an allowed tool, a tool that asks, and usage. */
    private val editTurn: suspend FakeCloud.(JsonObject) -> Unit = { sent ->
        when (sent.str("type")) {
            "user.message" -> {
                emit("""{"type":"session.status_running"}""")
                emit("""{"type":"event_start","event":{"type":"agent.message","id":"sevt_m1"}}""")
                emit("""{"type":"event_delta","event_id":"sevt_m1","delta":{"type":"content_delta","index":0,"content":{"type":"text","text":"Looking "}}}""")
                emit("""{"type":"agent.message","id":"sevt_m1","content":[{"type":"text","text":"Looking at it."}]}""")
                emit("""{"type":"agent.tool_use","id":"sevt_t1","name":"read","input":{"file_path":"/workspace/app/a.txt"},"evaluated_permission":"allow"}""")
                emit("""{"type":"agent.tool_result","tool_use_id":"sevt_t1","content":[{"type":"text","text":"hello world"}]}""")
                emit("""{"type":"agent.tool_use","id":"sevt_t2","name":"edit","input":{"file_path":"/workspace/app/a.txt","old_string":"world","new_string":"there"},"evaluated_permission":"ask"}""")
                emit("""{"type":"session.status_idle","stop_reason":{"type":"requires_action","event_ids":["sevt_t2"]}}""")
            }
            "user.tool_confirmation" -> {
                if (sent.str("result") == "allow") {
                    emit("""{"type":"agent.tool_result","tool_use_id":"sevt_t2","content":[{"type":"text","text":"ok"}]}""")
                    emit("""{"type":"agent.message","content":[{"type":"text","text":"Changed it."}]}""")
                } else {
                    emit("""{"type":"agent.message","content":[{"type":"text","text":"Left it alone."}]}""")
                }
                emit("""{"type":"span.model_request_end","model_usage":{"input_tokens":100,"cache_read_input_tokens":50,"cache_creation_input_tokens":0,"output_tokens":25}}""")
                emit("""{"type":"session.usage","list_cost":{"amount":"12","currency":"USD"}}""")
                emit("""{"type":"session.status_idle","stop_reason":{"type":"end_turn"}}""")
            }
        }
    }

    @Test
    fun runsATurnWithAToolThatAsksAndReloadsTheSession() = runBlocking {
        val cloud = FakeCloud(editTurn)
        val controller = controller(cloud)
        scope.launch {
            controller.state.collect { s ->
                s.permissions.firstOrNull()?.let { p -> controller.answerPermission(p.requestId, p.options.first { it.isAllow }.optionId) }
            }
        }

        withTimeout(20_000) {
            val ready = controller.connectAndWait()
            assertEquals("Claude (cloud)", ready.agentName)
            assertEquals(listOf("auto", "ask", "allow"), ready.modes.map { it.id })
            assertEquals(listOf("agent", "environment", "session"), cloud.created)

            // The session is created with this app's model, tool rules and repository.
            val agent = cloud.sessionAgent!!
            assertEquals("agent_with_overrides", agent.str("type"))
            assertEquals("claude-opus-5-5", agent.obj("model")?.str("id"))
            val toolset = agent.objects("tools").single()
            assertEquals("auto", toolset.obj("default_config")?.obj("permission_policy")?.str("type"))
            assertEquals(listOf("web_fetch" to false, "web_search" to false), toolset.objects("configs").map { it.str("name") to it.bool("enabled") })
            assertEquals("https://github.com/me/app", cloud.sessionResources.single().str("url"))

            val done = controller.promptAndWait("Fix the greeting")
            assertEquals(listOf("Looking at it.", "Changed it."), done.items.filterIsInstance<ChatItem.Agent>().map { it.text })
            val tools = done.tools()
            assertEquals(listOf("Read /workspace/app/a.txt", "Edit /workspace/app/a.txt"), tools.map { it.title })
            assertEquals(listOf("completed", "completed"), tools.map { it.status })
            assertTrue(tools[1].content.single() is ToolContent.Diff)
            assertEquals(175L, done.usage?.used)
            assertEquals(0.12, done.usage?.costAmount)
            assertEquals("Fix the greeting", done.sessionTitle)
            assertEquals("Fix the greeting", cloud.updates.first().first)
            assertEquals(listOf("user.message", "user.tool_confirmation"), cloud.sent.map { it.str("type") })
            assertEquals("sevt_t2", cloud.sent[1].str("tool_use_id"))

            // Reopen it later: the conversation comes back from the cloud, and nothing new is created.
            controller.disconnect()
            val again = controller(cloud)
            again.connect(profile, resumeSessionId = "sesn_1")
            val reloaded = again.state.first { it.canChat && it.items.count { i -> i is ChatItem.Agent } == 2 }
            assertEquals("Fix the greeting", (reloaded.items.first() as ChatItem.User).text)
            assertEquals(2, reloaded.tools().size)
            assertEquals(listOf("agent", "environment", "session"), cloud.created)
            again.refreshPastSessions()
            assertEquals("Fix the bug", again.state.first { it.pastSessions != null }.pastSessions!!.single().title)
            again.disconnect()
        }
    }

    @Test
    fun rejectingAToolSendsADenial() = runBlocking {
        val cloud = FakeCloud(editTurn)
        val controller = controller(cloud)
        scope.launch {
            controller.state.collect { s ->
                s.permissions.firstOrNull()?.let { p -> controller.answerPermission(p.requestId, p.options.first { !it.isAllow }.optionId) }
            }
        }
        withTimeout(20_000) {
            controller.connectAndWait()
            val done = controller.promptAndWait("Fix the greeting")
            assertEquals("deny", cloud.sent[1].str("result"))
            assertNotNull(cloud.sent[1].str("deny_message"))
            assertEquals(listOf("completed", "failed"), done.tools().map { it.status })
            assertEquals("Left it alone.", done.lastAgentText)
            controller.disconnect()
        }
    }

    @Test
    fun aDroppedConnectionIsRepairedFromHistory() = runBlocking {
        val cloud = FakeCloud { sent ->
            if (sent.str("type") == "user.message") {
                emit("""{"type":"agent.message","content":[{"type":"text","text":"one "}]}""")
                // The stream breaks after the event above; these two only exist in history.
                emit("""{"type":"agent.message","content":[{"type":"text","text":"two "}]}""")
                emit("""{"type":"session.status_idle","stop_reason":{"type":"end_turn"}}""")
            }
        }
        cloud.breakStreamAfter = 1
        val controller = controller(cloud)
        withTimeout(20_000) {
            controller.connectAndWait()
            val done = controller.promptAndWait("count")
            assertEquals(listOf("one ", "two "), done.items.filterIsInstance<ChatItem.Agent>().map { it.text }, "each message exactly once")
            assertEquals(1, cloud.sent.size, "the question must not be sent twice")
            controller.disconnect()
        }
    }

    @Test
    fun stopSendsAnInterruptAndModeChangesUpdateTheSession() = runBlocking {
        val cloud = FakeCloud { sent ->
            when (sent.str("type")) {
                "user.message" -> emit("""{"type":"agent.message","content":[{"type":"text","text":"Working..."}]}""")
                "user.interrupt" -> emit("""{"type":"session.status_idle","stop_reason":{"type":"end_turn"}}""")
            }
        }
        val controller = controller(cloud)
        withTimeout(20_000) {
            controller.connectAndWait()
            assertTrue(controller.sendPrompt("go"))
            controller.state.first { it.items.lastOrNull() is ChatItem.Agent }
            controller.cancelTurn()
            val stopped = controller.state.first { !it.busy }
            assertTrue(stopped.items.any { it is ChatItem.Note && it.text == "Stopped." }, "items: ${stopped.items}")
            assertEquals("user.interrupt", cloud.sent.last().str("type"))

            controller.setMode("ask")
            controller.state.first { it.currentModeId == "ask" }
            val tools = cloud.updates.last().second!!
            val asking = tools[0].let { it as JsonObject }.objects("configs").filter { it.obj("permission_policy")?.str("type") == "always_ask" }
            assertEquals(listOf("bash", "write", "edit"), asking.map { it.str("name") })
            assertFalse(File(stateDir, "nothing").exists())
            controller.disconnect()
        }
    }
}
