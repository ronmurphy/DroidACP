package com.droidacp.core.chat

import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.Plan
import com.droidacp.core.acp.SessionUpdate
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.acp.ToolCallPatch

/**
 * Pure functions that fold ACP `session/update`s into a [ChatState].
 *
 * "Pure" means: no I/O, no clocks, no mutation — same input, same output. That
 * makes this the easiest part of the app to unit test (see TranscriptTest) and
 * safe to call from inside `MutableStateFlow.update {}`, which may retry it.
 */
object Transcript {

    fun apply(state: ChatState, update: SessionUpdate): ChatState = when (update) {
        is SessionUpdate.AgentMessageChunk -> appendAgent(state, update.content, update.messageId)
        is SessionUpdate.AgentThoughtChunk -> appendThought(state, update.content, update.messageId)
        is SessionUpdate.UserMessageChunk ->
            // While *we* are running a turn we already added the user's message locally, so
            // an echo is dropped. Otherwise user chunks come from a history replay (session/load)
            // or from a prompt typed elsewhere (PlasmaLLM's window, another device). PlasmaLLM
            // never sends a device its own prompt, so there a user chunk during our turn is a
            // message typed in the Claude app (Remote Control), and it's shown.
            if (state.busy && !state.remoteTurn && !state.replaying && state.plasmaLlm == null) {
                state
            } else {
                appendUser(state, update.content, update.messageId, update.remote)
            }
        is SessionUpdate.ToolCallStarted -> upsertTool(state, update.call.id) { existing ->
            existing?.let { mergeFull(it, update.call) } ?: update.call
        }
        is SessionUpdate.ToolCallUpdated -> applyToolPatch(state, update.patch)
        is SessionUpdate.PlanReplaced -> state.copy(plans = state.plans + (LEGACY_PLAN_ID to Plan(entries = update.entries)))
        is SessionUpdate.PlanUpdated -> state.copy(plans = state.plans + (update.planId to update.plan))
        is SessionUpdate.PlanRemoved -> state.copy(plans = state.plans - update.planId)
        is SessionUpdate.CommandsUpdated -> state.copy(commands = update.commands)
        is SessionUpdate.ModeChanged -> state.copy(currentModeId = update.modeId)
        is SessionUpdate.ConfigOptionsChanged -> state.copy(configOptions = update.options)
        is SessionUpdate.SessionInfoChanged -> if (update.title != null) state.copy(sessionTitle = update.title) else state
        is SessionUpdate.Usage -> state.copy(usage = UsageInfo(update.used, update.size, update.costAmount, update.costCurrency))
        is SessionUpdate.Notice -> {
            val severity = when (update.severity) {
                "error" -> ChatItem.Severity.Error
                "warning" -> ChatItem.Severity.Warning
                else -> ChatItem.Severity.Info
            }
            val text = listOfNotNull(update.title, update.description).joinToString("\n")
            addNote(state, text, severity)
        }
        is SessionUpdate.Compaction -> when (update.status) {
            "in_progress" -> addNote(state, "Compacting the conversation to free up context…")
            "failed" -> addNote(state, "Context compaction failed" + (update.error?.let { ": $it" } ?: ""), ChatItem.Severity.Warning)
            else -> state
        }
        is SessionUpdate.Unknown -> state
    }

    /** Merge a tool-call patch (from `tool_call_update` or a permission request). */
    fun applyToolPatch(state: ChatState, patch: ToolCallPatch): ChatState {
        if (patch.id.isEmpty()) return state
        return upsertTool(state, patch.id) { existing -> existing?.apply(patch) ?: patch.toToolCall() }
    }

    fun addUser(state: ChatState, text: String, attachments: List<ContentBlock> = emptyList()): ChatState =
        state.copy(items = state.items + ChatItem.User(state.nextKey, text, attachments), nextKey = state.nextKey + 1)

    fun addNote(state: ChatState, text: String, severity: ChatItem.Severity = ChatItem.Severity.Info): ChatState =
        state.copy(items = state.items + ChatItem.Note(state.nextKey, text, severity), nextKey = state.nextKey + 1)

    /** Human readable explanation of why a turn ended, or null for a normal end. */
    fun describeStopReason(reason: String): String? = when (reason) {
        "end_turn" -> null
        "cancelled" -> "Stopped."
        "max_tokens" -> "The agent hit its output token limit."
        "max_turn_requests" -> "The agent hit its limit of model requests for one turn."
        "refusal" -> "The agent refused to continue."
        else -> "Turn ended: $reason"
    }

    const val LEGACY_PLAN_ID = ""

    // --- helpers ---

    private fun appendAgent(state: ChatState, content: ContentBlock, messageId: String?): ChatState {
        val last = state.items.lastOrNull()
        if (last is ChatItem.Agent && sameMessage(last.messageId, messageId)) {
            val updated = when (content) {
                is ContentBlock.Text -> last.copy(text = last.text + content.text, messageId = last.messageId ?: messageId)
                else -> last.copy(attachments = last.attachments + content)
            }
            return state.copy(items = state.items.dropLast(1) + updated)
        }
        val item = when (content) {
            is ContentBlock.Text -> ChatItem.Agent(state.nextKey, messageId, content.text)
            else -> ChatItem.Agent(state.nextKey, messageId, "", listOf(content))
        }
        return state.copy(items = state.items + item, nextKey = state.nextKey + 1)
    }

    private fun appendThought(state: ChatState, content: ContentBlock, messageId: String?): ChatState {
        val text = (content as? ContentBlock.Text)?.text ?: return state
        val last = state.items.lastOrNull()
        if (last is ChatItem.Thought && sameMessage(last.messageId, messageId)) {
            return state.copy(items = state.items.dropLast(1) + last.copy(text = last.text + text))
        }
        return state.copy(items = state.items + ChatItem.Thought(state.nextKey, messageId, text), nextKey = state.nextKey + 1)
    }

    private fun appendUser(state: ChatState, content: ContentBlock, messageId: String?, remote: Boolean): ChatState {
        val last = state.items.lastOrNull()
        // Stricter than agent chunks: two prompts in a row (one sent here, one typed on
        // the computer) must stay two bubbles, so the ids have to match exactly.
        if (last is ChatItem.User && last.messageId == messageId) {
            val updated = when (content) {
                is ContentBlock.Text -> last.copy(text = last.text + content.text)
                else -> last.copy(attachments = last.attachments + content)
            }
            // One marked chunk marks the whole message.
            return state.copy(items = state.items.dropLast(1) + updated.copy(remote = last.remote || remote))
        }
        val item = when (content) {
            is ContentBlock.Text -> ChatItem.User(state.nextKey, content.text, messageId = messageId, remote = remote)
            else -> ChatItem.User(state.nextKey, "", listOf(content), messageId, remote)
        }
        return state.copy(items = state.items + item, nextKey = state.nextKey + 1)
    }

    /** Chunks belong together unless both carry message ids and the ids differ. */
    private fun sameMessage(a: String?, b: String?): Boolean = a == null || b == null || a == b

    /** A second `tool_call` with a known id replaces what we know, keeping fields it leaves empty. */
    private fun mergeFull(old: ToolCall, new: ToolCall): ToolCall = new.copy(
        content = new.content.ifEmpty { old.content },
        locations = new.locations.ifEmpty { old.locations },
        rawInput = new.rawInput ?: old.rawInput,
        rawOutput = new.rawOutput ?: old.rawOutput,
    )

    private inline fun upsertTool(state: ChatState, id: String, change: (ToolCall?) -> ToolCall): ChatState {
        // Search from the end: updates almost always concern recent tool calls.
        val index = state.items.indexOfLast { it is ChatItem.Tool && it.call.id == id }
        return if (index >= 0) {
            val item = state.items[index] as ChatItem.Tool
            val items = state.items.toMutableList()
            items[index] = item.copy(call = change(item.call))
            state.copy(items = items)
        } else {
            state.copy(items = state.items + ChatItem.Tool(state.nextKey, change(null)), nextKey = state.nextKey + 1)
        }
    }
}
