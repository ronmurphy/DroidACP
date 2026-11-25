package com.droidacp.core

import com.droidacp.core.acp.Transport
import com.droidacp.core.acp.TransportListener
import com.droidacp.core.chat.AgentController
import com.droidacp.core.chat.ChatBookmark
import com.droidacp.core.chat.ChatBookmarks
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moving between chats (the title bar's Chats panel): each one opens in its own folder,
 * and nothing of the chat left behind reaches the next one.
 */
class ChatSwitchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val agents = CopyOnWriteArrayList<FakeAcpAgent>()
    private val agent: FakeAcpAgent get() = agents.last()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        agents.forEach { it.shutdown() }
    }

    private val bridge = Profile(name = "desk", kind = ProfileKind.Bridge, url = "ws://desk:8765", cwd = "/home/me/app")

    private fun controller(configure: FakeAcpAgent.() -> Unit = {}) =
        AgentController(scope, "test") { _, listener -> FakeAcpAgent(listener).apply(configure).also { agents += it } }

    private suspend fun AgentController.waitFor(what: String, check: (ChatState) -> Boolean): ChatState =
        try {
            withTimeout(5_000) { state.first(check) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state: ${state.value}", e)
        }

    private suspend fun AgentController.open(sessionId: String) = waitFor("chat $sessionId") { it.canChat && it.sessionId == sessionId }

    @Test
    fun anotherChatOpensInItsOwnFolderAndTheOldOneIsClosed() = runBlocking {
        val c = controller { saved["old-1"] = "/home/me/other" to "Fix the widget" }
        c.connect(bridge)
        c.open("s-1")
        assertEquals("/home/me/app", c.state.value.folder)

        c.openPastSession("old-1", cwd = "/home/me/other")
        val s = c.open("old-1")

        assertEquals("s-1", agent.waitForRequests("session/close", 1).single().str("sessionId"))
        assertEquals("/home/me/other", agent.requests.last { it.first == "session/load" }.second.str("cwd"))
        assertEquals("/home/me/other", s.folder, "the title bar shows the chat's own folder")
    }

    @Test
    fun anAgentThatCantCloseSessionsIsntAsked() = runBlocking {
        val c = controller { canClose = false }
        c.connect(bridge)
        c.open("s-1")
        c.newSession()
        c.open("s-2")
        assertTrue(agent.requests.none { it.first == "session/close" })
    }

    @Test
    fun nothingFromALeftChatReachesTheNextOne() = runBlocking {
        val c = controller()
        c.connect(bridge)
        c.open("s-1")
        agent.holdNew = true
        c.newSession()
        c.waitFor("switching") { it.sessionId == null && it.startingSession }

        // While the new chat is starting (its id not known yet), the old one still talks.
        agent.update("s-1", agentChunk("from the old chat"))
        val asked = agent.askPermission("s-1", "tool-1")
        agent.releaseNew()
        c.open("s-2")
        agent.update("s-1", agentChunk("still the old chat"))
        agent.update("s-2", agentChunk("the new chat"))

        val s = c.waitFor("the new chat's message") { st -> st.items.any { it.text() == "the new chat" } }
        assertEquals(listOf("the new chat"), s.items.map { it.text() })
        assertTrue(s.permissions.isEmpty(), "the old chat's question isn't shown")
        assertEquals("cancelled", withTimeout(5_000) { asked.await() }.obj("outcome")?.str("outcome"))
    }

    @Test
    fun aQuestionWaitingInTheOldChatIsAnsweredCancelled() = runBlocking {
        val c = controller()
        c.connect(bridge)
        c.open("s-1")
        val asked = agent.askPermission("s-1", "tool-1")
        c.waitFor("the question") { it.permissions.isNotEmpty() }

        c.openPastSession("old-1")
        assertEquals("cancelled", withTimeout(5_000) { asked.await() }.obj("outcome")?.str("outcome"))
        assertTrue(c.open("old-1").permissions.isEmpty())
    }

    @Test
    fun aNewChatGoesToTheChosenFolderAndListsStayThere() = runBlocking {
        val c = controller {
            saved["a-1"] = "/home/me/app" to "In app"
            saved["b-1"] = "/home/me/other" to "In other"
        }
        c.connect(bridge)
        c.open("s-1")

        c.newSession("/home/me/other")
        val s = c.open("s-2")
        assertEquals("/home/me/other", agent.requests.last { it.first == "session/new" }.second.str("cwd"))
        assertEquals("/home/me/other", s.folder)

        // "New session" and the list now follow the open chat's folder, not the saved agent's.
        c.refreshPastSessions()
        val listed = c.waitFor("the list") { it.pastSessions != null }
        assertEquals("/home/me/other", listed.pastSessionsCwd)
        assertEquals(listOf("b-1"), listed.pastSessions?.map { it.sessionId })
    }

    @Test
    fun anotherFoldersChatsNeverShowUnderThisOnesName() = runBlocking {
        val c = controller {
            saved["a-1"] = "/home/me/app" to "In app"
            saved["b-1"] = "/home/me/other" to "In other"
        }
        c.connect(bridge)
        c.open("s-1")
        c.refreshPastSessions("/home/me/app")
        c.waitFor("app's chats") { it.pastSessions?.map { s -> s.sessionId } == listOf("a-1") }

        agent.holdList = true
        c.refreshPastSessions("/home/me/other")
        val loading = c.waitFor("listing other") { it.pastSessionsCwd == "/home/me/other" }
        assertNull(loading.pastSessions, "app's chats aren't shown as other's while it loads")
        agent.releaseList()
        assertEquals(listOf("b-1"), c.waitFor("other's chats") { it.pastSessions != null }.pastSessions?.map { it.sessionId })
    }

    @Test
    fun reconnectingReopensTheChatInItsOwnFolder() = runBlocking {
        val c = controller()
        c.connect(bridge)
        c.open("s-1")
        c.openPastSession("old-1", cwd = "/home/me/other")
        c.open("old-1")

        // Reconnecting keeps the transcript on screen, so it resumes rather than loads.
        c.reconnect()
        val reopened = agent.waitForRequests("session/resume", 1).single()
        assertEquals("old-1", reopened.str("sessionId"))
        assertEquals("/home/me/other", reopened.str("cwd"))

        // After a restart, the app passes the folder it saved with the last chat.
        c.connect(bridge, resumeSessionId = "old-1", resumeCwd = "/home/me/other")
        assertEquals("/home/me/other", agent.waitForRequests("session/load", 1).single().str("cwd"))
    }

    @Test
    fun theOldChatsTurnEndingDoesntEndTheNewChatsTurn() = runBlocking {
        val c = controller()
        c.connect(bridge)
        c.open("s-1")
        assertTrue(c.sendPrompt("first"))
        agent.waitForRequests("session/prompt", 1)

        // The old turn's "cancelled" answer is slow; meanwhile a new chat starts working.
        c.newSession()
        c.open("s-2")
        assertTrue(c.sendPrompt("second"))
        agent.waitForRequests("session/prompt", 2)
        agent.finishPrompt("s-1", "cancelled")
        delay(200)
        assertTrue(c.state.value.busy, "s-2 is still working")
        agent.finishPrompt("s-2", "end_turn")
        assertFalse(c.waitFor("s-2 done") { !it.busy }.busy)
    }

    @Test
    fun aBookmarkIsCheckedInItsOwnFolderBeforeItOpens() = runBlocking {
        val c = controller { saved["kept"] = "/home/me/other" to "Still here" }
        c.connect(bridge)
        c.open("s-1")

        assertEquals(true, c.chatExists("kept", "/home/me/other"))
        assertEquals("/home/me/other", agent.requests.last { it.first == "session/list" }.second.str("cwd"))
        assertEquals(false, c.chatExists("deleted", "/home/me/other"), "a deleted chat isn't opened")
        assertTrue(agent.requests.none { it.first == "session/load" }, "checking opens nothing")

        agent.failList = true
        assertNull(c.chatExists("kept", "/home/me/other"), "a failed check says so, rather than guessing")
    }

    @Test
    fun bookmarksToggleRetitleAndSurviveStorage() {
        val a = ChatBookmark("a", "/home/me/app", "Widget taps", 1)
        val b = ChatBookmark("b", "/home/me/other", null, 2)
        var list = ChatBookmarks.toggle(emptyList(), a)
        list = ChatBookmarks.toggle(list, b)
        assertEquals(listOf("b", "a"), list.map { it.sessionId }, "newest first")
        assertTrue(ChatBookmarks.contains(list, "a"))
        assertFalse(ChatBookmarks.contains(list, null))

        list = ChatBookmarks.retitle(list, "b", "Folder picker")
        assertEquals("Folder picker", list.first().title)
        assertTrue(ChatBookmarks.retitle(list, "zzz", "x") === list, "an unsaved chat changes nothing")

        assertEquals(list, ChatBookmarks.fromJson(ChatBookmarks.toJson(list)))
        assertEquals(listOf("b"), ChatBookmarks.toggle(list, a).map { it.sessionId }, "toggling a saved chat removes it")
        assertTrue(ChatBookmarks.fromJson("not json").isEmpty())
        assertEquals(ChatBookmarks.MAX, (1..80).fold(emptyList<ChatBookmark>()) { l, i -> ChatBookmarks.toggle(l, ChatBookmark("$i", "", null, i.toLong())) }.size)
    }
}

private fun ChatItem.text(): String = when (this) {
    is ChatItem.User -> text
    is ChatItem.Agent -> text
    is ChatItem.Thought -> text
    is ChatItem.Note -> text
    is ChatItem.Tool -> call.title
}

private fun agentChunk(text: String) = buildJsonObject {
    put("sessionUpdate", "agent_message_chunk")
    putJsonObject("content") { put("type", "text"); put("text", text) }
}

/** A plain ACP agent: new, load (with history), list by folder, close, and prompts the test finishes. */
class FakeAcpAgent(private val listener: TransportListener) : Transport {
    private val executor = Executors.newSingleThreadExecutor()
    val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    var canClose = true

    /** sessionId to (folder, title), for session/list. */
    val saved = ConcurrentHashMap<String, Pair<String, String>>()
    var holdNew = false
    var holdList = false
    var failList = false
    private var heldNew: JsonElement? = null
    private var heldList: Pair<JsonElement?, String?>? = null
    private val prompts = ConcurrentHashMap<String, JsonElement>()
    private val permissionReplies = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var newChats = 0
    private var nextId = 1000L

    fun shutdown() = executor.shutdownNow()

    suspend fun waitForRequests(method: String, count: Int): List<JsonObject> = withTimeout(5_000) {
        var got = requests.filter { it.first == method }
        while (got.size < count) {
            delay(10)
            got = requests.filter { it.first == method }
        }
        got.take(count).map { it.second }
    }

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

    fun update(sessionId: String, update: JsonObject) = deliver(buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "session/update")
        putJsonObject("params") {
            put("sessionId", sessionId)
            put("update", update)
        }
    })

    /** Ask a permission question in [sessionId]; completes with the app's answer. */
    fun askPermission(sessionId: String, toolCallId: String): CompletableDeferred<JsonObject> {
        val id = nextId++
        val answer = CompletableDeferred<JsonObject>()
        permissionReplies[id.toString()] = answer
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
        return answer
    }

    fun releaseNew() = executor.execute {
        holdNew = false
        heldNew?.let { replyNew(it) }
    }

    fun releaseList() = executor.execute {
        holdList = false
        heldList?.let { (id, cwd) -> replyList(id, cwd) }
    }

    fun finishPrompt(sessionId: String, stopReason: String) = executor.execute {
        prompts.remove(sessionId)?.let { reply(it, buildJsonObject { put("stopReason", stopReason) }) }
    }

    private fun deliver(msg: JsonObject) = executor.execute { listener.onMessage(msg.toString()) }

    private fun handle(msg: JsonObject) {
        val method = msg.str("method")
        val id = msg["id"]
        if (method == null) {
            // The app's answer to a permission question.
            permissionReplies.remove((id as? JsonPrimitive)?.content)?.complete(msg.obj("result") ?: JsonObject(emptyMap()))
            return
        }
        val params = msg.obj("params") ?: JsonObject(emptyMap())
        requests += method to params
        when (method) {
            "initialize" -> reply(id, buildJsonObject {
                put("protocolVersion", 1)
                putJsonObject("agentInfo") { put("name", "fake-agent") }
                putJsonObject("agentCapabilities") {
                    put("loadSession", true)
                    putJsonObject("sessionCapabilities") {
                        putJsonObject("list") {}
                        putJsonObject("resume") {}
                        if (canClose) putJsonObject("close") {}
                    }
                }
                put("authMethods", buildJsonArray {})
            })
            "session/new" -> if (holdNew) heldNew = id else replyNew(id)
            "session/list" -> when {
                failList -> deliver(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonPrimitive(0))
                    putJsonObject("error") { put("code", -32603); put("message", "listing failed") }
                })
                holdList -> heldList = id to params.str("cwd")
                else -> replyList(id, params.str("cwd"))
            }
            "session/prompt" -> prompts[params.str("sessionId") ?: ""] = id ?: JsonPrimitive(0)
            "session/cancel" -> {}
            else -> reply(id, JsonObject(emptyMap()))
        }
    }

    private fun replyNew(id: JsonElement?) = reply(id, buildJsonObject { put("sessionId", "s-${++newChats}") })

    private fun replyList(id: JsonElement?, cwd: String?) = reply(id, buildJsonObject {
        putJsonArray("sessions") {
            saved.filter { cwd == null || it.value.first == cwd }.toSortedMap().forEach { (sessionId, info) ->
                add(buildJsonObject {
                    put("sessionId", sessionId)
                    put("cwd", info.first)
                    put("title", info.second)
                })
            }
        }
    })

    private fun reply(id: JsonElement?, result: JsonObject) = deliver(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonPrimitive(0))
        put("result", result)
    })
}
