package com.droidacp.core

import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.chat.AgentController
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.core.people.stripPeople
import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The people strip's break room (PlasmaLLM's companion protocol 19). */
class BreakRoomTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hosts = CopyOnWriteArrayList<FakeTabbedPlasma>()
    private val host: FakeTabbedPlasma get() = hosts.last()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        hosts.forEach { it.shutdown() }
    }

    private val laptop = Profile(name = "laptop", kind = ProfileKind.PlasmaLlm, url = "my-laptop", cwd = "")

    /** Connected to a PlasmaLLM speaking [protocol], with a second tab "t2" (Codex's chat in /home/me/other). */
    private suspend fun connected(protocol: Int = 19): AgentController {
        val c = AgentController(scope, "test") { _, listener ->
            FakeTabbedPlasma(listener).apply {
                this.protocol = protocol
                addTab("t2", "codex-acp", "codex-1", busy = true)
            }.also { hosts += it }
        }
        c.connect(laptop)
        c.waitFor("the computer's chat") { it.sessionId == "chat-a" && !it.startingSession }
        return c
    }

    private suspend fun AgentController.waitFor(what: String, check: (ChatState) -> Boolean): ChatState =
        try {
            withTimeout(5_000) { state.first(check) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; tabs: ${state.value.plasmaLlm?.tabs}", e)
        }

    private fun ChatState.onBreak() = stripPeople().filter { it.onBreak }.map { it.tabId }

    @Test
    fun aPersonGoesOnABreakAndComesBack() = runBlocking<Unit> {
        val c = connected()
        assertTrue(c.state.value.offersBreak)
        c.waitFor("both tabs") { it.stripPeople().size == 2 }
        assertTrue(c.state.value.onBreak().isEmpty())

        // Sent from the phone: asked of PlasmaLLM, and shown once its new state says so.
        assertNull(c.setBreak("t2", onBreak = true))
        val asked = host.requests.last { it.first == PlasmaLlm.BREAK_METHOD }.second
        assertEquals("t2", asked.str("tabId"))
        assertEquals(true, asked.bool("onBreak"))
        val away = c.waitFor("t2 in the room") { it.onBreak() == listOf("t2") }
        val person = away.stripPeople().single { it.tabId == "t2" }
        assertTrue(person.busy, "its agent goes on working in the room")

        // PlasmaLLM brings it back by itself (it asked for approval, say): the phone follows.
        host.breakChanged("t2", onBreak = false)
        c.waitFor("t2 back on the strip") { it.onBreak().isEmpty() }

        // This chat's own person can go too.
        assertNull(c.setBreak("t1", onBreak = true))
        val own = c.waitFor("this chat in the room") { it.onBreak() == listOf("t1") }
        assertTrue(own.stripPeople().single { it.tabId == "t1" }.front)
        assertNull(c.setBreak("t1", onBreak = false))
        c.waitFor("this chat back") { it.onBreak().isEmpty() }
    }

    @Test
    fun aTabThatsGoneSaysSoAndOlderPlasmaLlmIsntAsked() = runBlocking<Unit> {
        val c = connected()
        assertEquals("That tab isn't open any more.", c.setBreak("t9", onBreak = true))

        val old = connected(protocol = 18)
        assertFalse(old.state.value.offersBreak)
        val why = assertNotNull(old.setBreak("t2", onBreak = true))
        assertTrue("Update PlasmaLLM" in why, why)
        assertTrue(host.requests.none { it.first == PlasmaLlm.BREAK_METHOD }, "nothing asked of PlasmaLLM 18")
        // And an onBreak an older PlasmaLLM couldn't mean is never shown.
        assertTrue(old.state.value.onBreak().isEmpty())
    }
}
