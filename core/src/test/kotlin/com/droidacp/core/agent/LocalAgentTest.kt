package com.droidacp.core.agent

import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.chat.AgentController
import com.droidacp.core.chat.AgentEvent
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the real app-side [AgentController] against the real built-in agent, over the
 * in-memory transport. Only Claude is faked. This is the same path the app uses, so
 * it checks both halves of the protocol against each other.
 */
class LocalAgentTest {
    private val project: File = Files.createTempDirectory("droidacp-project").toFile()
    private val sessionsDir: File = Files.createTempDirectory("droidacp-sessions").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        project.deleteRecursively()
        sessionsDir.deleteRecursively()
    }

    private fun controller(model: FakeModel): AgentController {
        val config = LocalAgentConfig(sessionsDir, platform = "test", version = "test", modelFactory = { model })
        return AgentController(scope, "test") { profile, listener -> LocalTransport(listener, config, profile.apiKey) }
    }

    private val profile get() = Profile(name = "local", kind = ProfileKind.Local, apiKey = "sk-test", cwd = project.path)

    /** Answer every permission prompt the way [choose] says (by option kind), like a user tapping a button. */
    private fun autoAnswer(controller: AgentController, choose: (title: String) -> String) {
        scope.launch {
            controller.state.collect { s ->
                s.permissions.firstOrNull()?.let { p ->
                    val kind = choose(p.title)
                    controller.answerPermission(p.requestId, p.options.first { it.kind == kind }.optionId)
                }
            }
        }
    }

    private suspend fun AgentController.connectAndWait(): ChatState {
        connect(profile)
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

    @Test
    fun editsAFileAfterAskingAndRemembersTheConversation() = runBlocking {
        File(project, "notes.txt").writeText("hello world\n")
        val model = FakeModel(
            listOf(
                FakeModel.call("Read" to """{"file_path":"notes.txt"}"""),
                FakeModel.call(
                    "Edit" to """{"file_path":"notes.txt","old_string":"world","new_string":"there"}""",
                    "TodoWrite" to """{"todos":[{"content":"Fix greeting","status":"completed"}]}""",
                ),
                FakeModel.say("Done: the greeting now says **there**."),
            ),
        )
        val controller = controller(model)
        val asked = mutableListOf<String>()
        autoAnswer(controller) { title -> asked += title; "allow_once" }

        withTimeout(20_000) {
            val ready = controller.connectAndWait()
            assertEquals("Claude (on this device)", ready.agentName)
            assertEquals(listOf("default", "acceptEdits", "plan", "bypassPermissions"), ready.modes.map { it.id })
            assertEquals(listOf("model", "effort", "web"), ready.configOptions.map { it.id })

            val done = controller.promptAndWait("Fix the greeting")
            assertEquals("hello there\n", File(project, "notes.txt").readText())
            assertEquals(listOf("Edit notes.txt"), asked, "only the edit should need permission")

            val tools = done.tools()
            assertEquals(listOf("Read notes.txt", "Edit notes.txt", "Update to-do list"), tools.map { it.title })
            assertTrue(tools.all { it.status == "completed" }, "statuses: ${tools.map { it.status }}")
            assertTrue(tools[1].content.any { it is com.droidacp.core.acp.ToolContent.Diff }, "the edit should show a diff")
            assertEquals("Done: the greeting now says **there**.", done.lastAgentText)
            assertTrue(done.plans.isNotEmpty(), "TodoWrite should produce a plan")
            assertEquals("Fix the greeting", done.sessionTitle)
            assertNotNull(done.usage)

            // What the model was sent: tool results must follow the tool calls they answer.
            val last = model.requests.last().messages
            assertEquals(listOf("user", "assistant", "user", "assistant", "user"), last.map { it.str("role") })
            val results = last[4].objects("content")
            assertEquals(listOf("tool-3-0", "tool-3-1"), results.map { it.str("tool_use_id") })
            assertTrue(last[2].objects("content")[0].str("content")!!.contains("1\thello world"))

            // The session is saved, listed, and can be shown again after a reconnect.
            val sessionId = done.sessionId!!
            controller.disconnect()
            controller.connect(profile, resumeSessionId = sessionId)
            val reloaded = controller.state.first { it.canChat && it.items.any { i -> i is ChatItem.Agent } }
            assertEquals(sessionId, reloaded.sessionId)
            assertEquals("Fix the greeting", (reloaded.items.first { it is ChatItem.User } as ChatItem.User).text)
            assertEquals(3, reloaded.tools().size)
            assertEquals("Done: the greeting now says **there**.", reloaded.lastAgentText)

            controller.refreshPastSessions()
            val listed = controller.state.first { it.pastSessions != null }.pastSessions!!
            assertEquals(listOf(sessionId to "Fix the greeting"), listed.map { it.sessionId to it.title })
            controller.disconnect()
        }
    }

    @Test
    fun rejectedAndPlanModeToolCallsDoNotRun() = runBlocking {
        val model = FakeModel(
            listOf(
                FakeModel.call("Write" to """{"file_path":"a.txt","content":"A"}"""),
                FakeModel.say("Okay, I left it."),
                FakeModel.call("Bash" to """{"command":"touch b.txt"}""", "Glob" to """{"pattern":"*"}"""),
                FakeModel.say("Here is my plan."),
            ),
        )
        val controller = controller(model)
        autoAnswer(controller) { "reject_once" }

        withTimeout(20_000) {
            controller.connectAndWait()
            val rejected = controller.promptAndWait("write a file")
            assertFalse(File(project, "a.txt").exists())
            assertEquals("failed", rejected.tools().single().status)
            assertEquals(true, model.requests[1].messages.last().objects("content")[0].bool("is_error"))

            controller.setMode("plan")
            controller.state.first { it.currentModeId == "plan" }
            val planned = controller.promptAndWait("now plan")
            assertFalse(File(project, "b.txt").exists(), "plan mode must not run commands")
            assertEquals(listOf("failed", "failed", "completed"), planned.tools().map { it.status })
            // In plan mode the model is told so, as part of the user's message.
            val userText = model.requests[2].messages.last().objects("content").mapNotNull { it.str("text") }
            assertTrue(userText.any { "Plan mode is on" in it })
            controller.disconnect()
        }
    }

    @Test
    fun alwaysAllowIsRememberedAndBypassNeverAsks() = runBlocking {
        val model = FakeModel(
            listOf(
                FakeModel.call("Bash" to """{"command":"echo one"}"""),
                FakeModel.call("Bash" to """{"command":"echo two"}""", "Bash" to """{"command":"echo three && echo four"}"""),
                FakeModel.say("ok"),
                FakeModel.call("Write" to """{"file_path":"sub/new.txt","content":"x"}"""),
                FakeModel.say("ok"),
            ),
        )
        val controller = controller(model)
        val asked = mutableListOf<String>()
        autoAnswer(controller) { title -> asked += title; if ("&&" in title) "allow_once" else "allow_always" }

        withTimeout(20_000) {
            controller.connectAndWait()
            val done = controller.promptAndWait("run things")
            // "echo one" asked and remembered; "echo two" allowed by the rule; the compound command still asks.
            assertEquals(listOf("Run: echo one", "Run: echo three && echo four"), asked)
            assertTrue(done.tools().all { it.status == "completed" })
            val output = model.requests[2].messages.last().objects("content").map { it.str("content") }
            assertEquals(listOf("two", "three\nfour"), output)

            controller.setMode("bypassPermissions")
            controller.state.first { it.currentModeId == "bypassPermissions" }
            controller.promptAndWait("write")
            assertEquals("x", File(project, "sub/new.txt").readText())
            assertEquals(2, asked.size, "bypass mode must not ask")
            controller.disconnect()
        }
    }

    @Test
    fun stopCancelsTheTurnAndTheNextPromptStillWorks() = runBlocking {
        val model = FakeModel(listOf(FakeModel.stall(), FakeModel.say("back again")))
        val controller = controller(model)

        withTimeout(20_000) {
            controller.connectAndWait()
            assertTrue(controller.sendPrompt("sing"))
            controller.state.first { s -> s.items.lastOrNull() is ChatItem.Agent }
            controller.cancelTurn()
            val stopped = controller.state.first { !it.busy }
            assertTrue(stopped.items.any { it is ChatItem.Note && it.text == "Stopped." }, "items: ${stopped.items.takeLast(2)}")

            val next = controller.promptAndWait("hello?")
            assertEquals("back again", next.lastAgentText)
            // The interrupted turn's question and the new one reach the model as one user message.
            val messages = model.requests[1].messages
            assertEquals(listOf("user"), messages.map { it.str("role") })
            assertEquals(listOf("sing", "hello?"), messages[0].objects("content").map { it.str("text") })
            controller.disconnect()
        }
    }

    @Test
    fun configOptionsChangeTheRequest() = runBlocking {
        val model = FakeModel(listOf(FakeModel.say("hi")))
        val controller = controller(model)

        withTimeout(20_000) {
            val ready = controller.connectAndWait()
            // One change at a time, waiting for each: each reply carries the agent's full
            // list of options, so replies to changes sent together can overwrite each other.
            controller.setConfigOption(ready.configOptions.first { it.id == "model" }, "claude-haiku-5-5")
            controller.state.first { s -> s.configOptions.any { it.id == "model" && it is ConfigOption.Select && it.currentValue == "claude-haiku-5-5" } }
            controller.setConfigOption(ready.configOptions.first { it.id == "effort" }, "low")
            controller.state.first { s -> s.configOptions.any { it.id == "effort" && it is ConfigOption.Select && it.currentValue == "low" } }
            controller.setConfigOption(ready.configOptions.first { it.id == "web" }, false)
            controller.state.first { s -> s.configOptions.any { it.id == "web" && it is ConfigOption.Toggle && !it.currentValue } }
            controller.promptAndWait("/init")
            val request = model.requests.single()
            assertEquals("claude-haiku-5-5", request.model)
            assertEquals("low", request.effort)
            assertFalse(request.webAccess)
            assertTrue(request.messages[0].objects("content")[0].str("text")!!.contains("CLAUDE.md"), "/init should expand")
            assertTrue(request.system.contains(project.path))
            assertEquals(listOf("Read", "Write", "Edit", "Glob", "Grep", "Bash", "TodoWrite"), request.tools.map { it.str("name") })
            controller.disconnect()
        }
    }
}
