package com.droidacp.core

import com.droidacp.core.acp.ToolCall
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.displayTitle
import com.droidacp.core.chat.tabletAction
import com.droidacp.core.tablet.TabletActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The tablet's actions in words (plan D12), from TabDesk's tool list 2 arguments. */
class TabletActionsTest {
    private fun describe(tool: String, input: String) = TabletActions.describe("mcp__tablet__$tool", input)

    @Test
    fun eachActionSaysWhatItDoes() {
        assertEquals("Tap “Send”", describe("tap", """{"target": {"content_contains": "Send"}}"""))
        assertEquals("Tap “Roll d20” (button)", describe("tap", """{"target": {"content_contains": "Roll d20", "role": "button"}}"""))
        assertEquals("Tap an item (0.3.1)", describe("tap", """{"target": {"id": "0.3.1"}}"""))
        assertEquals("Tap at 700, 420", describe("tap", """{"x": 700, "y": 420}"""))
        assertEquals("Tap at 701, 420", describe("tap", """{"x": 700.6, "y": 420.2}"""), "a decimal point is rounded")
        assertEquals("Tap", describe("tap", """{"x": "700", "y": 420}"""), "not a number")
        assertEquals("Long-press “Hold me”", describe("tap", """{"target": {"content_contains": "Hold me"}, "long": true}"""))

        assertEquals("Type 12 characters into “Message”", describe("type", """{"text": "hello there!", "target": {"content_contains": "Message"}}"""))
        assertEquals("Type 1 character into the focused field", describe("type", """{"text": "y"}"""))
        assertEquals(
            "Replace the text in “Name” with 3 characters, then press Enter",
            describe("type", """{"text": "Bob", "target": {"content_contains": "Name"}, "replace": true, "submit": true}"""),
        )

        assertEquals("Swipe left from “Card 3”", describe("swipe", """{"from": {"content_contains": "Card 3"}, "direction": "left"}"""))
        assertEquals("Swipe up from 700, 900", describe("swipe", """{"x": 700, "y": 900, "direction": "up", "distance": "long"}"""))
        assertEquals("Scroll down", describe("scroll", """{"direction": "down"}"""))
        assertEquals("Scroll up by half a page in “Inbox”", describe("scroll", """{"direction": "up", "amount": "half", "target": {"content_contains": "Inbox"}}"""))

        assertEquals("Press Back", describe("key", """{"key": "back"}"""))
        assertEquals("Press Home", describe("key", """{"key": "home"}"""))
        assertEquals("Press Enter", describe("key", """{"key": "enter"}"""))
        assertEquals("Minimise all windows", describe("key", """{"key": "minimise_all"}"""))
        assertEquals("Wait for “Sent”", describe("wait_for", """{"target": {"content_contains": "Sent"}}"""))
        assertEquals("Wait until “Sending…” is gone", describe("wait_for", """{"target": {"content_contains": "Sending…"}, "gone": true}"""))
    }

    @Test
    fun theTextTypedIsNeverShown() {
        val secret = "my secret note to Mom"
        val title = describe("type", """{"text": "$secret", "target": {"content_contains": "Message"}}""")!!
        assertFalse(secret in title, title)
        assertFalse("secret" in title, title)
        assertEquals("Type 21 characters into “Message”", title)
        // Characters, not UTF-16 units: an emoji is one.
        assertEquals("Type 2 characters into the focused field", describe("type", """{"text": "🎲!"}"""))
    }

    @Test
    fun aLongTargetIsCutAndOtherToolsAreLeftAlone() {
        val long = "x".repeat(100)
        val title = describe("tap", """{"target": {"content_contains": "$long\nmore"}}""")!!
        assertEquals("Tap “" + "x".repeat(TabletActions.QUOTE_CHARS - 1) + "…”", title)
        // Cut by characters: an emoji is never split in half.
        val dice = describe("tap", """{"target": {"content_contains": "${"🎲".repeat(50)}"}}""")!!
        assertEquals("Tap “" + "🎲".repeat(TabletActions.QUOTE_CHARS - 1) + "…”", dice)

        // Not one of the tablet's actions, or no arguments yet: the usual title.
        assertNull(describe("screen", """{"screenshot": "none"}"""))
        assertNull(TabletActions.describe("mcp__plasmallm__tap", """{"x": 1, "y": 2}"""))
        assertNull(TabletActions.describe("Bash", """{"command": "ls"}"""))
        assertNull(describe("tap", ""))
        assertNull(describe("tap", "not json"))
        assertEquals("tap · tablet", ToolCall("t1", "mcp__tablet__tap").displayTitle)
        assertEquals("Press Back", ToolCall("t1", "mcp__tablet__key", rawInput = """{"key": "back"}""").displayTitle)
        assertEquals("screen · tablet", ToolCall("t1", "mcp__tablet__screen", rawInput = "{}").displayTitle)
    }

    @Test
    fun theNotificationNamesOnlyARunningAction() {
        val tap = ToolCall("t1", "mcp__tablet__tap", status = "in_progress", rawInput = """{"target": {"content_contains": "Send"}}""")
        val working = ChatState(status = ConnectionStatus.Connected, sessionId = "chat-a", busy = true, items = listOf(ChatItem.Tool(1, tap)))
        assertEquals("Tap “Send”", working.tabletAction)
        assertNull(working.copy(items = listOf(ChatItem.Tool(1, tap.copy(status = "completed")))).tabletAction, "done")
        assertNull(working.copy(busy = false).tabletAction, "not in a turn")
        assertNull(working.copy(items = listOf(ChatItem.Tool(1, tap.copy(title = "Bash")))).tabletAction, "another tool")

        // Not only the last item: a thought, or another tool running beside it, can come after.
        val after = listOf(
            ChatItem.Tool(1, tap),
            ChatItem.Thought(2, null, "thinking"),
            ChatItem.Tool(3, ToolCall("t2", "Bash", status = "in_progress", rawInput = """{"command": "ls"}""")),
        )
        assertEquals("Tap “Send”", working.copy(items = after).tabletAction)
        val key = ToolCall("t3", "mcp__tablet__key", status = "pending", rawInput = """{"key": "back"}""")
        assertEquals("Press Back", working.copy(items = after + ChatItem.Tool(4, key)).tabletAction, "the newest one")
    }
}
