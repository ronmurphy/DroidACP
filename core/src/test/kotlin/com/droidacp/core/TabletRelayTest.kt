package com.droidacp.core

import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.chat.AgentController
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.core.rpc.RpcErrorCodes
import com.droidacp.core.rpc.RpcException
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.str
import com.droidacp.core.tablet.PausedOnTablet
import com.droidacp.core.tablet.Tablet
import com.droidacp.core.tablet.TabletLink
import com.droidacp.core.tablet.TabletRelay
import com.droidacp.core.tablet.TabletStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tablet relay (plan D1–D8, D10): DroidACP on the tablet offers TabDesk's tools to
 * PlasmaLLM's agent and passes the calls on, against the fake PlasmaLLM (now speaking
 * companion protocol 2, as `docs/companion-protocol.md`'s "Version 2: the tablet" says) and
 * a fake TabDesk (`docs/agent-control.md` in TabDesk's repository).
 */
class TabletRelayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hosts = CopyOnWriteArrayList<FakePlasmaLlm>()
    private val host: FakePlasmaLlm get() = hosts.last()
    private val tabdesk = FakeTabDesk()

    /** Each hold saved (D20), in order. */
    private val savedHolds = CopyOnWriteArrayList<Boolean>()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        hosts.forEach { it.shutdownExecutor() }
    }

    private val profile = Profile(name = "laptop", kind = ProfileKind.PlasmaLlm, url = "my-laptop", cwd = "", useTablet = true)

    /** Reconnecting by itself waits a minute unless a test says otherwise. */
    private fun controller(
        tablet: Boolean = true,
        held: Boolean = false,
        reconnect: List<Long> = listOf(60_000L),
        progressTimeout: Long = AgentController.PROGRESS_TIMEOUT_MS,
        configure: FakePlasmaLlm.() -> Unit = { protocol = 7 },
    ) = AgentController(
        scope,
        "test",
        tablet = Tablet(tabdesk, SERVER_ID, held) { savedHolds += it }.takeIf { tablet },
        reconnectDelaysMs = reconnect,
        progressTimeoutMs = progressTimeout,
    ) { _, listener ->
        FakePlasmaLlm(listener).apply(configure).also { hosts += it }
    }

    private suspend fun AgentController.waitFor(what: String, check: (ChatState) -> Boolean): ChatState =
        try {
            withTimeout(5_000) { state.first(check) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state: ${state.value}", e)
        }

    private suspend fun AgentController.joined(sessionId: String) =
        waitFor("chat $sessionId") { it.canChat && it.sessionId == sessionId }

    private suspend fun AgentController.declared() = waitFor("the tablet declared") { it.tablet?.declared == true }

    private suspend fun waitUntil(what: String, check: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!check()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    /** The tablet entry each session/new, load and resume carried (null: none). */
    private fun declaredServers() = host.declarations().map { (method, servers) -> method to servers?.singleOrNull() as? JsonObject }

    private fun screenCall(requestId: String = "mcp-request:7", serverId: String = SERVER_ID, sessionId: String = "chat-a") = buildJsonObject {
        put("serverId", serverId)
        put("requestId", requestId)
        put("method", "tools/call")
        putJsonObject("params") {
            put("name", "screen")
            putJsonObject("arguments") { put("screenshot", "none") }
        }
        put("sessionId", sessionId)
        put("toolCallId", "tool-1")
    }

    private fun JsonObject.errorCode() = obj("error")?.long("code")

    // --- turning it on, and declaring (D1, D4) ---

    @Test
    fun saysProtocol2AndDeclaresTheTabletOnTheFirstLoad() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        val s = c.declared()

        assertTrue(PlasmaLlm.PROTOCOL >= 2)
        assertEquals(listOf(true), tabdesk.wanted, "TabDesk is connected because the setting is on")
        val (method, entry) = declaredServers().single()
        assertEquals("session/load", method)
        assertEquals("acp", entry?.str("type"))
        assertEquals("Lenovo Tab One", entry?.str("name"), "named as TabDesk names the tablet")
        assertEquals(SERVER_ID, entry?.str("serverId"))
        assertTrue(s.capabilities.mcpOverAcp)
        assertEquals(true, s.plasmaLlm?.tabletTools)
        assertTrue(s.tablet?.offered == true)
    }

    @Test
    fun declaresOnEveryLoadAndNewChat() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        host.notify(PlasmaLlm.STATE, buildJsonObject { put("sessionId", "chat-b"); put("cwd", "/home/me/project") })
        c.joined("chat-b")
        c.newSession()
        c.joined("new-1")
        // Every request carried it. (Which ones: the state for chat-b can still be on its way
        // when "new" is pressed, and then chat-b is followed once more first.)
        assertTrue(declaredServers().all { it.second?.str("serverId") == SERVER_ID }, "declarations: ${declaredServers()}")
        val opened = host.requests.filter { it.first in listOf("session/new", "session/load", "session/resume") }
        assertEquals(setOf("chat-a", "chat-b", null), opened.map { it.second.str("sessionId") }.toSet())
        assertEquals("session/new", opened.last().first)
    }

    @Test
    fun notDeclaredWithoutTheSettingTabDeskOrPlasmaLlmsSupport() = runBlocking {
        // The setting off: TabDesk isn't even connected.
        controller().apply { connect(profile.copy(useTablet = false)); joined("chat-a") }
        assertEquals(listOf(false), tabdesk.wanted)
        assertNull(declaredServers().single().second)

        // A PlasmaLLM before protocol 2: no capability, no declaration.
        controller { protocol = 1 }.apply { connect(profile); joined("chat-a") }
        assertNull(declaredServers().single().second)

        // No TabDesk on this device.
        controller(tablet = false).apply { connect(profile); joined("chat-a") }
        assertNull(declaredServers().single().second)

        // TabDesk hasn't approved DroidACP yet.
        tabdesk.approveOnConnect = false
        val c = controller()
        c.connect(profile)
        val s = c.joined("chat-a")
        assertNull(declaredServers().single().second)
        assertEquals("asked", s.tablet?.status?.approval)
        assertFalse(s.tablet?.declared == true)
    }

    @Test
    fun declaredEvenWhileTheLaptopsSettingIsOff() = runBlocking {
        val c = controller { protocol = 7; tabletTools = false }
        c.connect(profile)
        c.joined("chat-a")
        val s = c.declared()
        assertEquals(false, s.plasmaLlm?.tabletTools)
        host.tabletTools = true
        host.state()
        c.waitFor("the laptop's setting on") { it.plasmaLlm?.tabletTools == true }
        Unit
    }

    @Test
    fun approvalArrivingWhileConnectedDeclaresWithADeclareOnlyResume() = runBlocking {
        tabdesk.approveOnConnect = false
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        tabdesk.approve()
        c.declared()

        val resume = host.requests.last { it.first == "session/resume" }.second
        assertEquals("chat-a", resume.str("sessionId"))
        assertEquals("true", resume.obj("_meta")?.obj("plasmallm")?.get("declareOnly")?.toString())
        assertEquals(SERVER_ID, (resume["mcpServers"] as JsonArray).single().let { it as JsonObject }.str("serverId"))
        assertEquals(1, host.requests.count { it.first == "session/load" }, "no history sent again")
        assertEquals(1, hosts.size, "on the same connection")
    }

    @Test
    fun theSettingTurnedOnWhileConnectedDeclaresToo() = runBlocking {
        val c = controller()
        c.connect(profile.copy(useTablet = false))
        c.joined("chat-a")
        assertNull(c.state.value.tablet)
        c.setTabletAllowed(true)
        c.declared()
        assertEquals(listOf(false, true), tabdesk.wanted)
        assertEquals("session/resume", declaredServers().last().first)
    }

    @Test
    fun aDeclareOnlyResumeRefusedAsNotOpenChatStillCountsAndFollows() = runBlocking {
        tabdesk.approveOnConnect = false
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        // The laptop moved on without telling us yet.
        host.open = "chat-b"
        tabdesk.approve()
        c.declared()
        c.joined("chat-b")
        assertEquals(1, host.requests.count { it.first == "session/resume" }, "not asked again")
    }

    // --- passing calls on (D5) ---

    @Test
    fun aCallIsPassedThroughUnchangedBothWays() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()

        val answer = host.mcpMessage(41, screenCall())
        val (id, sent) = tabdesk.nextSent()
        assertEquals("c1:mcp-request:7", id, "<connection>:<requestId>")
        val request = Json.parseToJsonElement(sent) as JsonObject
        assertEquals("2.0", request.str("jsonrpc"))
        assertEquals("mcp-request:7", request.str("id"), "MCP's id is PlasmaLLM's requestId")
        assertEquals("tools/call", request.str("method"))
        assertEquals(screenCall()["params"], request["params"])
        waitUntil("the call counted") { c.state.value.tablet?.calls == 1 }

        val result = buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "The text below was read …") }) })
            put("isError", false)
        }
        tabdesk.answer(id, buildJsonObject { put("jsonrpc", "2.0"); put("id", "mcp-request:7"); put("result", result) }.toString())
        val reply = withTimeout(5_000) { answer.await() }
        assertEquals(buildJsonObject { put("result", result) }, reply["result"])
        c.waitFor("no calls running") { it.tablet?.calls == 0 }
        Unit
    }

    @Test
    fun tabDesksRefusalsAndErrorsComeBackAsTheyAre() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()

        // A refusal is a result with isError, passed on like any result.
        val refused = host.mcpMessage(41, screenCall("mcp-request:1"))
        val (first, _) = tabdesk.nextSent()
        val refusal = buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "not_approved: open TabDesk → Customize → Agent") }) })
            put("isError", true)
        }
        tabdesk.answer(first, buildJsonObject { put("jsonrpc", "2.0"); put("id", "mcp-request:1"); put("result", refusal) }.toString())
        assertEquals(refusal, withTimeout(5_000) { refused.await() }.obj("result")?.obj("result"))

        // An MCP error stays MCP's, inside the result.
        val failed = host.mcpMessage(42, screenCall("mcp-request:2"))
        val (second, _) = tabdesk.nextSent()
        val error = buildJsonObject { put("code", -32602); put("message", "Unknown tool") }
        tabdesk.answer(second, buildJsonObject { put("jsonrpc", "2.0"); put("id", "mcp-request:2"); put("error", error) }.toString())
        val reply = withTimeout(5_000) { failed.await() }
        assertNull(reply["error"], "not an ACP error")
        assertEquals(error, reply.obj("result")?.obj("error"))
    }

    @Test
    fun anotherServerIdOrTabDeskUnreachableIsRefusedWith33001() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()

        val wrong = withTimeout(5_000) { host.mcpMessage(41, screenCall(serverId = "droidacp:someone-else")).await() }
        assertEquals(-33001L, wrong.errorCode())

        tabdesk.status.value = tabdesk.status.value.copy(reach = TabletStatus.Reach.Lost)
        val gone = withTimeout(5_000) { host.mcpMessage(42, screenCall("mcp-request:8")).await() }
        assertEquals(-33001L, gone.errorCode())
        assertTrue(tabdesk.sentIds.isEmpty(), "nothing reached TabDesk")
    }

    @Test
    fun tabDeskDyingMidCallAnswers33002() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        val answer = host.mcpMessage(41, screenCall())
        tabdesk.nextSent()
        tabdesk.die()
        assertEquals(-33002L, withTimeout(5_000) { answer.await() }.errorCode())
    }

    @Test
    fun malformedOrRepeatedRequestsAreRefusedWith32602() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()

        val noMethod = buildJsonObject { put("serverId", SERVER_ID); put("requestId", "mcp-request:1") }
        assertEquals(-32602L, withTimeout(5_000) { host.mcpMessage(40, noMethod).await() }.errorCode())

        val first = host.mcpMessage(41, screenCall())
        tabdesk.nextSent()
        val again = withTimeout(5_000) { host.mcpMessage(42, screenCall()).await() }
        assertEquals(-32602L, again.errorCode(), "that requestId is already running")
        assertFalse(first.isCompleted, "the first one still runs")
        assertEquals(1, tabdesk.sentIds.size)
    }

    // --- cancelling (D6) ---

    @Test
    fun cancelRequestCancelsAtTabDeskAndAnswers32800AtOnce() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        val answer = host.mcpMessage(41, screenCall())
        val (id, _) = tabdesk.nextSent()

        host.cancelRequest(41)
        assertEquals(-32800L, withTimeout(5_000) { answer.await() }.errorCode())
        waitUntil("TabDesk's cancel") { id in tabdesk.cancelled }
        c.waitFor("no calls running") { it.tablet?.calls == 0 }
        Unit
    }

    @Test
    fun theConnectionClosingCancelsTheRunningCallAtTabDesk() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        host.mcpMessage(41, screenCall())
        val (id, _) = tabdesk.nextSent()
        host.close()
        waitUntil("TabDesk's cancel") { id in tabdesk.cancelled }
        val s = c.waitFor("the drop") { !it.isConnected }
        assertFalse(s.tablet?.declared == true, "a registration ends with its connection")
    }

    // --- the setting turned off (D2) ---

    @Test
    fun turningTheSettingOffRefusesCallsWithoutReconnecting() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        val running = host.mcpMessage(41, screenCall())
        val (id, _) = tabdesk.nextSent()

        c.setTabletAllowed(false)
        assertEquals(-33001L, withTimeout(5_000) { running.await() }.errorCode(), "the running call ends")
        waitUntil("TabDesk's cancel") { id in tabdesk.cancelled }
        val later = withTimeout(5_000) { host.mcpMessage(42, screenCall("mcp-request:8")).await() }
        assertEquals(-33001L, later.errorCode())
        assertEquals(1, hosts.size, "no reconnect")
        assertEquals(listOf(true, false), tabdesk.wanted, "TabDesk let go")
        assertNull(c.state.value.tablet, "no bar")
    }

    // --- one Stop, and going on again (D7) ---

    @Test
    fun ourStopReachesTabDeskAndTheNextMessageResumes() = runBlocking {
        val c = controller { protocol = 7; holdPrompts = true }
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        assertTrue(c.sendPrompt("use the tablet"))
        assertEquals(1, tabdesk.resumes.get(), "our own message lets it go on")
        c.waitFor("busy") { it.busy }
        host.waitForRequests("session/prompt", 1) // sent from a coroutine: let it arrive first
        c.cancelTurn()
        assertEquals(1, tabdesk.stops.get())
        // The fake records requests on its own thread.
        waitUntil("session/cancel") { host.requests.any { it.first == "session/cancel" } }
        c.waitFor("the bar says stopped") { it.tablet?.stopped == true }

        host.answerPrompt(buildJsonObject { put("stopReason", "cancelled") })
        c.waitFor("idle") { !it.busy }
        assertTrue(c.sendPrompt("go on"))
        assertEquals(2, tabdesk.resumes.get())
        c.waitFor("going on") { it.tablet?.stopped == false }
        Unit
    }

    @Test
    fun aTurnFromElsewhereResumesOnlyWhenAPersonSentIt() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        tabdesk.stop()

        // wake_me, a note from another tab, Claude by itself: no.
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", "app") })
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "ended"); put("stopReason", "end_turn") })
        c.waitFor("the app's turn over") { it.items.isNotEmpty() || !it.busy }
        delay(100)
        assertEquals(0, tabdesk.resumes.get())

        // Typed on the laptop or the phone: yes.
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", "user") })
        waitUntil("resume()") { tabdesk.resumes.get() == 1 }
    }

    @Test
    fun stopOnTabDeskCancelsTheTurnThatUsedIt() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", "user") })
        c.waitFor("the laptop's turn") { it.busy }
        host.mcpMessage(41, screenCall())
        tabdesk.nextSent()

        tabdesk.stopOnTablet()
        waitUntil("session/cancel") { host.requests.any { it.first == "session/cancel" && it.second.str("sessionId") == "chat-a" } }
        c.waitFor("stopping") { it.cancelling && it.tablet?.stopped == true }

        // Our own Stop coming back (reason caller) sends nothing more.
        tabdesk.stop()
        delay(100)
        assertEquals(1, host.requests.count { it.first == "session/cancel" })
    }

    @Test
    fun stopOnTabDeskCancelsABackTabsTurnFromProtocol13() = runBlocking {
        assertEquals(listOf("chat-b"), stopWhileABackTabUsesTheTablet(13))
    }

    @Test
    fun stopOnTabDeskLeavesABackTabsTurnBeforeProtocol13() = runBlocking {
        assertEquals(emptyList(), stopWhileABackTabUsesTheTablet(12))
    }

    /** Stop on TabDesk while tab B, in the back, uses the tablet: the chats `session/cancel` named. */
    private suspend fun stopWhileABackTabUsesTheTablet(protocol: Int): List<String?> {
        val c = controller { this.protocol = protocol }
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        host.notify(PlasmaLlm.STATE, buildJsonObject {
            put("sessionId", "chat-a")
            put("cwd", "/home/me/a")
            put("busy", false)
            put("tabs", buildJsonArray {
                add(buildJsonObject { put("sessionId", "chat-a"); put("cwd", "/home/me/a"); put("busy", false); put("front", true) })
                add(buildJsonObject { put("sessionId", "chat-b"); put("cwd", "/home/me/b"); put("busy", true); put("front", false) })
            })
        })
        c.waitFor("the tabs") { it.plasmaLlm?.tabs?.size == 2 }
        // Protocol 12 names the tab that made the call.
        host.mcpMessage(41, screenCall(sessionId = "chat-b"))
        tabdesk.nextSent()

        tabdesk.stopOnTablet()
        if (protocol >= PlasmaLlm.CANCEL_ANY_TAB) {
            waitUntil("session/cancel for tab B") { host.requests.any { it.first == "session/cancel" } }
        }
        delay(200)
        assertFalse(c.state.value.cancelling, "this chat goes on")
        return host.requests.filter { it.first == "session/cancel" }.map { it.second.str("sessionId") }
    }

    @Test
    fun aTouchOnTheScreenAnswersTheDroppedCallAndEndsTheTurn() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", "user") })
        c.waitFor("the laptop's turn") { it.busy }
        val running = host.mcpMessage(41, screenCall())
        tabdesk.nextSent()
        val listing = host.mcpMessage(42, buildJsonObject {
            put("serverId", SERVER_ID)
            put("requestId", "mcp-request:8")
            put("method", "tools/list")
            put("sessionId", "chat-a")
        })
        tabdesk.nextSent()

        // D11: TabDesk answers nothing, so a tool call gets TabDesk's own `paused` refusal…
        val resumes = tabdesk.resumes.get()
        tabdesk.pauseOnTablet()
        val answer = withTimeout(5_000) { running.await() }.obj("result")?.obj("result")
        assertEquals(true, answer?.get("isError")?.jsonPrimitive?.booleanOrNull, "answer: $answer")
        assertEquals("paused", answer?.obj("_meta")?.str("dev.tabdesk/refused"))
        assertTrue(answer.toString().contains("The user touched the screen"))
        // …and anything else, which a tool's result doesn't fit, ends as cancelled.
        assertEquals(RpcErrorCodes.REQUEST_CANCELLED.toLong(), withTimeout(5_000) { listing.await() }.errorCode())
        assertFalse("touched" in TabletStatus.pausedRefusal("other").toString(), "another pause isn't called a touch")

        // The turn ends, as for a Stop, and the bar says why.
        waitUntil("session/cancel") { host.requests.any { it.first == "session/cancel" && it.second.str("sessionId") == "chat-a" } }
        c.waitFor("paused") { it.cancelling && it.tablet?.paused == true }

        // The user's next message lets it go on.
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "ended"); put("stopReason", "cancelled") })
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", "user") })
        waitUntil("resume()") { tabdesk.resumes.get() == resumes + 1 }
        c.waitFor("going on") { it.tablet?.paused == false }
        assertEquals(1, host.requests.count { it.first == "session/cancel" })
    }

    // --- 🛑 hold, until the user lets it go on (D20) ---

    @Test
    fun aHoldStopsTheTurnAndRefusesEveryCallUntilTheUserLetsItGoOn() = runBlocking {
        val c = controller { protocol = 7; holdPrompts = true }
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        assertTrue(c.sendPrompt("use the tablet"))
        c.waitFor("busy") { it.busy }
        host.waitForRequests("session/prompt", 1)
        val running = host.mcpMessage(41, screenCall())
        val (id, _) = tabdesk.nextSent()

        c.holdTablet()
        assertEquals(listOf(true), savedHolds, "saved at once")
        val refused = withTimeout(5_000) { running.await() }
        assertEquals(-33001L, refused.errorCode(), "the running call ends refused")
        assertTrue("on hold" in refused.obj("error")?.str("message").orEmpty(), "and the agent is told why")
        waitUntil("TabDesk's cancel") { id in tabdesk.cancelled }
        waitUntil("session/cancel") { host.requests.any { it.first == "session/cancel" } }
        assertEquals(1, tabdesk.stops.get(), "TabDesk stops too")
        c.waitFor("the bar says on hold") { it.tablet?.held == true }

        host.answerPrompt(buildJsonObject { put("stopReason", "cancelled") })
        c.waitFor("idle") { !it.busy }
        val resumes = tabdesk.resumes.get()
        // Unlike Stop, the user's next message doesn't lift it, here or from elsewhere.
        assertTrue(c.sendPrompt("go on"))
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", "user") })
        delay(100)
        assertEquals(resumes, tabdesk.resumes.get(), "no resume")
        val later = withTimeout(5_000) { host.mcpMessage(42, screenCall("mcp-request:8")).await() }
        assertEquals(-33001L, later.errorCode())
        assertEquals(listOf(id), tabdesk.sentIds, "nothing more reached TabDesk")

        c.releaseTablet()
        assertEquals(listOf(true, false), savedHolds)
        assertEquals(resumes + 1, tabdesk.resumes.get(), "TabDesk lets it go on")
        c.waitFor("the bar lets go") { it.tablet?.held == false }
        val after = host.mcpMessage(43, screenCall("mcp-request:9"))
        val (third, _) = tabdesk.nextSent()
        tabdesk.answer(third, """{"jsonrpc":"2.0","id":"mcp-request:9","result":{"content":[]}}""")
        assertNotNull(withTimeout(5_000) { after.await() }.obj("result"), "calls reach TabDesk again")
        Unit
    }

    @Test
    fun aHoldDuringATurnThatIsntUsingTheTabletLeavesTheTurnRunning() = runBlocking {
        val c = controller { protocol = 7; holdPrompts = true }
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        assertTrue(c.sendPrompt("refactor the parser"))
        c.waitFor("busy") { it.busy }
        host.waitForRequests("session/prompt", 1)

        c.holdTablet() // from the menu, as a precaution
        assertEquals(1, tabdesk.stops.get(), "TabDesk stops")
        c.waitFor("the bar says on hold") { it.tablet?.held == true }
        delay(100)
        assertTrue(host.requests.none { it.first == "session/cancel" }, "the agent's other work goes on")
        assertTrue(c.state.value.busy)
        val refused = withTimeout(5_000) { host.mcpMessage(41, screenCall()).await() }
        assertEquals(-33001L, refused.errorCode(), "but not on the tablet")
        assertTrue(tabdesk.sentIds.isEmpty())
    }

    @Test
    fun aHoldSavedBeforeARestartStillHoldsAndTheTabletStaysDeclared() = runBlocking {
        val c = controller(held = true)
        c.connect(profile)
        c.joined("chat-a")
        c.declared() // so lifting the hold works in the same chat
        c.waitFor("the bar says on hold") { it.tablet?.held == true }
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", "user") })
        delay(100)
        assertEquals(0, tabdesk.resumes.get())
        val answer = withTimeout(5_000) { host.mcpMessage(41, screenCall()).await() }
        assertEquals(-33001L, answer.errorCode())
        assertTrue(tabdesk.sentIds.isEmpty())
        assertTrue(savedHolds.isEmpty(), "nothing changed")
    }

    @Test
    fun aHoldWhileIdleStopsTabDeskButCancelsNoTurn() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        c.holdTablet()
        assertEquals(1, tabdesk.stops.get())
        c.waitFor("the bar says on hold") { it.tablet?.held == true }
        delay(100)
        assertTrue(host.requests.none { it.first == "session/cancel" })

        // Lifting a hold that isn't there does nothing.
        c.releaseTablet()
        c.releaseTablet()
        assertEquals(listOf(true, false), savedHolds)
        assertEquals(1, tabdesk.resumes.get())
    }

    // --- 🛑 by message, from any device (D21) ---

    private fun typedElsewhere(text: String, messageId: String) = buildJsonObject {
        put("sessionUpdate", "user_message_chunk")
        put("messageId", messageId)
        putJsonObject("content") { put("type", "text"); put("text", text) }
    }

    @Test
    fun whichMessagesHoldTheTablet() {
        for (text in listOf("🛑", " 🛑 stop watching", "🛑\uFE0F", "Stop the device", "stop the tablet.", "Hold the tablet!", "hold the device")) {
            assertTrue(TabletRelay.isHoldMessage(text), text)
        }
        for (text in listOf("don't stop the tablet", "a 🛑 sign", "stop", "stop the device now", "let it go on", "")) {
            assertFalse(TabletRelay.isHoldMessage(text), text)
        }
    }

    @Test
    fun aStopMessageFromThePhoneHoldsTheTabletWithoutCancellingTheTurn() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", "user") })
        host.update("chat-a", typedElsewhere("🛑", "plasmallm-9"))

        c.waitFor("the bar says on hold") { it.tablet?.held == true }
        assertEquals(listOf(true), savedHolds)
        assertEquals(1, tabdesk.stops.get(), "TabDesk stops")
        delay(100)
        assertTrue(host.requests.none { it.first == "session/cancel" }, "the message's own turn goes on")
        val refused = withTimeout(5_000) { host.mcpMessage(41, screenCall()).await() }
        assertEquals(-33001L, refused.errorCode(), "and the agent is told the tablet is on hold")
        assertTrue("🛑 message" in refused.obj("error")?.str("message").orEmpty())
    }

    @Test
    fun aStopMessageTypedHereHoldsTheTabletBeforeTheTurnCouldResumeIt() = runBlocking {
        val c = controller { protocol = 7; holdPrompts = true }
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        assertTrue(c.sendPrompt("Stop the device"))
        assertEquals(0, tabdesk.resumes.get(), "held first, so no resume")
        assertEquals(listOf(true), savedHolds)
        host.waitForRequests("session/prompt", 1) // the message still goes to the agent
        c.waitFor("on hold") { it.tablet?.held == true }
        Unit
    }

    @Test
    fun aStopMessageInTheChatsHistoryDoesntHoldTheTabletAgain() = runBlocking {
        val c = controller { protocol = 7; history["chat-a"] = listOf(typedElsewhere("🛑", "plasmallm-1")) }
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        c.waitFor("the history") { it.items.isNotEmpty() }
        delay(100)
        assertTrue(savedHolds.isEmpty(), "a replayed 🛑 is history, not a request")
        assertEquals(false, c.state.value.tablet?.held)
    }

    @Test
    fun aStopMessageOnADeviceThatDoesntOfferTheTabletDoesNothing() = runBlocking {
        val c = controller()
        c.connect(profile.copy(useTablet = false))
        c.joined("chat-a")
        host.update("chat-a", typedElsewhere("🛑", "plasmallm-9"))
        c.waitFor("the message") { it.items.isNotEmpty() }
        delay(100)
        assertTrue(savedHolds.isEmpty())
        assertEquals(0, tabdesk.stops.get())
    }

    // --- "Let the tablet go on" from a person, on any device (D23) ---

    private fun turnStarted(by: String) =
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", by) })

    @Test
    fun whichMessagesLetTheTabletGoOn() {
        for (text in listOf("Let the tablet go on", "let the device go on.", "Release the tablet!", "🟢 let the tablet go on", "release the device")) {
            assertTrue(TabletRelay.isReleaseMessage(text), text)
        }
        for (text in listOf("don't let the tablet go on", "let it go on", "go on", "release", "🟢", "let the tablet go on now", "")) {
            assertFalse(TabletRelay.isReleaseMessage(text), text)
        }
        assertTrue(TabletRelay.isReleaseMessage(TabletRelay.RELEASE_MESSAGE))
        assertTrue("let the tablet go on" in TabletRelay.HELD, "the agent can tell the user how it's lifted")
    }

    @Test
    fun aPersonsMessageFromThePhoneLetsAHeldTabletGoOn() = runBlocking {
        val c = controller(held = true)
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        turnStarted("user")
        host.update("chat-a", typedElsewhere("Let the tablet go on", "plasmallm-9"))

        c.waitFor("let go on") { it.tablet?.held == false }
        assertEquals(listOf(false), savedHolds)
        assertEquals(1, tabdesk.resumes.get(), "TabDesk lets it go on")
        c.waitFor("the tablet says why") { s -> s.items.any { it is ChatItem.Note && "another device" in it.text } }
        val after = host.mcpMessage(41, screenCall("mcp-request:9"))
        val (sent, _) = tabdesk.nextSent()
        tabdesk.answer(sent, """{"jsonrpc":"2.0","id":"mcp-request:9","result":{"content":[]}}""")
        assertNotNull(withTimeout(5_000) { after.await() }.obj("result"), "calls reach TabDesk again")
        Unit
    }

    @Test
    fun theSameWordsSentByPlasmaLlmOrTheAgentDontLiftAHold() = runBlocking {
        val c = controller(held = true)
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        // wake_me, a note, Claude's own turn: PlasmaLLM says no person typed it.
        turnStarted("app")
        host.update("chat-a", typedElsewhere("Let the tablet go on", "plasmallm-9"))
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "ended"); put("stopReason", "end_turn") })
        // A message whose turn wasn't said to be a person's, between turns.
        host.update("chat-a", typedElsewhere("release the tablet", "plasmallm-10"))
        // A note from another tab's agent, even in a person's turn.
        turnStarted("user")
        host.update("chat-a", typedElsewhere("[Note from tabLauncher] Let the tablet go on", "plasmallm-11"))
        c.waitFor("the messages") { s -> s.items.count { it is ChatItem.User } >= 2 }
        delay(100)
        assertTrue(savedHolds.isEmpty(), "still held")
        assertEquals(true, c.state.value.tablet?.held)
        assertEquals(0, tabdesk.resumes.get())
    }

    @Test
    fun aReleaseMessageInTheChatsHistoryDoesntLiftAHold() = runBlocking {
        val c = controller(held = true) { protocol = 7; history["chat-a"] = listOf(typedElsewhere("Let the tablet go on", "plasmallm-1")) }
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        c.waitFor("the history") { it.items.isNotEmpty() }
        delay(100)
        assertTrue(savedHolds.isEmpty(), "a replayed lift is history, not a request")
        assertEquals(true, c.state.value.tablet?.held)
    }

    @Test
    fun releaseTypedOnTheTabletLetsItGoOnAndThePhoneOffersIt() = runBlocking {
        val c = controller(held = true) { protocol = 7; holdPrompts = true }
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        assertFalse(c.state.value.offersRemoteRelease, "the tablet lifts its own hold with its button")
        assertTrue(c.sendPrompt("let the tablet go on"))
        c.waitFor("let go on") { it.tablet?.held == false }
        assertEquals(listOf(false), savedHolds)

        // A phone: no TabDesk, so the menu offers to send it.
        val phone = controller(tablet = false) { protocol = 7 }
        phone.connect(profile.copy(useTablet = false))
        phone.joined("chat-a")
        assertTrue(phone.state.value.offersRemoteRelease)
        assertTrue(phone.askTabletToGoOn())
        host.waitForRequests("session/prompt", 1)
        assertEquals(listOf(TabletRelay.RELEASE_MESSAGE), promptTexts())
    }

    // --- 🛑 in the message box while the agent works (D22) ---

    /** The text of each message sent to PlasmaLLM, in order. */
    private fun promptTexts() = host.requests.filter { it.first == "session/prompt" }.map { (_, params) ->
        params["prompt"]!!.jsonArray.joinToString("") { it.jsonObject.str("text").orEmpty() }
    }

    @Test
    fun haltOnTheTabletHoldsAtOnceStopsTheTurnThenSendsAStopMessage() = runBlocking {
        val c = controller { protocol = 7; holdPrompts = true }
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        assertFalse(c.state.value.canHalt, "not while idle")
        assertTrue(c.sendPrompt("look at the kittens"))
        host.waitForRequests("session/prompt", 1)
        c.waitFor("🛑 offered") { it.canHalt }
        val resumes = tabdesk.resumes.get() // the first message lets TabDesk go on

        c.halt()
        assertEquals(listOf(true), savedHolds, "held here at once")
        waitUntil("session/cancel") { host.requests.any { it.first == "session/cancel" } }
        c.waitFor("stopping, 🛑 not offered twice") { it.halting && !it.canHalt && it.tablet?.held == true }
        val refused = withTimeout(5_000) { host.mcpMessage(41, screenCall()).await() }
        assertEquals(-33001L, refused.errorCode(), "the turn's calls are refused meanwhile")

        host.answerPrompt(buildJsonObject { put("stopReason", "cancelled") })
        host.waitForRequests("session/prompt", 2)
        assertEquals(listOf("look at the kittens", "🛑"), promptTexts())
        c.waitFor("done") { !it.halting }
        assertEquals(listOf(true), savedHolds, "the 🛑 message finds it held already")
        assertEquals(resumes, tabdesk.resumes.get(), "and doesn't let it go on")
    }

    @Test
    fun haltOnAPhoneWithoutTheTabletStopsTheTurnThenSendsAStopMessage() = runBlocking {
        val c = controller(tablet = false) { protocol = 7; holdPrompts = true }
        c.connect(profile.copy(useTablet = false))
        c.joined("chat-a")
        assertTrue(c.sendPrompt("look at the kittens"))
        host.waitForRequests("session/prompt", 1)
        c.waitFor("🛑 offered without TabDesk") { it.canHalt }

        c.halt()
        waitUntil("session/cancel") { host.requests.any { it.first == "session/cancel" } }
        delay(100)
        assertEquals(1, promptTexts().size, "nothing more while the turn is running")
        host.answerPrompt(buildJsonObject { put("stopReason", "cancelled") })
        host.waitForRequests("session/prompt", 2)
        assertEquals("🛑", promptTexts().last(), "the tablet holds on this (D21)")
        assertTrue(savedHolds.isEmpty())
    }

    @Test
    fun haltDuringATurnStartedOnTheComputerSendsTheStopMessageWhenItEnds() = runBlocking {
        val c = controller(tablet = false)
        c.connect(profile.copy(useTablet = false))
        c.joined("chat-a")
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", "user") })
        c.waitFor("the laptop's turn") { it.canHalt }

        c.halt()
        waitUntil("session/cancel") { host.requests.any { it.first == "session/cancel" && it.second.str("sessionId") == "chat-a" } }
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "ended"); put("stopReason", "cancelled") })
        host.waitForRequests("session/prompt", 1)
        assertEquals(listOf("🛑"), promptTexts())
    }

    @Test
    fun haltIsntOfferedWhenTheComputerDoesntLetTheAgentUseATablet() = runBlocking {
        val c = controller(tablet = false) { protocol = 7; holdPrompts = true; tabletTools = false }
        c.connect(profile.copy(useTablet = false))
        c.joined("chat-a")
        assertTrue(c.sendPrompt("refactor the parser"))
        c.waitFor("busy") { it.busy }
        assertFalse(c.state.value.canHalt)
        c.halt()
        delay(100)
        assertTrue(host.requests.none { it.first == "session/cancel" }, "nothing happens")
    }

    // --- approval on another device (D8) ---

    @Test
    fun anApprovalAskedElsewhereIsShownUntilResolved() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        host.notify(PlasmaLlm.PERMISSION_ELSEWHERE, buildJsonObject { put("sessionId", "chat-a"); put("toolCallId", "tool-1"); put("reason", "driven_device") })
        c.waitFor("waiting elsewhere") { "tool-1" in it.approvalElsewhere }
        host.notify(PlasmaLlm.PERMISSION_RESOLVED, buildJsonObject { put("sessionId", "chat-a"); put("toolCallId", "tool-1") })
        c.waitFor("resolved") { it.approvalElsewhere.isEmpty() }
        assertTrue(c.state.value.permissions.isEmpty(), "never asked here")
    }

    // --- PlasmaLLM's progress for a file copied (P11, D17) ---

    @Test
    fun progressIsKeptForTheShownChatAndEndsWithTheTurn() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "started"); put("by", "user") })
        c.waitFor("the laptop's turn") { it.busy }
        fun progress(chat: String, call: String, text: String) =
            host.notify(PlasmaLlm.TOOL_PROGRESS, buildJsonObject { put("sessionId", chat); put("toolCallId", call); put("text", text) })
        progress("chat-b", "tool-9", "Another tab's copy")
        progress("chat-a", "tool-1", "Copying report.pdf to the tablet: 1 of 8 MB")
        progress("chat-a", "tool-1", "Copying report.pdf to the tablet: 2 of 8 MB")
        val s = c.waitFor("the newest line") { it.toolProgress["tool-1"] == "Copying report.pdf to the tablet: 2 of 8 MB" }
        assertFalse("tool-9" in s.toolProgress, "only the chat shown here")
        host.notify(PlasmaLlm.TURN, buildJsonObject { put("sessionId", "chat-a"); put("state", "ended"); put("stopReason", "end_turn") })
        c.waitFor("gone with the turn") { !it.busy && it.toolProgress.isEmpty() }
        Unit
    }

    @Test
    fun theTabletShowsAnotherTabsCopyUntilItsProgressStops() = runBlocking {
        val c = controller(progressTimeout = 300L)
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        // A back tab copies a file: PlasmaLLM sends its progress to the tablet too (protocol 14).
        host.notify(PlasmaLlm.TOOL_PROGRESS, buildJsonObject { put("sessionId", "chat-b"); put("toolCallId", "tool-9"); put("text", "Copying scan.dat to the tablet: 3.1 MB of 7.3 MB") })
        val s = c.waitFor("another tab's line") { it.tabletProgress == "Copying scan.dat to the tablet: 3.1 MB of 7.3 MB" }
        assertTrue(s.toolProgress.isEmpty(), "not this chat's card")
        c.waitFor("gone once it stops coming") { it.tabletProgress == null }
        Unit
    }

    // --- staying connected for the relay (D14) ---

    @Test
    fun aDroppedConnectionIsMadeAgainByItselfAndTheTabletDeclaredAgain() = runBlocking {
        val c = controller(reconnect = listOf(50L))
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        host.close()
        waitUntil("a second connection") { hosts.size == 2 }
        c.joined("chat-a")
        val s = c.declared()
        assertFalse(s.reconnecting)
        assertEquals(SERVER_ID, declaredServers().single().second?.str("serverId"), "declared on the new connection")
    }

    @Test
    fun aLaptopThatIsntThereIsTriedAgainWithAGrowingWait() = runBlocking {
        val c = controller(reconnect = listOf(50L, 150L)) { protocol = 7; refuse = hosts.size < 2 } // counted before this one joins
        val started = System.nanoTime()
        c.connect(profile)
        c.waitFor("waiting to try again") { it.reconnecting }
        c.joined("chat-a")
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(3, hosts.size)
        assertTrue(tookMs >= 200, "waited 50 then 150 ms, took $tookMs")
        assertFalse(c.state.value.reconnecting)
    }

    @Test
    fun noReconnectWithoutTheSettingOrAfterDisconnect() = runBlocking {
        val c = controller(reconnect = listOf(50L))
        c.connect(profile.copy(useTablet = false))
        c.joined("chat-a")
        host.close()
        delay(300)
        assertEquals(1, hosts.size, "the setting is off")

        c.connect(profile)
        c.joined("chat-a")
        host.close()
        c.disconnect()
        delay(300)
        assertEquals(2, hosts.size, "the user disconnected")
        assertFalse(c.state.value.reconnecting)
    }

    @Test
    fun disconnectingWhileAReconnectWaitsLetsGoOfTabDesk() = runBlocking {
        val c = controller(reconnect = listOf(60_000L))
        c.connect(profile)
        c.joined("chat-a")
        host.close()
        c.waitFor("waiting to try again") { it.reconnecting }
        assertEquals(true, tabdesk.wanted.last(), "still bound while it waits")
        c.disconnect()
        assertEquals(false, tabdesk.wanted.last())
        assertNull(c.state.value.tablet)
        assertFalse(c.state.value.reconnecting)
        assertEquals(ConnectionStatus.Disconnected, c.state.value.status, "ended, not failed")
    }

    @Test
    fun turningTheSettingOnWhileNotConnectedWaitsForTheNextConnection() = runBlocking {
        val c = controller()
        c.connect(profile.copy(useTablet = false))
        c.joined("chat-a")
        c.disconnect()
        val before = tabdesk.wanted.size
        c.profileSaved(profile)
        assertTrue(tabdesk.wanted.drop(before).none { it }, "not bound with nothing connected")
        assertNull(c.state.value.tablet)
        assertTrue(c.state.value.profile?.useTablet == true)
    }

    @Test
    fun anEditSavedWhileAReconnectWaitsIsUsedByIt() = runBlocking {
        val urls = CopyOnWriteArrayList<String>()
        val c = AgentController(scope, "test", tablet = Tablet(tabdesk, SERVER_ID), reconnectDelaysMs = listOf(100L)) { p, listener ->
            urls += p.url
            FakePlasmaLlm(listener).apply { protocol = 7 }.also { hosts += it }
        }
        c.connect(profile)
        c.joined("chat-a")
        host.close()
        c.waitFor("waiting to try again") { it.reconnecting }
        c.profileSaved(profile.copy(url = "new-laptop"))
        waitUntil("a second connection") { hosts.size == 2 }
        assertEquals(listOf("my-laptop", "new-laptop"), urls.toList())
    }

    @Test
    fun aConnectionDroppedBeforeAChatOpensWaitsLongerEachTime() = runBlocking {
        val c = controller(reconnect = listOf(50L, 400L)) { protocol = 7; closeOnLoad = hosts.size < 2 } // counted before this one joins
        val started = System.nanoTime()
        c.connect(profile)
        waitUntil("a third connection") { hosts.size == 3 }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs >= 450, "initialize answered doesn't reset the wait: 50 then 400 ms, took $tookMs")
        c.joined("chat-a")
        Unit
    }

    @Test
    fun anEditSavedWhileConnectedIsUsedByTheReconnect() = runBlocking {
        val urls = CopyOnWriteArrayList<String>()
        val c = AgentController(scope, "test", tablet = Tablet(tabdesk, SERVER_ID), reconnectDelaysMs = listOf(50L)) { p, listener ->
            urls += p.url
            FakePlasmaLlm(listener).apply { protocol = 7 }.also { hosts += it }
        }
        c.connect(profile)
        c.joined("chat-a")
        c.profileSaved(profile.copy(url = "new-laptop"))
        assertEquals("my-laptop", c.state.value.profile?.url, "the connection open now keeps its address")
        host.close()
        waitUntil("a second connection") { hosts.size == 2 }
        assertEquals(listOf("my-laptop", "new-laptop"), urls.toList())
    }

    @Test
    fun aReconnectRefusedForGoodStopsTryingAndSaysSo() = runBlocking {
        val c = controller(reconnect = listOf(50L)) { protocol = 7; refuseForGood = hosts.size >= 1 } // counted before this one joins
        val events = CopyOnWriteArrayList<com.droidacp.core.chat.AgentEvent>()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { c.events.collect { events += it } }
        c.connect(profile)
        c.joined("chat-a")
        host.close()
        waitUntil("the reconnect refused") { hosts.size == 2 && c.state.value.status is com.droidacp.core.chat.ConnectionStatus.Failed && !c.state.value.reconnecting }
        delay(300)
        assertEquals(2, hosts.size, "no more tries after a refusal")
        assertEquals(1, events.count { it is com.droidacp.core.chat.AgentEvent.ConnectionLost }, "one alert, when it gave up: $events")
    }

    @Test
    fun aDropItRecoversFromByItselfRaisesNoAlert() = runBlocking {
        val c = controller(reconnect = listOf(50L))
        val events = CopyOnWriteArrayList<com.droidacp.core.chat.AgentEvent>()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { c.events.collect { events += it } }
        c.connect(profile)
        c.joined("chat-a")
        host.close()
        waitUntil("a second connection") { hosts.size == 2 }
        c.joined("chat-a")
        assertTrue(events.none { it is com.droidacp.core.chat.AgentEvent.ConnectionLost }, "events: $events")
    }

    @Test
    fun anAnswerTooBigToPassOnIsRefusedWith33002() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        val answer = host.mcpMessage(41, screenCall())
        val (id, _) = tabdesk.nextSent()
        val big = "x".repeat(com.droidacp.core.tablet.TabletRelay.MAX_ANSWER_CHARS)
        tabdesk.answer(id, buildJsonObject { put("jsonrpc", "2.0"); put("id", "mcp-request:7"); putJsonObject("result") { put("text", big) } }.toString())
        val reply = withTimeout(5_000) { answer.await() }
        assertEquals(PlasmaLlm.TABLET_FAILED.toLong(), reply.errorCode())
        c.waitFor("no calls running") { it.tablet?.calls == 0 }
        Unit
    }

    @Test
    fun theRunningCountEndsAtZeroWhenManyCallsEndTogether() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        val n = 40
        val answers = (1..n).map { host.mcpMessage(100L + it, screenCall(requestId = "mcp-request:$it")) }
        val ids = (1..n).map { tabdesk.nextSent().first }
        waitUntil("all counted") { c.state.value.tablet?.calls == n }
        ids.map { id ->
            scope.launch(Dispatchers.Default) {
                tabdesk.answer(id, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); putJsonObject("result") {} }.toString())
            }
        }.forEach { it.join() }
        answers.forEach { withTimeout(5_000) { it.await() } }
        c.waitFor("no calls running") { it.tablet?.calls == 0 }
        delay(100)
        assertEquals(0, c.state.value.tablet?.calls)
    }

    @Test
    fun tabDesksRefusalIsNamedTheSameWhereverItShows() {
        fun s(approval: String?, control: String?, reason: String?, enabled: Boolean = true) =
            TabletStatus(TabletStatus.Reach.Connected, enabled = enabled, approval = approval, control = control, reason = reason)
        assertNull(s("approved", "idle", "").refusal)
        assertNull(TabletStatus(TabletStatus.Reach.Lost).refusal, "not connected: no refusal, unavailable says why")
        assertEquals(TabletStatus.Refusal.SwitchedOff, s("approved", "revoked", "switched_off", enabled = false).refusal)
        assertEquals(TabletStatus.Refusal.Lapsed, s("lapsed", "revoked", "lapsed").refusal)
        assertEquals(TabletStatus.Refusal.Lapsed, s("approved", "revoked", "lapsed").refusal, "the control state's reason counts too")
        assertEquals(TabletStatus.Refusal.CertificateChanged, s("certificate_changed", "revoked", "certificate_changed").refusal)
        assertEquals(TabletStatus.Refusal.Revoked, s("none", "revoked", "revoked").refusal)
        assertEquals(TabletStatus.Refusal.NotApproved, s("asked", "revoked", "not_approved").refusal)
    }

    @Test
    fun theBarsStopReachesTabDeskWithoutATurnRunningHere() = runBlocking {
        val c = controller()
        c.connect(profile)
        c.joined("chat-a")
        c.declared()
        c.stopTablet()
        assertEquals(1, tabdesk.stops.get())
        assertTrue(host.requests.none { it.first == "session/cancel" })
    }

    companion object {
        const val SERVER_ID = "droidacp:4f1c-test"
    }
}

/** TabDesk's agent service as DroidACP sees it, in memory. */
class FakeTabDesk : TabletLink {
    override val status = MutableStateFlow(TabletStatus())

    /** Each setWanted, in order. */
    val wanted = CopyOnWriteArrayList<Boolean>()
    val sentIds = CopyOnWriteArrayList<String>()
    private val sent = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()
    val cancelled = CopyOnWriteArrayList<String>()
    val stops = AtomicInteger()
    val resumes = AtomicInteger()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Connecting finds DroidACP approved; otherwise it has asked, and [approve] comes later. */
    @Volatile
    var approveOnConnect = true

    override fun setWanted(wanted: Boolean) {
        this.wanted += wanted
        status.value = when {
            !wanted -> TabletStatus()
            approveOnConnect -> connected("approved", TabletStatus.IDLE, "")
            else -> connected("asked", TabletStatus.REVOKED, "not_approved")
        }
    }

    private fun connected(approval: String, control: String, reason: String) =
        TabletStatus(TabletStatus.Reach.Connected, enabled = true, approval = approval, helper = "running", name = "Lenovo Tab One", control = control, reason = reason)

    fun approve() {
        status.value = connected("approved", TabletStatus.IDLE, "approved")
    }

    override suspend fun send(id: String, message: String): String {
        if (!status.value.connected) throw RpcException(PlasmaLlm.TABLET_UNAVAILABLE, "TabDesk isn't connected")
        val answer = CompletableDeferred<String>()
        waiting[id] = answer
        sentIds += id
        // Active before the test can see the call, so a Stop it presses next isn't overwritten.
        status.value = status.value.copy(control = TabletStatus.ACTIVE, reason = "")
        sent.put(id to message)
        try {
            return answer.await()
        } catch (e: CancellationException) {
            cancelled += id
            throw e
        } finally {
            waiting.remove(id)
        }
    }

    /** The next message DroidACP sent: its Binder id and JSON. */
    fun nextSent(): Pair<String, String> = sent.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("nothing was sent to TabDesk")

    fun answer(id: String, json: String) {
        waiting[id]?.complete(json) ?: throw AssertionError("no call $id running")
    }

    /** TabDesk's process dies: running calls fail on the way. */
    fun die() {
        status.value = status.value.copy(reach = TabletStatus.Reach.Lost)
        waiting.values.forEach { it.completeExceptionally(RpcException(PlasmaLlm.TABLET_FAILED, "TabDesk stopped")) }
    }

    override fun stop() {
        stops.incrementAndGet()
        waiting.values.forEach { it.cancel() }
        status.value = status.value.copy(control = TabletStatus.STOPPED, reason = "caller")
    }

    /** Stop pressed on TabDesk's indicator. */
    fun stopOnTablet() {
        waiting.values.forEach { it.cancel() }
        status.value = status.value.copy(control = TabletStatus.STOPPED, reason = TabletStatus.BY_TABDESK)
    }

    /** The user touched the screen (tool list 2): TabDesk drops the running calls, which TabDeskLink fails. */
    fun pauseOnTablet() {
        waiting.values.forEach { it.completeExceptionally(PausedOnTablet(TabletStatus.TOUCHED)) }
        status.value = status.value.copy(control = TabletStatus.PAUSED, reason = TabletStatus.TOUCHED)
    }

    override fun resume() {
        resumes.incrementAndGet()
        val control = status.value.control
        if (control == TabletStatus.STOPPED || control == TabletStatus.PAUSED) {
            status.value = status.value.copy(control = TabletStatus.IDLE, reason = "resumed")
        }
    }
}
