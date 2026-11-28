package com.droidacp.core

import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.acp.TodoList
import com.droidacp.core.acp.TodoRow
import com.droidacp.core.chat.AgentController
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A project folder's to-do list from PlasmaLLM (companion protocol 17), read only. */
class TodoListTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hosts = CopyOnWriteArrayList<FakeTabbedPlasma>()
    private val host: FakeTabbedPlasma get() = hosts.last()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        hosts.forEach { it.shutdown() }
    }

    private val laptop = Profile(name = "laptop", kind = ProfileKind.PlasmaLlm, url = "my-laptop", cwd = "")

    private suspend fun connected(protocol: Int = 17, configure: FakeTabbedPlasma.() -> Unit = {}): AgentController {
        val c = AgentController(scope, "test") { _, listener ->
            FakeTabbedPlasma(listener).apply { this.protocol = protocol }.apply(configure).also { hosts += it }
        }
        c.connect(laptop)
        c.waitFor("the computer's chat") { it.sessionId == "chat-a" && !it.startingSession }
        return c
    }

    private suspend fun AgentController.waitFor(what: String, check: (ChatState) -> Boolean): ChatState =
        try {
            withTimeout(5_000) { state.first(check) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state: ${state.value.todo}", e)
        }

    private fun todoRequests() = host.requests.filter { it.first == PlasmaLlm.TODO_METHOD }.map { it.second }

    /** A list as PlasmaLLM answers it: [open] and [done] items under "Chat UI", one done under "Done". */
    private fun answer(open: List<String>, done: List<String> = emptyList()): JsonObject {
        var line = 3
        val items = (open.map { false to it } + done.map { true to it }).joinToString(",") { (d, t) ->
            """{"done": $d, "text": ${JsonPrimitive(t)}, "section": "Chat UI", "line": ${line++}}"""
        }
        return Json.parseToJsonElement(
            """{"cwd": "/home/me/project", "path": "/home/me/project/TASKLIST.md", "exists": true,
                "modified": "2026-11-27T20:41:05-05:00", "markdown": "# To do\n",
                "rows": [{"heading": true, "level": 2, "text": "Chat UI"}, $items,
                         {"heading": true, "level": 2, "text": "Done"},
                         {"done": true, "text": "An old one", "section": "Done", "line": 40}]}""",
        ) as JsonObject
    }

    @Test
    fun theRowsAreReadAsPlasmaLlmSendsThem() {
        val list = TodoList.parse(answer(open = listOf("A Tailscale bar\n- **Off:** say so", "A break room"), done = listOf("Pictures")), "/asked")
        assertEquals("/home/me/project", list.cwd)
        assertTrue(list.exists)
        assertEquals(2, list.openCount)
        assertEquals(2, list.doneCount)
        val first = list.items.first()
        assertEquals("A Tailscale bar", first.title)
        assertEquals("- **Off:** say so", first.notes)
        assertEquals("Chat UI", first.section)
        assertEquals(3, first.line)
        assertEquals("", list.items[1].notes)

        assertEquals("2026-11-27T20:41:05-05:00", list.modified)

        // Open ones only: the "Done" section, all done, goes with its items. (PlasmaLLM sends
        // ## and deeper headings only; the # title is in the markdown.)
        val open = list.shownRows(showDone = false)
        assertEquals(listOf("Chat UI", "A Tailscale bar", "A break room"), open.map { (it as? TodoRow.Heading)?.text ?: (it as TodoRow.Item).title })
        assertEquals(list.rows, list.shownRows(showDone = true))

        // An item above every heading has no section (null).
        val loose = TodoRow.parse(Json.parseToJsonElement("""{"done": false, "text": "Loose", "section": null, "line": 2}""") as JsonObject)
        assertEquals(TodoRow.Item(done = false, text = "Loose", section = null, line = 2), loose)

        // No TASKLIST.md: nothing to show, and the folder asked for when PlasmaLLM names none.
        val none = TodoList.parse(Json.parseToJsonElement("""{"exists": false, "rows": []}""") as JsonObject, "/asked")
        assertFalse(none.exists)
        assertEquals("/asked", none.cwd)
        assertTrue(none.shownRows(false).isEmpty())
    }

    @Test
    fun theOpenChatsFolderListOpensAndIsReadAgainWhenItChanges() = runBlocking {
        val c = connected { todo = answer(open = listOf("One", "Two")) }
        assertTrue(c.state.value.offersTodo)

        c.openTodo(null)
        val shown = c.waitFor("the list") { it.todo?.list != null && it.todo?.loading == false }
        assertEquals(listOf("One", "Two"), shown.todo!!.list!!.items.filterNot { it.done }.map { it.title })
        assertEquals("/home/me/project", todoRequests().single().str("cwd"), "the open chat's folder")

        // An agent added one on the laptop: PlasmaLLM says so (here with a trailing slash), and it's read again.
        host.todo = answer(open = listOf("One", "Two", "Three"))
        host.todoChanged("/home/me/project/")
        c.waitFor("the new item") { it.todo?.list?.openCount == 3 }
        assertEquals(2, todoRequests().size)

        // Another folder's list changing isn't this one's.
        host.todoChanged("/home/me/other")
        delay(300)
        assertEquals(2, todoRequests().size)

        // Closed: no more reading, even when it changes.
        c.closeTodo()
        host.todoChanged("/home/me/project")
        delay(300)
        assertNull(c.state.value.todo)
        assertEquals(2, todoRequests().size)
    }

    @Test
    fun aListTooBigSaysSoAndItIsntOfferedBefore17() = runBlocking {
        val c = connected { todoError = PlasmaLlm.Reason.TOO_BIG }
        c.openTodo("/home/me/project")
        val failed = c.waitFor("the error") { it.todo?.error != null }
        assertEquals("This to-do list is too big to show here (over 256 KB).", failed.todo!!.error)
        assertNull(failed.todo!!.list)

        // The file is there but couldn't be read: said so, read again on refresh.
        host.todoError = PlasmaLlm.Reason.UNREADABLE
        c.refreshTodo()
        val unreadable = c.waitFor("the second error") { it.todo?.error?.contains("couldn't read it") == true }
        assertEquals("This folder has a to-do list, but PlasmaLLM couldn't read it.", unreadable.todo!!.error)

        val old = connected(protocol = 15)
        assertFalse(old.state.value.offersTodo)
        old.openTodo(null)
        assertNotNull(old.waitFor("the reason") { it.todo?.error != null }.todo!!.error)
        assertTrue(todoRequests().isEmpty(), "nothing asked of a PlasmaLLM without protocol 17")
    }
}
