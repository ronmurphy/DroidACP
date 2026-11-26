package com.droidacp.core.people

import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState

/**
 * What an agent is doing, as one word: PlasmaLLM's vocabulary for a tab's `activity`
 * (companion protocol 9), which DroidACP also works out for the chat on screen from its own
 * updates ([ChatState.activity]). [poseFor] turns it into a Pixel Person's pose.
 */
object Activity {
    const val IDLE = "idle"
    const val WORKING = "working"

    private val POSES = mapOf(
        // Not in a turn.
        "idle" to "sleep",
        "waiting" to "sleep",
        "starting" to "think",
        "signin" to "ask",
        "stopped" to "empty",
        "error" to "oops",
        // In a turn.
        "thinking" to "think",
        "replying" to "talk",
        "permission" to "ask",
        "stopping" to "stop",
        "tablet" to "tablet",
        "subagent" to "helper",
        "note" to "plane",
        // ACP's tool kinds.
        "read" to "book",
        "edit" to "write",
        "delete" to "write",
        "move" to "write",
        "search" to "search",
        "execute" to "laptop",
        "fetch" to "fetch",
        "think" to "think",
        "tool" to "laptop",
        // Busy, with no more said (PlasmaLLM before protocol 9).
        "working" to "laptop",
    )

    private val WORDS = mapOf(
        "idle" to "idle",
        "waiting" to "waiting for a timer it set",
        "starting" to "starting",
        "signin" to "needs signing in",
        "stopped" to "not running",
        "error" to "something went wrong",
        "thinking" to "thinking",
        "replying" to "writing its reply",
        "permission" to "waiting for your OK",
        "stopping" to "stopping",
        "tablet" to "using the tablet",
        "subagent" to "working with a helper",
        "note" to "sending a note",
        "read" to "reading files",
        "edit" to "editing files",
        "delete" to "deleting files",
        "move" to "moving files",
        "search" to "searching",
        "execute" to "running a command",
        "fetch" to "looking on the web",
        "think" to "thinking",
        "tool" to "using a tool",
        "working" to "working",
    )

    /** The pose for [activity]; anything unknown is plain work. */
    fun poseFor(activity: String): String = POSES[activity] ?: "laptop"

    /**
     * A tab's [activity] as DroidACP can draw it: a word it doesn't know (from a newer
     * PlasmaLLM) is `tool` while the tab is [busy] and `idle` otherwise, as PlasmaLLM asks.
     */
    fun known(activity: String, busy: Boolean): String = when {
        activity in POSES -> activity
        busy -> "tool"
        else -> IDLE
    }

    /** [activity] in a few words, for the name label and TalkBack. */
    fun describe(activity: String): String = WORDS[activity] ?: "working"

    /** The activity of a tool call: ACP's kind, or what the tool is when its kind says nothing. */
    fun ofTool(title: String, kind: String): String {
        val t = title.trim()
        return when {
            t.startsWith("mcp__tablet__") -> "tablet"
            t.contains("note_send") -> "note"
            kind in setOf("read", "edit", "delete", "move", "search", "execute", "fetch", "think") -> kind
            else -> "tool"
        }
    }
}

/**
 * What the agent in this chat is doing, worked out from what DroidACP has seen of it: the
 * same words as PlasmaLLM's per-tab `activity` ([Activity]).
 */
val ChatState.activity: String
    get() = when {
        !isConnected -> "stopped"
        permissions.isNotEmpty() -> "permission"
        cancelling -> "stopping"
        !busy -> Activity.IDLE
        else -> when (val last = items.lastOrNull()) {
            is ChatItem.Tool -> if (last.call.status == "pending" || last.call.status == "in_progress") {
                Activity.ofTool(last.call.title, last.call.kind)
            } else {
                "thinking"
            }
            is ChatItem.Thought -> "thinking"
            is ChatItem.Agent -> "replying"
            else -> "thinking"
        }
    }
