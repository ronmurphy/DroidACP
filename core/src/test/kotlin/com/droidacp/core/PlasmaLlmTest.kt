package com.droidacp.core

import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.acp.Transport
import com.droidacp.core.acp.TransportListener
import com.droidacp.core.chat.AgentController
import com.droidacp.core.chat.AgentEvent
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DroidACP as PlasmaLLM's companion, against a fake PlasmaLLM that follows
 * `docs/companion-protocol.md` (version 1) in PlasmaLLM's repository: the join,
 * following the computer's chat, turns sent from the computer, permission prompts
 * answered elsewhere, and the error reasons.
 */
class PlasmaLlmTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hosts = CopyOnWriteArrayList<FakePlasmaLlm>()
    private val host: FakePlasmaLlm get() = hosts.last()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        hosts.forEach { it.shutdownExecutor() }
    }

    private val plasmaProfile = Profile(name = "laptop", kind = ProfileKind.PlasmaLlm, url = "my-laptop", cwd = "")

    private fun controller(configure: FakePlasmaLlm.() -> Unit = {}) =
        AgentController(scope, "test") { _, listener -> FakePlasmaLlm(listener).apply(configure).also { hosts += it } }

    private suspend fun AgentController.waitFor(what: String, check: (ChatState) -> Boolean): ChatState =
        try {
            withTimeout(5_000) { state.first(check) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state: ${state.value}", e)
        }

    private suspend fun AgentController.joined(sessionId: String) =
        waitFor("chat $sessionId") { it.canChat && it.sessionId == sessionId }

    // --- addresses ---

    @Test
    fun computerNamesBecomeWebSocketAddresses() {
        assertEquals("ws://my-laptop:8766", PlasmaLlm.url("my-laptop"))
        assertEquals("ws://my-laptop:9000", PlasmaLlm.url(" my-laptop:9000 "))
        assertEquals("ws://100.101.102.103:8766", PlasmaLlm.url("100.101.102.103"))
        assertEquals("ws://[fd7a:115c::1]:8766", PlasmaLlm.url("fd7a:115c::1"))
        assertEquals("ws://[fd7a:115c::1]:9000", PlasmaLlm.url("[fd7a:115c::1]:9000"))
        assertEquals("wss://laptop.tail1234.ts.net:443", PlasmaLlm.url("wss://laptop.tail1234.ts.net:443"))
        assertEquals("ws://my-laptop:8766", plasmaProfile.connectUrl)
        assertTrue(plasmaProfile.validate().isEmpty(), "an empty folder is fine for PlasmaLLM")
        assertTrue(plasmaProfile.copy(url = "").validate().isNotEmpty())
        assertTrue(plasmaProfile.copy(cwd = "relative/path").validate().isNotEmpty())
    }

    // --- joining ---

    @Test
    fun declaresItselfAndJoinsTheChatOpenOnTheComputer() = runBlocking {
        val c = controller { history["chat-a"] = listOf(userChunk("hello", "plasmallm-1"), agentChunk("hi there")) }
        // A saved last session is ignored: the phone shows what the computer has open.
        c.connect(plasmaProfile, resumeSessionId = "old-phone-chat")
        val s = c.joined("chat-a")

        assertEquals(1L, host.initParams?.obj("_meta")?.obj("plasmallm")?.long("protocol"))
        val load = host.requests.single { it.first == "session/load" }.second
        assertEquals("chat-a", load.str("sessionId"))
        assertEquals("/home/me/project", load.str("cwd"), "loads with the chat's own folder")
        assertTrue(host.requests.none { it.first == "session/new" })
        assertEquals("Open chat", s.sessionTitle)
        assertEquals(listOf("hello", "hi there"), c.waitFor("history") { it.items.size == 2 }.items.map { it.text() })
        assertEquals("0.9", s.plasmaLlm?.version)
    }

    @Test
    fun startNewMakesANewChatOnTheComputer() = runBlocking {
        val c = controller()
        c.connect(plasmaProfile.copy(cwd = "/home/me/other"), startNew = true)
        c.joined("new-1")
        val new = host.requests.single { it.first == "session/new" }.second
        assertEquals("/home/me/other", new.str("cwd"))
        assertTrue(host.requests.none { it.first == "session/load" })
    }

    @Test
    fun aBridgeProfileStaysPlainAcp() = runBlocking {
        val c = controller()
        c.connect(Profile(name = "bridge", url = "ws://x:8765", cwd = "/home/me/project"))
        c.joined("new-1")
        assertNull(host.initParams?.obj("_meta"), "only PlasmaLLM profiles declare themselves")
        assertNull(c.state.value.plasmaLlm)
    }

    @Test
    fun somethingElseOnThePortIsTreatedAsPlainAcp() = runBlocking {
        val c = controller { isPlasma = false }
        c.connect(plasmaProfile.copy(cwd = "/home/me/project"))
        val s = c.joined("new-1")
        assertNull(s.plasmaLlm)
        assertTrue(s.items.any { it is ChatItem.Note && "not as PlasmaLLM" in it.text })
    }

    // --- following the computer ---

    @Test
    fun followsWhenTheComputerOpensAnotherChat() = runBlocking {
        val c = controller { history["chat-b"] = listOf(agentChunk("from chat b")) }
        c.connect(plasmaProfile)
        c.joined("chat-a")

        host.notify(PlasmaLlm.STATE, chatState("", "/home/me/project")) // switching: ignored
        host.notify(PlasmaLlm.STATE, chatState("chat-b", "/home/me/other", title = "Chat B"))
        val s = c.joined("chat-b")
        assertEquals("/home/me/other", host.requests.last { it.first == "session/load" }.second.str("cwd"))
        assertEquals(listOf("from chat b"), c.waitFor("chat b's history") { it.items.isNotEmpty() }.items.map { it.text() })
        assertEquals("Chat B", s.sessionTitle)
        assertEquals(2, host.requests.count { it.first == "session/load" }, "no load for the empty state")
    }

    @Test
    fun aPromptToALeftChatFollowsTheOpenOne() = runBlocking {
        val c = controller { promptError = { notOpenChat("chat-c") } }
        c.connect(plasmaProfile)
        c.joined("chat-a")
        assertTrue(c.sendPrompt("are you there?"))
        val s = c.joined("chat-c")
        val notes = c.waitFor("the note") { st -> st.items.any { it is ChatItem.Note } }.items.filterIsInstance<ChatItem.Note>()
        assertTrue(notes.any { "wasn't sent" in it.text }, "notes: $notes")
        assertFalse(s.busy)
    }

    @Test
    fun aFailedOpenFollowsTheChatPlasmaLlmStartedInstead() = runBlocking {
        val c = controller { failLoads += "chat-gone" }
        c.connect(plasmaProfile)
        c.joined("chat-a")
        c.openPastSession("chat-gone")
        c.joined("new-1")
        assertTrue(host.requests.none { it.first == "session/new" }, "no new chat of our own: PlasmaLLM made one")
    }

    // --- turns sent from the computer ---

    @Test
    fun showsATurnSentFromTheComputer() = runBlocking {
        val c = controller()
        c.connect(plasmaProfile)
        c.joined("chat-a")
        val finished = async(start = CoroutineStart.UNDISPATCHED) { c.events.first { it is AgentEvent.TurnFinished } }

        host.notify(PlasmaLlm.STATE, chatState("chat-a", "/home/me/project", busy = true))
        host.notify(PlasmaLlm.TURN, turn("chat-a", "started"))
        host.update("chat-a", userChunk("typed on the laptop", "plasmallm-2"))
        host.update("chat-a", agentChunk("answer"))
        val busy = c.waitFor("the remote turn") { it.busy && it.items.size == 2 }
        assertTrue(busy.remoteTurn)
        assertFalse(c.sendPrompt("me too"), "PlasmaLLM takes one prompt at a time")

        host.notify(PlasmaLlm.STATE, chatState("chat-a", "/home/me/project"))
        host.notify(PlasmaLlm.TURN, turn("chat-a", "ended", "end_turn"))
        val done = c.waitFor("the end of the turn") { !it.busy }
        assertFalse(done.remoteTurn)
        assertEquals(listOf("typed on the laptop", "answer"), done.items.map { it.text() })
        assertEquals("answer", (withTimeout(5_000) { finished.await() } as AgentEvent.TurnFinished).lastText)
    }

    @Test
    fun twoPromptsInARowStayTwoMessages() = runBlocking {
        val c = controller { history["chat-a"] = listOf(userChunk("first", "plasmallm-1"), userChunk("second", "plasmallm-2")) }
        c.connect(plasmaProfile)
        c.joined("chat-a")
        val s = c.waitFor("history") { it.items.size == 2 }
        assertEquals(listOf("first", "second"), s.items.map { it.text() })
    }

    @Test
    fun joiningMidTurnShowsItAsRunning() = runBlocking {
        val c = controller { runningTurn = true }
        c.connect(plasmaProfile)
        c.joined("chat-a")
        assertTrue(c.waitFor("busy") { it.busy }.remoteTurn)
    }

    // --- permissions ---

    @Test
    fun aPromptAnsweredOnTheComputerCloses() = runBlocking {
        val c = controller()
        c.connect(plasmaProfile)
        c.joined("chat-a")
        host.askPermission("chat-a", "tool-1")
        c.waitFor("the permission prompt") { it.permissions.isNotEmpty() }

        host.notify(PlasmaLlm.PERMISSION_RESOLVED, buildJsonObject { put("sessionId", "chat-a"); put("toolCallId", "tool-1") })
        c.waitFor("the prompt to close") { it.permissions.isEmpty() }
        val reply = withTimeout(5_000) { host.permissionReply.await() }
        assertEquals("cancelled", reply.obj("outcome")?.str("outcome"), "answered so the request doesn't hang; PlasmaLLM ignores it")
    }

    // --- helpers ---

    private fun ChatItem.text(): String = when (this) {
        is ChatItem.User -> text
        is ChatItem.Agent -> text
        else -> toString()
    }

    private fun chatState(sessionId: String, cwd: String, title: String? = null, busy: Boolean = false) = buildJsonObject {
        put("sessionId", sessionId)
        put("cwd", cwd)
        if (title != null) put("title", title)
        put("busy", busy)
    }

    private fun turn(sessionId: String, state: String, stopReason: String? = null) = buildJsonObject {
        put("sessionId", sessionId)
        put("state", state)
        if (stopReason != null) put("stopReason", stopReason)
    }

    private fun notOpenChat(open: String) = RpcError(
        -32602,
        "Plasma LLM has another chat open now",
        buildJsonObject { putJsonObject("plasmallm") { put("reason", "not_open_chat"); put("sessionId", open) } },
    )
}

private fun userChunk(text: String, messageId: String) = buildJsonObject {
    put("sessionUpdate", "user_message_chunk")
    put("messageId", messageId)
    putJsonObject("content") { put("type", "text"); put("text", text) }
}

private fun agentChunk(text: String) = buildJsonObject {
    put("sessionUpdate", "agent_message_chunk")
    putJsonObject("content") { put("type", "text"); put("text", text) }
}

class RpcError(val code: Int, val message: String, val data: JsonElement?)

/**
 * Just enough of PlasmaLLM's phone access, as its spec describes it: answers in
 * order on one thread, like a WebSocket would deliver them.
 */
class FakePlasmaLlm(private val listener: TransportListener) : Transport {
    private val executor = Executors.newSingleThreadExecutor()
    val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    var initParams: JsonObject? = null
    var isPlasma = true
    var runningTurn = false
    val history = mutableMapOf<String, List<JsonObject>>()
    val failLoads = mutableSetOf<String>()
    var promptError: (() -> RpcError)? = null
    val permissionReply = kotlinx.coroutines.CompletableDeferred<JsonObject>()
    private var newChats = 0
    private var nextId = 1000L
    private var permissionId: Long? = null
    private var open = "chat-a"
    private var openCwd = "/home/me/project"

    fun shutdownExecutor() = executor.shutdownNow()

    override fun connect() {
        executor.execute { listener.onOpen() }
    }

    override fun close() {
        executor.execute { listener.onClosed("closed", null) }
    }

    override fun send(text: String): Boolean {
        val msg = Json.parseToJsonElement(text) as JsonObject
        executor.execute { handle(msg) }
        return true
    }

    fun notify(method: String, params: JsonObject) = deliver(buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
    })

    fun update(sessionId: String, update: JsonObject) = notify("session/update", buildJsonObject {
        put("sessionId", sessionId)
        put("update", update)
    })

    fun askPermission(sessionId: String, toolCallId: String) {
        val id = nextId++
        permissionId = id
        deliver(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "session/request_permission")
            putJsonObject("params") {
                put("sessionId", sessionId)
                putJsonObject("toolCall") { put("toolCallId", toolCallId); put("title", "Edit file") }
                put("options", buildJsonArray {
                    add(buildJsonObject { put("optionId", "allow"); put("name", "Allow"); put("kind", "allow_once") })
                })
            }
        })
    }

    private fun deliver(msg: JsonObject) = executor.execute { listener.onMessage(msg.toString()) }

    private fun handle(msg: JsonObject) {
        val method = msg.str("method")
        val id = msg["id"]
        if (method == null) {
            // A reply to our permission request.
            if ((id as? JsonPrimitive)?.content == permissionId?.toString()) msg.obj("result")?.let { permissionReply.complete(it) }
            return
        }
        val params = msg.obj("params") ?: JsonObject(emptyMap())
        requests += method to params
        when (method) {
            "initialize" -> {
                initParams = params
                reply(id, buildJsonObject {
                    put("protocolVersion", 1)
                    putJsonObject("agentInfo") { put("name", "claude-agent-acp") }
                    putJsonObject("agentCapabilities") {
                        put("loadSession", true)
                        putJsonObject("sessionCapabilities") { putJsonObject("list") {} }
                    }
                    put("authMethods", buildJsonArray {})
                    if (isPlasma) {
                        putJsonObject("_meta") {
                            putJsonObject("plasmallm") {
                                put("protocol", 1)
                                put("version", "0.9")
                                put("sessionId", open)
                                put("cwd", openCwd)
                                put("title", "Open chat")
                                put("busy", runningTurn)
                            }
                        }
                    }
                })
            }
            "session/load" -> {
                val sessionId = params.str("sessionId") ?: ""
                if (sessionId in failLoads) {
                    open = "new-${++newChats}"
                    state()
                    error(id, RpcError(-32603, "Couldn't open that chat", buildJsonObject {
                        putJsonObject("plasmallm") { put("reason", "open_failed"); put("sessionId", open) }
                    }))
                    return
                }
                open = sessionId
                openCwd = params.str("cwd")?.ifEmpty { null } ?: openCwd
                history[sessionId].orEmpty().forEach { update(sessionId, it) }
                reply(id, JsonObject(emptyMap()))
                if (runningTurn) notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", open); put("state", "started") })
                state()
            }
            "session/new" -> {
                open = "new-${++newChats}"
                reply(id, buildJsonObject { put("sessionId", open) })
                if (isPlasma) state()
            }
            "session/prompt" -> {
                val e = promptError
                if (e != null) {
                    open = (e().data as? JsonObject)?.obj("plasmallm")?.str("sessionId") ?: open
                    state()
                    error(id, e())
                } else {
                    reply(id, buildJsonObject { put("stopReason", "end_turn") })
                }
            }
            else -> reply(id, JsonObject(emptyMap()))
        }
    }

    private fun state() = notify(PlasmaLlm.STATE, buildJsonObject {
        put("sessionId", open)
        put("cwd", openCwd)
        put("busy", runningTurn)
    })

    private fun reply(id: JsonElement?, result: JsonObject) = deliver(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonPrimitive(0))
        put("result", result)
    })

    private fun error(id: JsonElement?, e: RpcError) = deliver(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonPrimitive(0))
        putJsonObject("error") {
            put("code", e.code)
            put("message", e.message)
            if (e.data != null) put("data", e.data)
        }
    })
}
