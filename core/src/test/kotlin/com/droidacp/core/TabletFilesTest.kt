package com.droidacp.core

import com.droidacp.core.acp.PlasmaEvent
import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.toolProgressNow
import com.droidacp.core.rpc.RpcErrorCodes
import com.droidacp.core.rpc.RpcException
import com.droidacp.core.tablet.TabletRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Files through the relay (plan D17): the shared folder's big chunks, big answers, and PlasmaLLM's progress. */
class TabletFilesTest {
    private val tabdesk = FakeTabDesk().apply { setWanted(true) }
    private val queued = AtomicLong(0)
    private val relay = TabletRelay(tabdesk, TabletRelayTest.SERVER_ID, "c1", allowed = true, queued = { queued.get() })

    /** A `folder_write` call whose chunk makes the message about [chars] characters long. */
    private fun chunkCall(chars: Int, requestId: String = "mcp-request:9") = buildJsonObject {
        put("serverId", TabletRelayTest.SERVER_ID)
        put("requestId", requestId)
        put("method", "tools/call")
        putJsonObject("params") {
            put("name", "folder_write")
            putJsonObject("arguments") { put("name", "report.pdf"); put("data", "A".repeat(chars)) }
        }
        put("sessionId", "chat-a")
        put("toolCallId", "tool-1")
    }

    private fun answer(chars: Int) = """{"jsonrpc":"2.0","id":"mcp-request:9","result":{"content":[{"type":"text","text":"${"B".repeat(chars)}"}]}}"""

    private fun streams(on: Boolean) {
        tabdesk.status.value = tabdesk.status.value.copy(streams = on)
    }

    @Test
    fun aBigChunkGoesOnlyToATabDeskThatTakesStreams() = runBlocking {
        // An older TabDesk: refused here (-33002), and never sent.
        streams(false)
        val old = assertFailsWith<RpcException> { relay.call(chunkCall(40_000)) }
        assertEquals(PlasmaLlm.TABLET_FAILED, old.code)
        assertTrue("update TabDesk" in old.message.orEmpty(), old.message)
        assertTrue(tabdesk.sentIds.isEmpty())

        // A small message still goes, as before.
        val small = async(Dispatchers.Default) { relay.call(chunkCall(1_000)) }
        val (id1, _) = tabdesk.nextSent()
        tabdesk.answer(id1, answer(10))
        assertTrue(withTimeout(5_000) { small.await() }.containsKey("result"))

        // One that takes streams gets it whole, for TabDeskLink to send through a pipe.
        streams(true)
        val big = async(Dispatchers.Default) { relay.call(chunkCall(1_400_000, "mcp-request:10")) }
        val (id2, message) = tabdesk.nextSent()
        assertTrue(message.length > TabletRelay.MAX_SEND_CHARS)
        tabdesk.answer(id2, answer(10))
        assertTrue(withTimeout(5_000) { big.await() }.containsKey("result"))
    }

    @Test
    fun overTwoMiBIsRefusedHereInUtf8Bytes() = runBlocking {
        streams(true)
        val e = assertFailsWith<RpcException> { relay.call(chunkCall(TabletRelay.MAX_STREAM_BYTES + 1)) }
        assertEquals(RpcErrorCodes.INVALID_REQUEST, e.code)
        assertTrue(tabdesk.sentIds.isEmpty(), "never sent")

        // Bytes, not characters: é is two.
        assertEquals(3L, TabletRelay.utf8Bytes("aé"))
        assertEquals(4L, TabletRelay.utf8Bytes("🎲"))
        assertEquals(3L, TabletRelay.utf8Bytes("€"))
    }

    @Test
    fun aBigAnswerWaitsForTheConnectionsQueueToEmpty() = runBlocking {
        queued.set(5L * 1024 * 1024)
        val call = async(Dispatchers.Default) { relay.call(chunkCall(100)) }
        val (id, _) = tabdesk.nextSent()
        tabdesk.answer(id, answer(TabletRelay.BIG_ANSWER_CHARS + 1))
        delay(300)
        assertFalse(call.isCompleted, "held while 5 MiB wait to go out")
        queued.set(200_000)
        assertTrue(withTimeout(5_000) { call.await() }.containsKey("result"), "sent once it's nearly empty")

        // A small answer never waits.
        queued.set(5L * 1024 * 1024)
        val small = async(Dispatchers.Default) { relay.call(chunkCall(100, "mcp-request:11")) }
        val (id2, _) = tabdesk.nextSent()
        tabdesk.answer(id2, answer(100))
        assertTrue(withTimeout(1_000) { small.await() }.containsKey("result"))
    }

    // --- PlasmaLLM's progress (`_plasmallm/tool_progress`) ---

    private fun progress(params: JsonObject) = PlasmaEvent.parse(PlasmaLlm.TOOL_PROGRESS, params)

    @Test
    fun progressIsReadAsOneShortLine() {
        assertEquals(
            PlasmaEvent.ToolProgress("chat-a", "tool-1", "Copying report.pdf from the tablet: 12 of 50 MB"),
            progress(buildJsonObject { put("sessionId", "chat-a"); put("toolCallId", "tool-1"); put("text", "Copying report.pdf from the tablet:\n 12 of 50 MB") }),
        )
        val long = progress(buildJsonObject { put("sessionId", "chat-a"); put("toolCallId", "tool-1"); put("text", "x".repeat(500)) })
        assertEquals(200, (long as PlasmaEvent.ToolProgress).text.length)
        assertNull(progress(buildJsonObject { put("sessionId", "chat-a"); put("text", "no call") }))
        assertNull(progress(buildJsonObject { put("sessionId", "chat-a"); put("toolCallId", "tool-1"); put("text", "  ") }))
    }

    @Test
    fun progressShowsOnlyWhileItsCallRuns() {
        val copy = ToolCall("tool-1", "put_file · plasmallm", status = "in_progress")
        val s = ChatState(
            status = ConnectionStatus.Connected,
            sessionId = "chat-a",
            busy = true,
            items = listOf(ChatItem.Tool(1, copy), ChatItem.Thought(2, null, "waiting")),
            toolProgress = mapOf("tool-1" to "Copying report.pdf to the tablet: 3 of 8 MB"),
        )
        assertEquals("Copying report.pdf to the tablet: 3 of 8 MB", s.progressOf(copy))
        assertEquals("Copying report.pdf to the tablet: 3 of 8 MB", s.toolProgressNow, "for the notification, past a later item")

        val done = copy.copy(status = "completed")
        val after = s.copy(items = listOf(ChatItem.Tool(1, done)))
        assertNull(after.progressOf(done), "over when the call ends")
        assertNull(after.toolProgressNow)
        assertNull(s.copy(busy = false).toolProgressNow, "or the turn")
    }
}
