package com.droidacp.core.cloud

import com.droidacp.core.agent.clip
import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What one cloud event means for the turn in progress. */
sealed interface Step {
    /** Nothing to decide; keep listening. */
    data object Continue : Step

    /** A tool call is paused until the user allows or rejects it. */
    data class Ask(val toolEventId: String, val title: String, val kind: String) : Step

    /** The agent has finished its turn. */
    data class Done(val stopReason: String) : Step

    data class Failed(val message: String) : Step
}

/**
 * Translates a cloud session's events into ACP `session/update`s for the chat screen.
 *
 * The two protocols describe the same things with different words:
 *
 *     agent.message      ->  agent_message_chunk
 *     agent.tool_use     ->  tool_call
 *     agent.tool_result  ->  tool_call_update
 *     session.usage      ->  usage_update
 *
 * One instance per session. It remembers which events it has handled, so the same
 * event arriving twice (once from history, once live) is only shown once.
 */
class CloudEventMapper(private val emit: (JsonObject) -> Unit) {
    private val seen = HashSet<String>()

    /** Text already shown from live previews, by the id of the message being previewed. */
    private val previews = HashMap<String, StringBuilder>()

    /** Tool calls waiting for the user's allow/reject, by event id. */
    val pendingAsks = LinkedHashSet<String>()

    /** Edit and write calls show a diff, which a plain "done" result should not replace. */
    private val showsDiff = HashSet<String>()

    private var contextTokens = 0L
    private var cost: Pair<Double, String>? = null

    /**
     * Handle one event. [live] is false when replaying history: then the user's own
     * messages are shown too (live, the app has already shown them itself).
     */
    fun handle(event: JsonObject, live: Boolean): Step {
        val type = event.str("type") ?: return Step.Continue
        // Previews have no id of their own; everything else is handled once only.
        event.str("id")?.let { id -> if (type != "event_start" && !seen.add("$type:$id")) return Step.Continue }

        when (type) {
            // --- text, streamed piece by piece and then confirmed as a whole message ---
            "event_start" -> event.obj("event")?.str("id")?.let { previews[it] = StringBuilder() }
            "event_delta" -> {
                val id = event.str("event_id") ?: return Step.Continue
                val text = event.obj("delta")?.obj("content")?.str("text") ?: return Step.Continue
                previews.getOrPut(id) { StringBuilder() }.append(text)
                emit(messageChunk("agent_message_chunk", text, id))
            }
            "agent.message" -> {
                val id = event.str("id")
                val text = textOf(event["content"])
                // The whole message is the authoritative version. Show whatever part of
                // it the preview has not shown yet (all of it, if nothing was previewed).
                val shown = previews.remove(id)?.toString().orEmpty()
                val rest = if (text.startsWith(shown)) text.substring(shown.length) else if (shown.isEmpty()) text else ""
                if (rest.isNotEmpty()) emit(messageChunk("agent_message_chunk", rest, id))
            }
            "user.message" -> if (!live) {
                for (block in event.objects("content")) {
                    when (block.str("type")) {
                        "text" -> emit(buildJsonObject { put("sessionUpdate", "user_message_chunk"); put("content", block) })
                        "image" -> block.obj("source")?.let { source ->
                            emit(
                                buildJsonObject {
                                    put("sessionUpdate", "user_message_chunk")
                                    put(
                                        "content",
                                        buildJsonObject {
                                            put("type", "image")
                                            put("mimeType", source.str("media_type") ?: "image/png")
                                            put("data", source.str("data") ?: "")
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // --- tools ---
            "agent.tool_use", "agent.mcp_tool_use" -> return toolUse(event)
            "agent.tool_result", "agent.mcp_tool_result" -> toolResult(event)
            "user.tool_confirmation" -> event.str("tool_use_id")?.let(pendingAsks::remove)

            // --- bookkeeping ---
            "span.model_request_end" -> event.obj("model_usage")?.let { u ->
                contextTokens = listOf("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens", "output_tokens").sumOf { u.long(it) ?: 0 }
                emitUsage()
            }
            "session.usage" -> (event.obj("usage") ?: event).obj("list_cost")?.let { c ->
                // The amount is in cents, as text.
                c.str("amount")?.toDoubleOrNull()?.let { cents -> cost = cents / 100 to (c.str("currency") ?: "USD") }
                emitUsage()
            }
            "session.error" -> emit(
                buildJsonObject {
                    put("sessionUpdate", "notice")
                    put("severity", "error")
                    put("title", "The cloud session reported an error")
                    put("description", event.obj("error")?.let { it.str("message") ?: it.str("type") } ?: "Unknown error")
                },
            )

            // --- the end of a turn ---
            "session.status_idle" -> return when (val reason = event.obj("stop_reason")?.str("type")) {
                // Waiting for a tool confirmation. The matching tool call has already asked.
                "requires_action" -> Step.Continue
                "retries_exhausted" -> Step.Failed("The cloud session gave up after repeated errors.")
                "budget_reached" -> Step.Failed("The session reached its spending limit.")
                else -> Step.Done(reason ?: "end_turn")
            }
            "session.status_terminated" -> return Step.Failed("This cloud session has ended. Start a new session to continue.")
        }
        return Step.Continue
    }

    private fun toolUse(event: JsonObject): Step {
        val id = event.str("id") ?: return Step.Continue
        val name = event.str("name") ?: "tool"
        val input = event.obj("input") ?: JsonObject(emptyMap())
        val permission = event.str("evaluated_permission")
        val title = toolTitle(name, input)
        val kind = toolKind(name)
        val diff = diffFor(name, input)
        if (diff != null) showsDiff += id

        emit(
            buildJsonObject {
                put("sessionUpdate", "tool_call")
                put("toolCallId", id)
                put("title", title)
                put("kind", kind)
                put("rawInput", input)
                put(
                    "status",
                    when (permission) {
                        "ask" -> "pending"
                        "deny" -> "failed"
                        else -> "in_progress"
                    },
                )
                if (diff != null) put("content", diff)
                if (permission == "deny") put("content", textContent("Blocked by the server's safety check."))
            },
        )
        if (permission != "ask") return Step.Continue
        pendingAsks += id
        return Step.Ask(id, title, kind)
    }

    private fun toolResult(event: JsonObject) {
        val id = event.str("tool_use_id") ?: return
        val isError = event.bool("is_error") == true
        pendingAsks -= id
        emit(
            buildJsonObject {
                put("sessionUpdate", "tool_call_update")
                put("toolCallId", id)
                put("status", if (isError) "failed" else "completed")
                if (isError || id !in showsDiff) put("content", textContent(textOf(event["content"]).ifEmpty { "(no output)" }))
            },
        )
    }

    private fun emitUsage() {
        emit(
            buildJsonObject {
                put("sessionUpdate", "usage_update")
                put("used", contextTokens)
                put("size", 1_000_000L)
                cost?.let { (amount, currency) ->
                    put(
                        "cost",
                        buildJsonObject {
                            put("amount", amount)
                            put("currency", currency)
                        },
                    )
                }
            },
        )
    }

    companion object {
        /** The text in a `content` field, which may be a plain string or a list of blocks. */
        fun textOf(content: kotlinx.serialization.json.JsonElement?): String = when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
            else -> ""
        }

        private fun messageChunk(kind: String, text: String, messageId: String?) = buildJsonObject {
            put("sessionUpdate", kind)
            if (messageId != null) put("messageId", messageId)
            put(
                "content",
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }

        private fun textContent(text: String): JsonArray {
            val shown = clip(text, 6000)
            val fence = if ("```" in shown) "````" else "```"
            return buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "content")
                        put(
                            "content",
                            buildJsonObject {
                                put("type", "text")
                                put("text", "$fence\n$shown\n$fence")
                            },
                        )
                    },
                )
            }
        }

        private fun path(input: JsonObject) = input.str("file_path") ?: input.str("path")

        private fun toolKind(name: String) = when (name) {
            "read" -> "read"
            "write", "edit" -> "edit"
            "glob", "grep", "web_search" -> "search"
            "bash" -> "execute"
            "web_fetch" -> "fetch"
            else -> "other"
        }

        private fun toolTitle(name: String, input: JsonObject): String = when (name) {
            "bash" -> "Run: " + (input.str("command") ?: "").lineSequence().firstOrNull().orEmpty().let { if (it.length > 80) it.take(80) + "…" else it }
            "read" -> "Read ${path(input) ?: "file"}"
            "write" -> "Write ${path(input) ?: "file"}"
            "edit" -> "Edit ${path(input) ?: "file"}"
            "glob" -> "Find files: ${input.str("pattern") ?: ""}"
            "grep" -> "Search for \"${input.str("pattern") ?: ""}\""
            "web_search" -> "Search the web: ${input.str("query") ?: ""}"
            "web_fetch" -> "Fetch ${input.str("url") ?: "a web page"}"
            else -> name
        }

        /** For edits and writes, a diff the app can draw, built from the tool's own input. */
        private fun diffFor(name: String, input: JsonObject): JsonArray? {
            val file = path(input) ?: return null
            val (old, new) = when (name) {
                "edit" -> (input.str("old_string") ?: return null) to (input.str("new_string") ?: return null)
                "write" -> null to (input.str("content") ?: return null)
                else -> return null
            }
            return buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "diff")
                        put("path", file)
                        if (old != null) put("oldText", old)
                        put("newText", new)
                    },
                )
            }
        }
    }
}
