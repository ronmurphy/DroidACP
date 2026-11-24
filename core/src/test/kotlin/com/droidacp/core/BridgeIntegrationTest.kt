package com.droidacp.core

import com.droidacp.core.acp.BridgeFolderException
import com.droidacp.core.acp.BridgeFolders
import com.droidacp.core.acp.WebSocketTransport
import com.droidacp.core.chat.AgentController
import com.droidacp.core.chat.AgentEvent
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test against a running bridge + mock agent:
 *
 *   node bridge/acp-bridge.js --port 18765 --token test -- node bridge/mock-agent.js
 *   DROIDACP_BRIDGE_URL=ws://127.0.0.1:18765 DROIDACP_BRIDGE_TOKEN=test ./gradlew :core:test
 *
 * Skipped (passes trivially) when DROIDACP_BRIDGE_URL isn't set, e.g. in CI.
 */
class BridgeIntegrationTest {

    @Test
    fun fullDemoTurn() {
        val url = System.getenv("DROIDACP_BRIDGE_URL") ?: return
        val token = System.getenv("DROIDACP_BRIDGE_TOKEN") ?: ""
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AgentController.create(scope, "test")

        // Auto-approve permission prompts, like a user tapping "Allow".
        scope.launch {
            controller.state.collect { s ->
                s.permissions.firstOrNull()?.let { p ->
                    controller.answerPermission(p.requestId, p.options.first { it.isAllow }.optionId)
                }
            }
        }

        try {
            runBlocking {
                withTimeout(30_000) {
                    controller.connect(Profile(name = "mock", url = url, token = token, cwd = "/tmp/demo"))
                    val ready = controller.state.first { it.canChat || it.status is ConnectionStatus.Failed }
                    assertEquals(ConnectionStatus.Connected, ready.status, "connect failed: $ready")
                    assertEquals("Mock Agent", ready.agentName)
                    assertTrue(ready.modes.isNotEmpty())
                    assertTrue(ready.configOptions.isNotEmpty())

                    val finished = scope.launch { controller.events.first { it is AgentEvent.TurnFinished } }
                    assertTrue(controller.sendPrompt("/demo"))
                    finished.join()
                    val done = controller.state.first { !it.busy }
                    val tools = done.items.filterIsInstance<ChatItem.Tool>()
                    assertTrue(tools.size >= 4, "expected tool calls, got ${done.items}")
                    assertTrue(tools.all { it.call.status == "completed" }, "tools: ${tools.map { it.call.status }}")
                    assertTrue(done.plans.isNotEmpty())
                    assertTrue(done.lastAgentText!!.contains("all tests pass"))

                    // Config option round trip.
                    val model = done.configOptions.first { it.id == "model" }
                    controller.setConfigOption(model, "mock-smart")
                    controller.state.first { s -> s.configOptions.any { it.id == "model" && (it as? com.droidacp.core.acp.ConfigOption.Select)?.currentValue == "mock-smart" } }

                    // Session list + reload replays history.
                    controller.refreshPastSessions()
                    val listed = controller.state.first { it.pastSessions != null }
                    val sessionId = listed.sessionId!!
                    assertTrue(listed.pastSessions!!.any { it.sessionId == sessionId })
                    controller.openPastSession(sessionId)
                    val reloaded = controller.state.first { it.canChat && it.items.any { i -> i is ChatItem.User } }
                    assertTrue(reloaded.items.any { it is ChatItem.Agent })

                    // Cancel a long turn.
                    assertTrue(controller.sendPrompt("/long"))
                    controller.state.first { s -> s.items.lastOrNull() is ChatItem.Agent }
                    controller.cancelTurn()
                    val cancelled = controller.state.first { !it.busy }
                    assertTrue(cancelled.items.any { it is ChatItem.Note && it.text == "Stopped." }, "items: ${cancelled.items.takeLast(3)}")

                    controller.disconnect()
                    assertEquals(ConnectionStatus.Disconnected, controller.state.value.status)
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun listsFoldersOnTheBridgeComputer() {
        val url = System.getenv("DROIDACP_BRIDGE_URL") ?: return
        val token = System.getenv("DROIDACP_BRIDGE_TOKEN") ?: ""
        val folders = BridgeFolders(WebSocketTransport.defaultClient())
        runBlocking {
            // No path: the home folder.
            val home = folders.list(url, token, null)
            assertEquals(home.home, home.path)

            // A folder we make, with a hidden one inside that must not be listed.
            val dir = java.nio.file.Files.createTempDirectory("droidacp-browse").toFile()
            try {
                java.io.File(dir, "src").mkdir()
                java.io.File(dir, ".git").mkdir()
                java.io.File(dir, "notes.txt").writeText("x")
                val listing = folders.list(url, token, dir.path)
                assertEquals(listOf("src"), listing.folders)
                assertEquals(dir.parent, listing.parent)
                assertEquals(java.io.File(dir, "src").path, listing.child("src"))

                val missing = runCatching { folders.list(url, token, java.io.File(dir, "nope").path) }.exceptionOrNull()
                assertTrue(missing is BridgeFolderException && "No such folder" in missing.message!!, "got $missing")
                val badToken = runCatching { folders.list(url, "wrong", dir.path) }.exceptionOrNull()
                assertTrue(badToken is BridgeFolderException && "token" in badToken.message!!, "got $badToken")
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
