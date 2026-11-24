package com.droidacp.core

import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.acp.SessionUpdate
import com.droidacp.core.acp.ToolContent
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.Transcript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TranscriptTest {

    private fun update(json: String): SessionUpdate = SessionUpdate.parse(Json.parseToJsonElement(json) as JsonObject)

    private fun ChatState.feed(vararg json: String): ChatState = json.fold(this) { s, j -> Transcript.apply(s, update(j)) }

    @Test
    fun agentChunksAreJoinedIntoOneMessage() {
        val s = ChatState().feed(
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hel"}}""",
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"lo"}}""",
        )
        assertEquals(1, s.items.size)
        assertEquals("Hello", (s.items[0] as ChatItem.Agent).text)
    }

    @Test
    fun differentMessageIdsStartNewMessages() {
        val s = ChatState().feed(
            """{"sessionUpdate":"agent_message_chunk","messageId":"m1","content":{"type":"text","text":"one"}}""",
            """{"sessionUpdate":"agent_message_chunk","messageId":"m2","content":{"type":"text","text":"two"}}""",
        )
        assertEquals(listOf("one", "two"), s.items.map { (it as ChatItem.Agent).text })
    }

    @Test
    fun toolCallIsCreatedThenPatched() {
        val s = ChatState().feed(
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Let me look."}}""",
            """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read file","kind":"read","status":"pending"}""",
            """{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"diff","path":"/a.txt","oldText":"a","newText":"b"}]}""",
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Done."}}""",
        )
        assertEquals(3, s.items.size)
        val tool = assertIs<ChatItem.Tool>(s.items[1])
        assertEquals("Read file", tool.call.title)
        assertEquals("completed", tool.call.status)
        assertIs<ToolContent.Diff>(tool.call.content.single())
        // Text after a tool call is a new message, not glued onto the first one.
        assertEquals("Done.", (s.items[2] as ChatItem.Agent).text)
    }

    @Test
    fun updateForUnknownToolCallCreatesIt() {
        val s = ChatState().feed("""{"sessionUpdate":"tool_call_update","toolCallId":"zz","title":"Run ls","status":"in_progress"}""")
        val tool = assertIs<ChatItem.Tool>(s.items.single())
        assertEquals("Run ls", tool.call.title)
    }

    @Test
    fun plansModesCommandsAndConfig() {
        val s = ChatState().feed(
            """{"sessionUpdate":"plan","entries":[{"content":"Step 1","priority":"high","status":"completed"}]}""",
            """{"sessionUpdate":"plan_update","plan":{"type":"markdown","planId":"p2","content":"# Plan"}}""",
            """{"sessionUpdate":"current_mode_update","currentModeId":"plan"}""",
            """{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"review","description":"Review code","input":{"hint":"what"}}]}""",
            """{"sessionUpdate":"config_option_update","configOptions":[{"id":"model","name":"Model","type":"select","currentValue":"b","options":[{"group":"g","name":"Group","options":[{"value":"a","name":"A"},{"value":"b","name":"B"}]}]}]}""",
            """{"sessionUpdate":"session_info_update","title":"Fix bug"}""",
            """{"sessionUpdate":"usage_update","used":50,"size":100}""",
            """{"sessionUpdate":"something_new","foo":1}""",
        )
        assertEquals("Step 1", s.plans[Transcript.LEGACY_PLAN_ID]?.entries?.single()?.content)
        assertEquals("# Plan", s.plans["p2"]?.markdown)
        assertEquals("plan", s.currentModeId)
        assertEquals("what", s.commands.single().inputHint)
        val model = assertIs<ConfigOption.Select>(s.configOptions.single())
        assertEquals("B", model.currentName)
        assertEquals("Fix bug", s.sessionTitle)
        assertEquals(0.5f, s.usage?.fraction)
        assertEquals(0, s.items.size)

        val removed = s.feed("""{"sessionUpdate":"plan_removed","planId":"p2"}""")
        assertNull(removed.plans["p2"])
    }

    @Test
    fun userChunksIgnoredDuringOurTurnButKeptDuringReplay() {
        val chunk = """{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"hi"}}"""
        assertEquals(0, ChatState(busy = true).feed(chunk).items.size)
        val replay = ChatState(replaying = true).feed(chunk, """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hello"}}""")
        assertEquals("hi", (replay.items[0] as ChatItem.User).text)
        assertEquals("hello", (replay.items[1] as ChatItem.Agent).text)
    }

    @Test
    fun keysAreUnique() {
        val s = ChatState().feed(
            """{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"hmm"}}""",
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"a"}}""",
            """{"sessionUpdate":"tool_call","toolCallId":"1","title":"x"}""",
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"b"}}""",
        )
        assertEquals(s.items.size, s.items.map { it.key }.toSet().size)
        assertNotNull(s.lastAgentText)
        assertEquals("b", s.lastAgentText)
    }
}
