package com.droidacp.core

import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.PermissionOption
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.chat.ChatDigest
import com.droidacp.core.chat.ChatDigest.Kind
import com.droidacp.core.chat.ChatDigest.Row
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ToolNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatDigestTest {
    @Test
    fun mcpToolsGetReadableNames() {
        assertEquals("read log · plasmallm", ToolNames.display("mcp__plasmallm__read_log"))
        assertEquals("authenticate · claude_ai_Gmail", ToolNames.display("mcp__claude_ai_Gmail__authenticate"))
        // Anything else is left alone.
        assertEquals("Read README.md", ToolNames.display("Read README.md"))
        assertEquals("mcp__half", ToolNames.display("mcp__half"))
        assertEquals("run mcp__a__b now", ToolNames.display("run mcp__a__b now"))
    }

    @Test
    fun theChatBecomesShortPlainLines() {
        val items = listOf(
            ChatItem.User(1, "Fix the **build**"),
            ChatItem.Thought(2, null, "Let me think"),
            ChatItem.Tool(3, ToolCall(id = "t1", title = "mcp__plasmallm__read_log", status = "completed")),
            ChatItem.Tool(4, ToolCall(id = "t2", title = "Run gradle", status = "in_progress")),
            ChatItem.Agent(5, "m1", "It's **fixed**, see [the log](http://x)."),
            ChatItem.Note(6, "Stopped: max tokens", ChatItem.Severity.Warning),
            ChatItem.User(7, "", attachments = listOf(ContentBlock.Image("image/png", "", null))),
            ChatItem.Agent(8, "m2", "   "),
        )
        assertEquals(
            listOf(
                Row(Kind.User, "Fix the **build**"),
                Row(Kind.Tool, "✓ read log · plasmallm"),
                Row(Kind.Tool, "… Run gradle"),
                Row(Kind.Agent, "It's fixed, see the log."),
                Row(Kind.Problem, "Stopped: max tokens"),
                Row(Kind.User, "🖼 An image"),
            ),
            ChatDigest.rows(items),
        )
    }

    @Test
    fun aLongRunOfToolsShowsItsLastFew() {
        val tools = (1..7).map { ChatItem.Tool(it.toLong(), ToolCall(id = "t$it", title = "Step $it", status = "completed")) }
        val rows = ChatDigest.rows(listOf(ChatItem.User(0, "Go")) + tools + ChatItem.Agent(99, "m", "Done"))
        assertEquals(
            listOf(
                Row(Kind.User, "Go"),
                Row(Kind.Tool, "+4 more tool calls"),
                Row(Kind.Tool, "✓ Step 5"),
                Row(Kind.Tool, "✓ Step 6"),
                Row(Kind.Tool, "✓ Step 7"),
                Row(Kind.Agent, "Done"),
            ),
            rows,
        )
        // Only the newest rows are kept.
        val long = (1..100L).map { ChatItem.User(it, "Message $it") }
        assertEquals((61..100).map { Row(Kind.User, "Message $it") }, ChatDigest.rows(long, max = 40))
    }

    @Test
    fun longRepliesAreCut() {
        val text = ChatDigest.plainText("a".repeat(5000), maxChars = 100)
        assertEquals("a".repeat(100) + "…", text)
    }

    @Test
    fun theHomeScreenOffersAllowOnceAndRejectOnce() {
        val always = PermissionOption("always", "Always allow", "allow_always")
        val once = PermissionOption("once", "Allow", "allow_once")
        val reject = PermissionOption("no", "Reject", "reject_once")
        assertEquals(once to reject, ChatDigest.quickAnswers(listOf(always, once, reject)))
        assertEquals(always to null, ChatDigest.quickAnswers(listOf(always)))
        val (allow, deny) = ChatDigest.quickAnswers(emptyList())
        assertNull(allow)
        assertNull(deny)
    }
}
