package com.droidacp.core

import com.droidacp.core.acp.Implementation
import com.droidacp.core.acp.PlasmaChat
import com.droidacp.core.acp.PlasmaHello
import com.droidacp.core.acp.PlasmaPerson
import com.droidacp.core.acp.PlasmaTab
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.people.Activity
import com.droidacp.core.people.Look
import com.droidacp.core.people.PixelPeople
import com.droidacp.core.people.activity
import com.droidacp.core.people.stripPeople
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PixelPeopleTest {
    private val people = PixelPeople.default

    @Test
    fun everyPoseAndOptionDraws() {
        assertTrue(people.poses.size >= 15, "poses: ${people.poses}")
        for (pose in people.poses) {
            val s = people.render(pose, Look())
            assertEquals(PixelPeople.SIZE * PixelPeople.SIZE, s.pixels.size)
            assertTrue(s.pixels.any { it != 0 }, "$pose draws something")
        }
        // Every option changes the picture, so none is missing its pixels.
        val base = people.render("talk", Look()).pixels
        for (h in people.hairStyles.filter { it != "short" }) {
            assertTrue(!people.render("talk", Look(hair = h)).pixels.contentEquals(base), "hair $h")
        }
        for (a in people.accessories) {
            assertTrue(!people.render("talk", Look(accessories = listOf(a))).pixels.contentEquals(base), "accessory $a")
        }
        for (c in people.hairColors.filter { it != "brown" }) {
            assertTrue(!people.render("talk", Look(hairColor = c)).pixels.contentEquals(base), "hair colour $c")
        }
        for (s in people.skins.filter { it != "light" }) {
            assertTrue(!people.render("talk", Look(skin = s)).pixels.contentEquals(base), "skin $s")
        }
        assertTrue(people.shirts.size >= 12, "shirts: ${people.shirts}")
        for (s in people.shirts) {
            assertTrue(!people.render("talk", Look(shirt = s)).pixels.contentEquals(base), "shirt $s")
        }
        for (ch in ('A'..'Z') + ('0'..'9')) assertTrue(ch in people.letters, "letter $ch")
        assertTrue(!people.render("talk", Look(letter = 'D')).pixels.contentEquals(base), "the letter shows")
    }

    @Test
    fun eachAgentHasItsColours() {
        assertEquals("claude", people.agentFor("@agentclientprotocol/claude-agent-acp"))
        assertEquals("gemini", people.agentFor("gemini-cli"))
        assertEquals("codex", people.agentFor("codex-acp"))
        assertEquals("mistral", people.agentFor("mistral-vibe"))
        assertEquals("local", people.agentFor("ollama"))
        assertEquals("unknown", people.agentFor("fake-plasmallm"))
        assertEquals("unknown", people.agentFor(null))
        // Until it chooses a shirt, an agent wears its colours; a chosen one replaces them.
        val claude = people.render("talk", Look(agent = "claude")).pixels
        assertTrue(!people.render("talk", Look(agent = "gemini")).pixels.contentEquals(claude))
        val navy = people.render("talk", Look(agent = "claude", shirt = "navy")).pixels
        assertTrue(!navy.contentEquals(claude))
        assertTrue(navy.contentEquals(people.render("talk", Look(agent = "gemini", shirt = "navy")).pixels), "the shirt doesn't show the agent")
        assertTrue(claude.contentEquals(people.render("talk", Look(agent = "claude", shirt = "plaid")).pixels), "an unknown shirt is the agent's")

        // The bar always shows the agent: its colours side by side, outlined.
        for (agent in people.agents) {
            val bar = people.bar(agent)
            assertEquals(PixelPeople.BAR_WIDTH * PixelPeople.BAR_HEIGHT, bar.pixels.size)
            assertEquals(0, bar.pixels[0], "$agent: corners left out")
        }
        val gemini = people.bar("gemini").pixels
        val top = (1 until PixelPeople.BAR_WIDTH - 1).map { gemini[PixelPeople.BAR_WIDTH + it] }.distinct()
        assertEquals(listOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt()), top)
        assertTrue(people.bar("nobody").pixels.contentEquals(people.bar("unknown").pixels))
    }

    @Test
    fun aDefaultLookStaysTheSameForAFolder() {
        val a = people.defaultLook("/home/me/DroidACP", "claude", 'D')
        assertEquals(a, people.defaultLook("/home/me/DroidACP", "claude", 'D'))
        assertEquals('D', a.letter)
        val others = listOf("/home/me/plasmaLLM", "/home/me/tabLauncher", "/home/me/kittens").map { people.defaultLook(it, "claude", 'X') }
        assertTrue(others.any { it.hair != a.hair || it.skin != a.skin || it.hairColor != a.hairColor }, "folders look different")
    }

    @Test
    fun thePoseFollowsWhatTheAgentDoes() {
        val idle = ChatState(status = ConnectionStatus.Connected, sessionId = "chat-a")
        assertEquals("idle", idle.activity)
        assertEquals("sleep", Activity.poseFor(idle.activity))
        fun tool(title: String, kind: String, status: String = "in_progress") =
            idle.copy(busy = true, items = listOf(ChatItem.Tool(1, ToolCall("t", title, kind, status))))
        assertEquals("book", Activity.poseFor(tool("Read Main.kt", "read").activity))
        assertEquals("write", Activity.poseFor(tool("Edit Main.kt", "edit").activity))
        assertEquals("laptop", Activity.poseFor(tool("gradle test", "execute").activity))
        assertEquals("search", Activity.poseFor(tool("grep foo", "search").activity))
        assertEquals("tablet", Activity.poseFor(tool("mcp__tablet__screen", "other").activity))
        assertEquals("plane", Activity.poseFor(tool("mcp__plasmallm__note_send", "other").activity))
        assertEquals("think", Activity.poseFor(tool("Read Main.kt", "read", status = "completed").activity))
        assertEquals("talk", Activity.poseFor(idle.copy(busy = true, items = listOf(ChatItem.Agent(1, null, "hi"))).activity))
        assertEquals("stop", Activity.poseFor(idle.copy(busy = true, cancelling = true).activity))
        assertEquals("empty", Activity.poseFor(ChatState().activity))
        assertEquals("laptop", Activity.poseFor("something new"))
        // A word from a newer PlasmaLLM: a tool while working, idle otherwise.
        assertEquals("tool", Activity.known("juggling", busy = true))
        assertEquals("idle", Activity.known("juggling", busy = false))
        assertEquals("read", Activity.known("read", busy = true))
    }

    @Test
    fun theStripHasOnePersonPerTab() {
        val one = listOf(PlasmaTab("chat-a", "/home/me/DroidACP", "A", busy = true, front = true))
        val state = ChatState(
            status = ConnectionStatus.Connected,
            sessionId = "chat-a",
            busy = true,
            agent = Implementation("@agentclientprotocol/claude-agent-acp", "Claude Agent", "1"),
            plasmaLlm = PlasmaHello(8, null, PlasmaChat("chat-a", "/home/me/DroidACP", "A", true), tabs = one),
        )
        assertEquals(emptyList(), state.stripPeople(), "no strip for one tab")

        val tabs = one + listOf(
            PlasmaTab("chat-b", "/home/me/plasmaLLM", "B", busy = true, front = false),
            PlasmaTab("chat-c", "/home/me/tabLauncher", "C", busy = false, front = false),
            PlasmaTab("", "/home/me/new", null, busy = false, front = false),
            PlasmaTab("chat-g", "/home/me/g", "G", busy = true, front = false, agentName = "gemini-cli", activity = "read", detail = "Read Main.kt"),
        )
        val strip = state.copy(plasmaLlm = state.plasmaLlm!!.copy(tabs = tabs)).stripPeople()
        assertEquals(listOf("DroidACP", "plasmaLLM", "tabLauncher", "g"), strip.map { it.name })
        assertEquals(listOf(true, false, false, false), strip.map { it.front })
        assertEquals(listOf("thinking", "working", "idle", "read"), strip.map { it.activity })
        assertEquals(listOf("claude", "claude", "claude", "gemini"), strip.map { it.look.agent })
        assertEquals(listOf(null, null, null, null), strip.map { it.look.shirt })
        assertEquals("plasmaLLM (Claude): working", strip[1].description)
        assertEquals('T', strip[2].look.letter)
        assertEquals("Read Main.kt", strip[3].detail)

        // A chosen look (protocol 10) replaces the default; unknown ids keep the default's part.
        val chosen = tabs.map {
            when (it.sessionId) {
                "chat-c" -> it.copy(person = PlasmaPerson("medium", "swoop", "teal", listOf("glasses", "headphones"), 'T', "purple"))
                "chat-b" -> it.copy(person = PlasmaPerson("green-ish", "afro", null, listOf("cape", "scarf", "bow", "crown"), null, "plaid"))
                else -> it
            }
        }
        val looks = state.copy(plasmaLlm = state.plasmaLlm.copy(tabs = chosen)).stripPeople()
        assertEquals(Look("medium", "swoop", "teal", listOf("glasses", "headphones"), 'T', shirt = "purple", agent = "claude"), looks[2].look)
        val b = looks[1].look
        val bDefault = strip[1].look
        assertEquals(listOf(bDefault.skin, bDefault.hair, bDefault.hairColor), listOf(b.skin, b.hair, b.hairColor))
        assertEquals(listOf("scarf", "bow"), b.accessories, "known ones only, at most two")
        assertEquals(null, b.letter, "no letter chosen")
        assertEquals(null, b.shirt, "an unknown shirt is the agent's colours")
        assertEquals("claude", b.agent)
        assertNotEquals(strip[0].look, strip[1].look)
    }
}
