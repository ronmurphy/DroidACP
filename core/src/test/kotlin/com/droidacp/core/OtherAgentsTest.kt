package com.droidacp.core

import com.droidacp.core.acp.Implementation
import com.droidacp.core.acp.PlasmaAgent
import com.droidacp.core.acp.PlasmaChat
import com.droidacp.core.acp.PlasmaHello
import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.acp.PlasmaTab
import com.droidacp.core.acp.Transport
import com.droidacp.core.acp.TransportListener
import com.droidacp.core.chat.AgentController
import com.droidacp.core.chat.ChatBookmark
import com.droidacp.core.chat.ChatBookmarks
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.core.chat.TabClose
import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.serialization.json.putJsonObject
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Other agents than Claude, one per PlasmaLLM tab (companion protocol 15): the list of agents,
 * a new tab with one, a tab whose agent doesn't start, closing and showing tabs, and bookmarks
 * that only their own agent can open. Against [FakeTabbedPlasma], which follows PlasmaLLM's
 * `docs/companion-protocol.md`, "Other agents (version 15)".
 */
class OtherAgentsTest {
    /** What escaped the controller's jobs: on Android, each would have crashed the app. */
    private val crashes = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> crashes += e })
    private val hosts = CopyOnWriteArrayList<FakeTabbedPlasma>()
    private val host: FakeTabbedPlasma get() = hosts.last()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        hosts.forEach { it.shutdown() }
    }

    private val laptop = Profile(name = "laptop", kind = ProfileKind.PlasmaLlm, url = "my-laptop", cwd = "")

    private val codex = PlasmaAgent("codex-acp", "Codex", "npx", ready = true, featured = true, version = "2.12.1")

    private suspend fun connected(configure: FakeTabbedPlasma.() -> Unit = {}): AgentController {
        val c = AgentController(scope, "test") { _, listener -> FakeTabbedPlasma(listener).apply(configure).also { hosts += it } }
        c.connect(laptop)
        c.waitFor("the computer's chat") { it.sessionId == "chat-a" && !it.startingSession && it.plasmaLlm?.tabs != null }
        return c
    }

    private suspend fun AgentController.waitFor(what: String, check: (ChatState) -> Boolean): ChatState =
        try {
            withTimeout(5_000) { state.first(check) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state: ${state.value}", e)
        }

    @Test
    fun theAgentsAndEachTabsAgentAreListed() = runBlocking {
        val c = connected()
        assertEquals(PlasmaLlm.PROTOCOL.toLong(), host.initParams?.obj("_meta")?.obj("plasmallm")?.long("protocol"), "DroidACP says which it speaks")
        assertTrue(PlasmaLlm.PROTOCOL >= PlasmaLlm.AGENTS)
        assertTrue(c.state.value.offersAgents)
        assertEquals(PlasmaLlm.CLAUDE, c.state.value.frontAgentId)
        assertEquals("t1", c.state.value.shownTab?.tabId)

        c.refreshAgents()
        val agents = c.waitFor("the agents") { it.plasmaAgents != null }.plasmaAgents!!
        assertEquals(listOf("claude-acp", "codex-acp", "antigravity-acp"), agents.map { it.id }, "in PlasmaLLM's order")
        val (claude, codex, antigravity) = agents
        assertTrue(claude.isDefault && claude.tested && claude.ready && !claude.downloads)
        assertEquals("Codex 2.12.1", codex.nameAndVersion)
        assertTrue(codex.downloads && codex.featured && !codex.tested)
        assertFalse(antigravity.ready)
        assertTrue(antigravity.why!!.startsWith("Google Antigravity is a download of its own"))
    }

    @Test
    fun aNewTabWithAnotherAgentJoinsItsChatAndStopsNothing() = runBlocking {
        val c = connected { busy("t1") }
        c.waitFor("the turn running") { it.busy }
        // The picker lists the agents first.
        c.refreshAgents()
        c.waitFor("the agents") { it.plasmaAgents != null }
        host.holdNewTab = true

        c.newAgentTab(codex, "/home/me/other")
        // While it starts (a first start downloads it): the phone names it, and lists the tab starting.
        val starting = c.waitFor("the starting tab") { s -> s.plasmaLlm?.tabs?.any { it.starting } == true }
        assertEquals(codex, starting.startingAgent)
        assertTrue(starting.startingSession)
        assertEquals(listOf("t2"), starting.tabsWithoutChat.map { it.tabId })
        assertEquals("Codex", starting.agentLabel(starting.tabsWithoutChat.single()))
        val asked = host.requests.single { it.first == "session/new" }.second
        assertEquals("codex-acp", asked.obj("_meta")?.obj("plasmallm")?.str("agent"))
        assertEquals("/home/me/other", asked.str("cwd"))

        host.answerNewTab()
        val s = c.waitFor("Codex's chat") { it.sessionId == "codex-1" && !it.startingSession }
        assertNull(s.startingAgent)
        assertEquals("codex-acp", s.frontAgentId)
        assertEquals("Codex", s.agentName)
        assertFalse(s.busy, "Claude's turn goes on in its own tab, not here")
        assertTrue(s.otherTabs.single().busy, "…and it's still working there")
        assertTrue(host.requests.none { it.first == "session/cancel" }, "nothing was stopped")
    }

    @Test
    fun aConnectionLostWhileAChatOpensDoesntCrashTheApp() = runBlocking {
        // The tablet, 2026-11-27: a ping went unanswered on a weak link while a chat opened, and
        // the request left waiting ended its job with ConnectionClosedException: a crash.
        val c = connected { holdNewTab = true }
        c.newAgentTab(codex, "/home/me/other")
        c.waitFor("the starting tab") { s -> s.plasmaLlm?.tabs?.any { it.starting } == true }
        assertTrue(host.requests.any { it.first == "session/new" }, "session/new is waiting for its answer")

        host.lose("sent ping but didn't receive pong within 20000ms", SocketTimeoutException())
        c.waitFor("the connection lost") { !it.isConnected && !it.startingSession }
        delay(500) // the waiting job's end
        assertEquals(emptyList(), crashes.map { "$it" })
    }

    @Test
    fun aTabWhoseAgentNeedsSigningInIsShownUntilItIsClosed() = runBlocking {
        val c = connected { newTabFails = PlasmaLlm.Reason.SIGN_IN_NEEDED }
        val loadsBefore = host.requests.count { it.first == "session/load" }
        c.newAgentTab(codex, "")
        val s = c.waitFor("the problem") { it.tabProblem != null && !it.startingSession }
        val problem = s.tabProblem!!
        assertEquals("t2", problem.tabId)
        assertTrue(problem.signIn)
        assertEquals("Codex", problem.agentName)
        assertEquals("Sign in to Codex with ChatGPT, on the computer.", problem.message)
        assertNull(s.sessionId)
        // The computer stays on that tab, so the phone doesn't go back to Claude's chat by itself
        // (a load would bring Claude's tab to the front).
        delay(300)
        assertEquals(loadsBefore, host.requests.count { it.first == "session/load" })
        assertNotNull(c.state.value.tabProblem)

        assertEquals(TabClose.Closed, c.closeTab("t2"))
        // PlasmaLLM brings another tab to the front, and the phone follows it.
        val back = c.waitFor("Claude's chat again") { it.sessionId == "chat-a" && !it.startingSession }
        assertNull(back.tabProblem)
        assertEquals(listOf("t1"), back.plasmaLlm?.tabs?.map { it.tabId })
    }

    @Test
    fun anAgentThatCantStartLeavesTheComputerOnItsChat() = runBlocking {
        val c = connected()
        c.newAgentTab(PlasmaAgent("antigravity-acp", "Google Antigravity", "binary", ready = true), "")
        val s = c.waitFor("back on the computer's chat") { st ->
            st.sessionId == "chat-a" && !st.startingSession && st.items.any { it is ChatItem.Note && it.text.contains("couldn't open a tab") }
        }
        val note = s.items.filterIsInstance<ChatItem.Note>().last().text
        assertEquals("PlasmaLLM couldn't open a tab with Google Antigravity: Google Antigravity is a download of its own, which Plasma LLM can't install yet.", note)
        assertNull(s.tabProblem)
        assertEquals(ConnectionStatus.Connected, s.status)
    }

    @Test
    fun closingAWorkingTabAsksFirst() = runBlocking {
        val c = connected { addTab("t3", "codex-acp", "codex-old", busy = true) }
        c.waitFor("the Codex tab") { it.otherTabs.any { t -> t.tabId == "t3" } }
        assertEquals(TabClose.Busy, c.closeTab("t3"))
        assertEquals(TabClose.Closed, c.closeTab("t3", force = true))
        assertEquals(true, host.requests.last { it.first == PlasmaLlm.CLOSE_TAB }.second.bool("force"))
        c.waitFor("the tab gone") { s -> s.plasmaLlm?.tabs?.none { it.tabId == "t3" } == true }
        // The last tab stays open, and one that's gone already counts as closed.
        assertEquals(TabClose.Refused("That's PlasmaLLM's last tab, which stays open."), c.closeTab("t1"))
        assertEquals(TabClose.Closed, c.closeTab("t9"))
    }

    @Test
    fun showingATabMovesThePhoneToItsChat() = runBlocking {
        val c = connected { addTab("t3", "codex-acp", "codex-old") }
        c.waitFor("the Codex tab") { it.otherTabs.any { t -> t.tabId == "t3" } }
        c.refreshPastSessions("/home/me/project")
        c.waitFor("Claude's chats") { it.pastSessions?.map { p -> p.sessionId } == listOf("chat-a", "chat-old") }
        assertNull(c.showTab("t3"))
        val s = c.waitFor("Codex's chat") { it.sessionId == "codex-old" && !it.startingSession }
        assertEquals("codex-acp", s.frontAgentId)
        // The Chats panel's list is the front tab's agent's: listed again, never Claude's under Codex's name.
        c.waitFor("Codex's chats") { it.pastSessions?.map { p -> p.sessionId } == listOf("codex-old") }
        assertEquals("That tab isn't open any more.", c.showTab("t9"))
    }

    @Test
    fun aBookmarkOpensOnlyWhenItsAgentsTabIsInFront() {
        fun tab(id: String, agent: String, session: String, front: Boolean = false) =
            PlasmaTab(session, "/p", null, busy = false, front = front, agentId = agent, tabId = id)
        fun state(vararg tabs: PlasmaTab, protocol: Int = 15) = ChatState(
            status = ConnectionStatus.Connected,
            sessionId = tabs.first { it.front }.sessionId,
            plasmaLlm = PlasmaHello(protocol, null, PlasmaChat(tabs.first { it.front }.sessionId, "/p", null, false), tabs = tabs.toList()),
        )
        val old = ChatBookmark("chat-old", "/p", "Before protocol 15", 1) // Claude's
        val codexChat = ChatBookmark("codex-9", "/p", "Codex's", 2, agentId = "codex-acp")

        val claudeInFront = state(tab("t1", PlasmaLlm.CLAUDE, "chat-a", front = true), tab("t2", "codex-acp", "codex-1"))
        assertNull(claudeInFront.agentNeededFor(old))
        assertEquals("codex-acp", claudeInFront.agentNeededFor(codexChat))
        assertEquals("t2", claudeInFront.tabRunning("codex-acp")?.tabId)

        val codexInFront = state(tab("t1", PlasmaLlm.CLAUDE, "chat-a"), tab("t2", "codex-acp", "codex-1", front = true))
        assertEquals(PlasmaLlm.CLAUDE, codexInFront.agentNeededFor(old))
        assertNull(codexInFront.agentNeededFor(codexChat))
        // A chat a tab has open opens by bringing that tab to the front, whoever's it is.
        assertNull(codexInFront.agentNeededFor(old.copy(sessionId = "chat-a")))
        // Before protocol 15, every tab ran Claude.
        assertNull(state(tab("t1", PlasmaLlm.CLAUDE, "chat-a", front = true), protocol = 14).agentNeededFor(codexChat))

        // The agent is kept with the bookmark; one saved before has none.
        val back = ChatBookmarks.fromJson(ChatBookmarks.toJson(listOf(codexChat, old)))
        assertEquals(listOf("codex-acp", null), back.map { it.agentId })
    }

    @Test
    fun aChatIsNamedAfterItsTabsAgentNotWhoeverWasInFrontAtConnect() {
        // PlasmaLLM's initialize names the agent in front then: here Codex's tab, before
        // the user went back to Claude's (seen on a phone on 2026-11-27: "Message Codex…").
        val claude = PlasmaTab("chat-a", "/p", null, busy = false, front = true, agentName = "@agentclientprotocol/claude-agent-acp",
            agentTitle = "Claude Agent", agentId = PlasmaLlm.CLAUDE, tabId = "t1")
        val codex = PlasmaTab("codex-1", "/q", null, busy = false, front = false, agentName = "codex-acp", agentTitle = "Codex",
            agentId = "codex-acp", tabId = "t2")
        val s = ChatState(
            status = ConnectionStatus.Connected,
            sessionId = "chat-a",
            agent = Implementation("codex-acp", "Codex", "0.9.1"),
            plasmaLlm = PlasmaHello(15, null, PlasmaChat("chat-a", "/p", null, false), tabs = listOf(claude, codex)),
        )
        assertEquals("Claude Agent", s.agentName)
        assertNull(s.agentVersion, "Codex's version isn't Claude's")

        val inCodexTab = s.copy(sessionId = "codex-1")
        assertEquals("Codex", inCodexTab.agentName)
        assertEquals("0.9.1", inCodexTab.agentVersion)

        // While the chat isn't listed yet, the tab in front; with the list fetched, its version.
        val listed = s.copy(sessionId = null, plasmaAgents = listOf(PlasmaAgent(PlasmaLlm.CLAUDE, "Claude", "default", ready = true, isDefault = true, version = "1.2.0")))
        assertEquals("Claude Agent", listed.agentName)
        assertEquals("1.2.0", listed.agentVersion)

        // Before protocol 15, the agent from initialize, as before.
        val old = s.copy(plasmaLlm = s.plasmaLlm!!.copy(protocol = 14))
        assertEquals("Codex", old.agentName)
        assertEquals("0.9.1", old.agentVersion)
    }
}

/**
 * A PlasmaLLM window with tabs, each running an agent (protocol 15): `_plasmallm/agents`,
 * `session/new` with `_meta.plasmallm.agent` opening a tab in front, `show_tab`, `close_tab`,
 * and `_plasmallm/state` naming the front tab's chat with every tab's `agentId` and `tabId`.
 */
class FakeTabbedPlasma(private val listener: TransportListener) : Transport {
    private class Tab(val tabId: String, val agentId: String, var sessionId: String, val cwd: String, var busy: Boolean = false, var activity: String? = null, var onBreak: Boolean = false)

    private val executor = Executors.newSingleThreadExecutor()
    val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    var initParams: JsonObject? = null
    private val tabs = CopyOnWriteArrayList(listOf(Tab("t1", PlasmaLlm.CLAUDE, "chat-a", "/home/me/project")))
    private var front = "t1"
    private var nextTab = 2
    private var codexChats = 0

    /** How a new agent tab ends: null, with a chat; else this error after the tab opened. */
    @Volatile
    var newTabFails: String? = null

    /** The companion protocol it speaks: 15, 17 with the to-do list, 19 with the break room. */
    @Volatile
    var protocol = 15

    /** `_plasmallm/todo`'s answer (protocol 17); null: no TASKLIST.md in the folder. */
    @Volatile
    var todo: JsonObject? = null

    /** `_plasmallm/todo` fails with this reason instead, e.g. `too_big`. */
    @Volatile
    var todoError: String? = null

    /** The folder's list changed: `_plasmallm/todo_changed`. */
    fun todoChanged(cwd: String) = deliver(buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", PlasmaLlm.TODO_CHANGED)
        put("params", buildJsonObject { put("cwd", cwd) })
    })

    /** A new agent tab waits for [answerNewTab] after it's listed as starting. */
    @Volatile
    var holdNewTab = false
    private var heldNewTab: Pair<JsonElement?, Tab>? = null

    fun busy(tabId: String) {
        tabs.first { it.tabId == tabId }.busy = true
    }

    fun addTab(tabId: String, agentId: String, sessionId: String, busy: Boolean = false) {
        tabs += Tab(tabId, agentId, sessionId, "/home/me/other", busy)
    }

    /** PlasmaLLM changes a tab's break itself (its window, or the tab asking for approval) and says so. */
    fun breakChanged(tabId: String, onBreak: Boolean) = executor.execute {
        tabs.first { it.tabId == tabId }.onBreak = onBreak
        state()
    }

    /** The connection is lost, as when a ping gets no pong: the controller hears it, nothing is answered. */
    fun lose(reason: String, error: Throwable?) = executor.execute { listener.onClosed(reason, error) }

    fun answerNewTab() = executor.execute { heldNewTab?.let { (id, tab) -> heldNewTab = null; startChat(id, tab) } }

    fun shutdown() = executor.shutdownNow()

    override fun connect() = executor.execute { listener.onOpen() }

    override fun close() = executor.execute { listener.onClosed("closed", null) }

    override fun send(text: String): Boolean {
        val msg = Json.parseToJsonElement(text) as JsonObject
        executor.execute { handle(msg) }
        return true
    }

    private val frontTab: Tab get() = tabs.first { it.tabId == front }

    private fun handle(msg: JsonObject) {
        val method = msg.str("method") ?: return
        val id = msg["id"]
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
                    putJsonObject("_meta") {
                        putJsonObject("plasmallm") {
                            put("protocol", protocol)
                            put("version", "1.0")
                            put("sessionId", frontTab.sessionId)
                            put("cwd", frontTab.cwd)
                            put("busy", frontTab.busy)
                            put("tabs", tabsJson())
                        }
                    }
                })
            }
            PlasmaLlm.AGENTS_METHOD -> reply(id, buildJsonObject {
                put("defaultAgent", PlasmaLlm.CLAUDE)
                put("agents", buildJsonArray {
                    add(buildJsonObject {
                        put("id", PlasmaLlm.CLAUDE); put("name", "Claude Agent"); put("kind", "default"); put("ready", true)
                        put("tested", true); put("featured", true); put("isDefault", true); put("description", "Claude, as set on the laptop")
                    })
                    add(buildJsonObject {
                        put("id", "codex-acp"); put("name", "Codex"); put("version", "2.12.1"); put("kind", "npx"); put("ready", true)
                        put("tested", false); put("featured", true); put("isDefault", false)
                        put("description", "ACP adapter for OpenAI's coding assistant"); put("icon", "https://cdn.example/codex.svg")
                    })
                    add(buildJsonObject {
                        put("id", "antigravity-acp"); put("name", "Google Antigravity"); put("kind", "binary"); put("ready", false)
                        put("why", "Google Antigravity is a download of its own, which Plasma LLM can't install yet.")
                        put("tested", false); put("featured", true); put("isDefault", false)
                    })
                })
            })
            "session/new" -> {
                val agent = params.obj("_meta")?.obj("plasmallm")?.str("agent")
                if (agent == null) {
                    frontTab.sessionId = "new-${nextTab++}"
                    state()
                    reply(id, buildJsonObject { put("sessionId", frontTab.sessionId) })
                    return
                }
                if (agent == "antigravity-acp") {
                    error(id, -32603, "Google Antigravity is a download of its own, which Plasma LLM can't install yet.", "agent_unavailable")
                    return
                }
                // The tab opens in front with no chat while its agent starts.
                val tab = Tab("t${nextTab++}", agent, "", params.str("cwd")?.ifEmpty { null } ?: frontTab.cwd, activity = "starting")
                tabs += tab
                front = tab.tabId
                state()
                if (holdNewTab) heldNewTab = id to tab else startChat(id, tab)
            }
            "session/load" -> {
                val sessionId = params.str("sessionId") ?: ""
                // A chat a tab has brings that tab to the front; any other replaces the front tab's.
                val has = tabs.firstOrNull { it.sessionId == sessionId }
                if (has != null) front = has.tabId else frontTab.sessionId = sessionId
                reply(id, buildJsonObject { putJsonObject("_meta") { putJsonObject("plasmallm") { put("tabId", front) } } })
                // Then the turn running in it, then the state, as PlasmaLLM sends them.
                if (frontTab.busy) {
                    deliver(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", PlasmaLlm.TURN)
                        put("params", buildJsonObject { put("sessionId", frontTab.sessionId); put("state", "started") })
                    })
                }
                state()
            }
            // Each agent's own chats: the front tab's agent answers.
            "session/list" -> reply(id, buildJsonObject {
                put("sessions", buildJsonArray {
                    val chats = if (frontTab.agentId == PlasmaLlm.CLAUDE) listOf("chat-a", "chat-old") else listOf("codex-old")
                    chats.forEach { c -> add(buildJsonObject { put("sessionId", c); put("cwd", params.str("cwd") ?: "") }) }
                })
            })
            PlasmaLlm.TODO_METHOD -> {
                val cwd = params.str("cwd") ?: frontTab.cwd
                val reason = todoError
                if (reason != null) return error(id, -32602, "Plasma LLM couldn't read the to-do list.", reason)
                reply(id, todo ?: buildJsonObject {
                    put("cwd", cwd)
                    put("path", "$cwd/TASKLIST.md")
                    put("exists", false)
                    put("rows", buildJsonArray {})
                    put("markdown", "")
                })
            }
            PlasmaLlm.BREAK_METHOD -> {
                val tab = tabs.firstOrNull { it.tabId == params.str("tabId") }
                val onBreak = params.bool("onBreak")
                when {
                    params.str("tabId") == null || onBreak == null -> error(id, -32602, "break needs tabId and onBreak.", "invalid_params")
                    tab == null -> error(id, -32602, "Plasma LLM has no such tab now.", "no_tab")
                    else -> {
                        tab.onBreak = onBreak
                        reply(id, JsonObject(emptyMap()))
                        state()
                    }
                }
            }
            PlasmaLlm.SHOW_TAB -> {
                val tab = tabs.firstOrNull { it.tabId == params.str("tabId") }
                if (tab == null) return error(id, -32602, "Plasma LLM has no such tab now.", "no_tab")
                front = tab.tabId
                reply(id, JsonObject(emptyMap()))
                state()
            }
            PlasmaLlm.CLOSE_TAB -> {
                val tab = tabs.firstOrNull { it.tabId == params.str("tabId") }
                when {
                    tab == null -> error(id, -32602, "Plasma LLM has no such tab now.", "no_tab")
                    tabs.size < 2 -> error(id, -32602, "That's Plasma LLM's last tab, which stays open.", "last_tab")
                    tab.busy && params.bool("force") != true -> error(id, -32603, "That tab's agent is working.", "busy")
                    else -> {
                        tabs.remove(tab)
                        if (front == tab.tabId) front = tabs.first().tabId
                        reply(id, JsonObject(emptyMap()))
                        state()
                    }
                }
            }
            else -> reply(id, JsonObject(emptyMap()))
        }
    }

    /** The new tab's agent has started (or not): its chat, after the devices hear the switch. */
    private fun startChat(id: JsonElement?, tab: Tab) {
        tab.activity = null
        val fails = newTabFails
        if (fails != null) {
            state()
            val message = if (fails == PlasmaLlm.Reason.SIGN_IN_NEEDED) "Sign in to Codex with ChatGPT, on the computer." else "The agent exited (code 1)."
            error(id, -32603, message, fails, tabId = tab.tabId)
            return
        }
        tab.sessionId = "codex-${++codexChats}"
        state()
        reply(id, buildJsonObject {
            put("sessionId", tab.sessionId)
            putJsonObject("_meta") { putJsonObject("plasmallm") { put("tabId", tab.tabId) } }
        })
    }

    private fun tabsJson() = buildJsonArray {
        tabs.forEach { t ->
            add(buildJsonObject {
                put("sessionId", t.sessionId)
                put("cwd", t.cwd)
                put("busy", t.busy)
                put("front", t.tabId == front)
                put("agentId", t.agentId)
                put("tabId", t.tabId)
                put("tabletTools", t.agentId == PlasmaLlm.CLAUDE && t.sessionId.isNotEmpty())
                t.activity?.let { put("activity", it) }
                if (t.onBreak) put("onBreak", true)
            })
        }
    }

    private fun state() = deliver(buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", PlasmaLlm.STATE)
        put("params", buildJsonObject {
            put("sessionId", frontTab.sessionId)
            put("cwd", frontTab.cwd)
            put("busy", frontTab.busy)
            put("tabs", tabsJson())
        })
    })

    private fun deliver(msg: JsonObject) = executor.execute { listener.onMessage(msg.toString()) }

    private fun reply(id: JsonElement?, result: JsonObject) = deliver(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonPrimitive(0))
        put("result", result)
    })

    private fun error(id: JsonElement?, code: Int, message: String, reason: String, tabId: String? = null) = deliver(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonPrimitive(0))
        putJsonObject("error") {
            put("code", code)
            put("message", message)
            putJsonObject("data") {
                putJsonObject("plasmallm") {
                    put("reason", reason)
                    if (tabId != null) put("tabId", tabId)
                }
            }
        }
    })
}
