package com.droidacp.core

import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.acp.ToolContent
import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ChatView
import kotlin.test.Test
import kotlin.test.assertEquals

/** The developer and standard views of a chat ([ChatView]), and what a chat says while it opens. */
class ChatViewTest {
    private val picture = ContentBlock.Image("image/png", "iVBORw0KGgo=", null)
    private val screenshot = ToolCall("t2", "screen · tablet", status = "completed", content = listOf(ToolContent.Block(ContentBlock.Text("Camera")), ToolContent.Block(picture)))
    private val items = listOf(
        ChatItem.User(1, "Show me the kittens"),
        ChatItem.Thought(2, null, "The tablet's camera…"),
        ChatItem.Tool(3, ToolCall("t1", "open_app · tablet", status = "completed", content = listOf(ToolContent.Block(ContentBlock.Text("ok"))))),
        ChatItem.Tool(4, screenshot),
        ChatItem.Agent(5, null, "Two kittens, on the left."),
        ChatItem.User(6, "[Note from tabLauncher] …", agentNote = true),
        ChatItem.Note(7, "Stopped."),
    )
    private val state = ChatState(items = items)

    @Test
    fun developerShowsEverythingButNotesBetweenAgents() {
        assertEquals(listOf(1L, 2, 3, 4, 5, 7), ChatView.shown(state, developer = true).map { it.key })
    }

    @Test
    fun standardShowsTheConversationAndAToolsPictures() {
        // No thinking, no tool card without a picture; the screenshot stays, for its picture.
        assertEquals(listOf(1L, 4, 5, 7), ChatView.shown(state, developer = false).map { it.key })
        assertEquals(listOf(picture), ChatView.picturesOf(screenshot))
        assertEquals(emptyList(), ChatView.picturesOf((items[2] as ChatItem.Tool).call))
    }

    @Test
    fun openingSaysHowMuchHasArrivedInTheViewsWords() {
        assertEquals("Opening the chat…", ChatView.openingText(ChatState(), developer = true))
        assertEquals("Opening the chat… 6 messages and tool calls so far", ChatView.openingText(state, developer = true))
        // Standard counts only messages: the user's (not the note from another agent) and the agent's.
        assertEquals("Opening the chat… 2 messages so far", ChatView.openingText(state, developer = false))
        assertEquals("Opening the chat… 1 message so far", ChatView.openingText(ChatState(items = items.take(1)), developer = false))
    }
}
