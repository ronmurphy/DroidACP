package com.droidacp.core.chat

import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.acp.ToolContent

/**
 * What the chat list shows, in the two views a device can pick.
 *
 * - Developer: everything the agent does, as before (tool cards, thinking, plans).
 * - Standard: the conversation and its pictures, for people who just chat. Tool cards and
 *   thinking are left out, but a tool's pictures (a screenshot, an image it read) show on
 *   their own, so nothing the agent shows the user is lost.
 */
object ChatView {
    /** The items to show, in order. Notes between PlasmaLLM's agents never show (their answers do). */
    fun shown(state: ChatState, developer: Boolean): List<ChatItem> = state.items.filter { item ->
        when (item) {
            is ChatItem.User -> !item.agentNote
            is ChatItem.Thought -> developer
            is ChatItem.Tool -> developer || picturesOf(item.call).isNotEmpty()
            is ChatItem.Agent, is ChatItem.Note -> true
        }
    }

    /** A tool's pictures, for the standard view. */
    fun picturesOf(call: ToolCall): List<ContentBlock.Image> =
        call.content.mapNotNull { (it as? ToolContent.Block)?.block as? ContentBlock.Image }

    /**
     * What the screen says while a chat's history comes in (it's built out of sight and
     * shown once, at its end): how much has arrived, in the view's own words.
     */
    fun openingText(state: ChatState, developer: Boolean): String {
        val n = if (developer) {
            shown(state, developer = true).size
        } else {
            state.items.count { it is ChatItem.Agent || (it is ChatItem.User && !it.agentNote) }
        }
        val what = when {
            n == 0 -> return "Opening the chat…"
            developer -> if (n == 1) "message or tool call" else "messages and tool calls"
            else -> if (n == 1) "message" else "messages"
        }
        return "Opening the chat… $n $what so far"
    }
}
