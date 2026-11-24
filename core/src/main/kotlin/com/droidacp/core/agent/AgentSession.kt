package com.droidacp.core.agent

import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant

/** How a session reaches the app. Implemented by [LocalAgent] on top of JSON-RPC. */
interface ClientPort {
    /** Send a `session/update` notification. */
    fun update(sessionId: String, update: JsonObject)

    /** Send `session/request_permission` and wait. Returns the chosen option id, or null if cancelled. */
    suspend fun requestPermission(sessionId: String, toolCall: JsonObject, options: JsonArray): String?
}

/**
 * One conversation with the model, and the loop that drives it.
 *
 * The loop in [prompt] is the heart of every coding agent:
 *
 *     1. send the conversation to the model
 *     2. if the reply contains tool calls: run them, add the results to the
 *        conversation, and go back to 1
 *     3. otherwise the reply is the answer; the turn is over
 *
 * The model has no memory between requests. Everything it knows about this session
 * is in [SessionData.messages], which we resend in full every time.
 */
class AgentSession(
    val data: SessionData,
    private val model: ModelClient,
    private val ctx: ToolContext,
    platform: String,
    private val client: ClientPort,
    private val store: SessionStore,
) {
    /** Built once and never changed, so the API can cache it between requests. */
    private val system = SystemPrompt.build(File(data.cwd), platform)

    /** "Always allow" answers given in this session (see [Permissions]). Not saved to disk. */
    private val allowed = mutableSetOf<String>()

    // ------------------------------------------------------------------
    // One turn
    // ------------------------------------------------------------------

    /** Run one user turn to the end. Returns the ACP stop reason. */
    suspend fun prompt(blocks: List<JsonObject>): String {
        closeDanglingToolCalls()

        blocks.forEach { record(buildJsonObject { put("sessionUpdate", "user_message_chunk"); put("content", it) }) }
        val content = promptToApiContent(blocks).toMutableList()
        if (data.mode == AgentMode.Plan) content += textBlock(SystemPrompt.PLAN_MODE_REMINDER)
        appendUserContent(content)

        if (data.title == null) {
            val firstLine = blocks.firstNotNullOfOrNull { it.str("text") }?.trim()?.lineSequence()?.firstOrNull()
            data.title = (firstLine ?: "Image").take(60)
            data.updatedAt = Instant.now().toString()
            client.update(data.id, buildJsonObject { put("sessionUpdate", "session_info_update"); put("title", data.title); put("updatedAt", data.updatedAt) })
        }

        try {
            return loop()
        } finally {
            // Runs on success, on errors and when the user presses Stop.
            data.updatedAt = Instant.now().toString()
            store.save(data)
        }
    }

    private suspend fun loop(): String {
        repeat(MAX_REQUESTS_PER_TURN) {
            val request = ModelRequest(data.model, data.effort, system, Toolbox.definitions, data.messages.toList(), data.webAccess)
            val reply = model.send(request) { event ->
                when (event) {
                    is ModelEvent.Text -> emit(chunk("agent_message_chunk", event.text))
                    is ModelEvent.Thinking -> emit(chunk("agent_thought_chunk", event.text))
                    is ModelEvent.ToolStarted -> emit(
                        buildJsonObject {
                            put("sessionUpdate", "tool_call")
                            put("toolCallId", event.id)
                            put("title", if (event.server) serverToolTitle(event.name, null) else event.name)
                            put("kind", if (event.server) serverToolKind(event.name) else Toolbox.find(event.name)?.kind ?: "other")
                            put("status", "pending")
                        },
                    )
                }
            }
            data.messages += reply.message
            client.update(
                data.id,
                buildJsonObject {
                    put("sessionUpdate", "usage_update")
                    put("used", reply.contextTokens)
                    put("size", Models.CONTEXT_WINDOW)
                },
            )
            finishServerTools(reply.message)

            when (reply.stopReason) {
                // The model wants tools run. Their results go back as the next "user" message.
                "tool_use" -> appendUserContent(runTools(reply.message))
                // A server-side tool (web search) is taking long; asking again continues it.
                "pause_turn" -> Unit
                "max_tokens", "model_context_window_exceeded" -> return "max_tokens"
                "refusal" -> {
                    reply.refusalReason?.let { emit(chunk("agent_message_chunk", it)) }
                    return "refusal"
                }
                else -> return "end_turn"
            }
        }
        return "max_turn_requests"
    }

    // ------------------------------------------------------------------
    // Running tools
    // ------------------------------------------------------------------

    /** Run every tool call in [message], in order. Returns one `tool_result` block per call. */
    private suspend fun runTools(message: JsonObject): List<JsonObject> =
        message.objects("content").filter { it.str("type") == "tool_use" }.map { call ->
            val id = call.str("id") ?: ""
            val result = runTool(id, call.str("name") ?: "", call.obj("input") ?: JsonObject(emptyMap()))
            buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", id)
                put("content", result.text)
                if (result.isError) put("is_error", true)
            }
        }

    private suspend fun runTool(id: String, name: String, input: JsonObject): ToolResult {
        val tool = Toolbox.find(name) ?: return failed(id, "There is no tool called `$name`.")
        tool.validate(input)?.let { problem -> return failed(id, "Invalid input for $name: $problem") }

        // Now that the whole input has arrived, give the tool card a proper title and,
        // for edits, the diff, so the user sees what they are being asked to approve.
        val change = tool.preview(input, ctx)
        val title = tool.title(input, ctx)
        emit(
            toolUpdate(id) {
                put("title", title)
                put("kind", tool.kind)
                put("rawInput", input)
                tool.targetFile(input, ctx)?.let { file ->
                    putJsonArray("locations") { add(buildJsonObject { put("path", file.path) }) }
                }
                if (change != null) put("content", diffContent(change))
            },
        )

        when (val decision = Permissions.decide(tool, input, data.mode, allowed, ctx)) {
            is Decision.Allow -> Unit
            is Decision.Deny -> return failed(id, decision.reason)
            is Decision.Ask -> {
                val options = buildJsonArray {
                    add(option(OPTION_ALLOW, "Allow", "allow_once"))
                    if (decision.rememberAs != null) add(option(OPTION_ALWAYS, decision.rememberLabel ?: "Always allow", "allow_always"))
                    add(option(OPTION_REJECT, "Reject", "reject_once"))
                }
                val toolCall = buildJsonObject {
                    put("toolCallId", id)
                    put("title", title)
                    put("kind", tool.kind)
                }
                when (client.requestPermission(data.id, toolCall, options)) {
                    OPTION_ALLOW -> Unit
                    OPTION_ALWAYS -> decision.rememberAs?.let { allowed += it }
                    else -> return failed(id, "The user declined this action. Don't retry it; ask what they would like instead.", "Declined")
                }
            }
        }

        emit(toolUpdate(id) { put("status", "in_progress") })
        val result = try {
            tool.run(input, ctx)
        } catch (e: CancellationException) {
            throw e // the user pressed Stop
        } catch (e: ToolFailure) {
            ToolResult(e.message ?: "Failed", isError = true)
        } catch (e: Exception) {
            ToolResult("$name failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }

        emit(
            toolUpdate(id) {
                put("status", if (result.isError) "failed" else "completed")
                // A successful edit keeps showing its diff; everything else shows its output.
                if (change == null || result.isError) put("content", textContent(result.display ?: result.text))
            },
        )
        result.plan?.let { entries -> emit(buildJsonObject { put("sessionUpdate", "plan"); put("entries", entries) }) }
        return result
    }

    private fun failed(toolCallId: String, forModel: String, forUser: String = forModel): ToolResult {
        emit(
            toolUpdate(toolCallId) {
                put("status", "failed")
                put("content", textContent(forUser))
            },
        )
        return ToolResult(forModel, isError = true)
    }

    /**
     * Web search and web fetch run on Anthropic's servers, so by the time we see them
     * they are already done: the reply contains both the call and its result. All we
     * do is complete the tool cards.
     */
    private fun finishServerTools(message: JsonObject) {
        val blocks = message.objects("content")
        for (call in blocks.filter { it.str("type") == "server_tool_use" }) {
            val id = call.str("id") ?: continue
            val name = call.str("name") ?: ""
            val result = blocks.firstOrNull { it.str("tool_use_id") == id }
            val error = result?.obj("content")?.str("error_code")
            emit(
                toolUpdate(id) {
                    put("title", serverToolTitle(name, call.obj("input")))
                    put("kind", serverToolKind(name))
                    call.obj("input")?.let { put("rawInput", it) }
                    // No result yet means the turn was paused mid-search; it continues in the next request.
                    if (result != null) put("status", if (error != null) "failed" else "completed")
                    if (error != null) put("content", textContent("Failed: $error"))
                },
            )
        }
    }

    // ------------------------------------------------------------------
    // Conversation bookkeeping
    // ------------------------------------------------------------------

    /**
     * Add content to the conversation as the user. The API wants user and assistant
     * messages to alternate, so if the last message is already from the user (for
     * instance after an interrupted turn) the new content joins that message.
     */
    private fun appendUserContent(content: List<JsonObject>) {
        val last = data.messages.lastOrNull()
        if (last != null && last.str("role") == "user") {
            data.messages[data.messages.lastIndex] = userMessage(last.objects("content") + content)
        } else {
            data.messages += userMessage(content)
        }
    }

    /**
     * Every tool call the model made must be answered with a result, or the API
     * rejects the conversation. If the last turn was interrupted between a tool call
     * and its result (Stop, a crash, the output limit), fill in "interrupted" results.
     */
    private fun closeDanglingToolCalls() {
        val index = data.messages.indexOfLast { it.str("role") == "assistant" }
        if (index < 0) return
        val calls = data.messages[index].objects("content").filter { it.str("type") == "tool_use" }.mapNotNull { it.str("id") }
        if (calls.isEmpty()) return
        val next = data.messages.getOrNull(index + 1)
        val answered = next?.objects("content").orEmpty().mapNotNull { it.str("tool_use_id") }.toSet()
        val missing = calls.filterNot { it in answered }.map { id ->
            buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", id)
                put("content", "Interrupted before this tool finished. It may not have run.")
                put("is_error", true)
            }
        }
        if (missing.isEmpty()) return
        if (next != null) {
            data.messages[index + 1] = userMessage(missing + next.objects("content"))
        } else {
            data.messages += userMessage(missing)
        }
    }

    /** Translate ACP prompt blocks (what the app sends) into Claude API content blocks. */
    private fun promptToApiContent(blocks: List<JsonObject>): List<JsonObject> = blocks.mapNotNull { block ->
        when (block.str("type")) {
            "text" -> textBlock(expandCommand(block.str("text") ?: ""))
            "image" -> buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", block.str("mimeType") ?: "image/png")
                    put("data", block.str("data") ?: "")
                }
            }
            "resource" -> block.obj("resource")?.let { r -> textBlock("File ${r.str("uri") ?: ""}:\n\n${r.str("text") ?: ""}") }
            "resource_link" -> textBlock("[File: ${block.str("uri") ?: block.str("name") ?: ""}]")
            else -> null
        }
    }

    /** Slash commands are shortcuts for longer prompts. */
    private fun expandCommand(text: String): String {
        val trimmed = text.trim()
        return when {
            trimmed == "/init" || trimmed.startsWith("/init ") ->
                SystemPrompt.INIT_COMMAND + trimmed.removePrefix("/init").trim().let { if (it.isEmpty()) "" else "\n\nAlso: $it" }
            else -> text
        }
    }

    // ------------------------------------------------------------------
    // Talking to the app
    // ------------------------------------------------------------------

    /** Show an update in the app and remember it for `session/load`. */
    private fun emit(update: JsonObject) {
        client.update(data.id, update)
        record(update)
    }

    /**
     * Add [update] to the replay log. Streamed text arrives in hundreds of small
     * chunks; consecutive chunks of the same kind are joined so the log stays small.
     */
    private fun record(update: JsonObject) {
        val kind = update.str("sessionUpdate")
        val text = update.obj("content")?.takeIf { it.str("type") == "text" }?.str("text")
        val last = data.updates.lastOrNull()
        val lastText = last?.obj("content")?.takeIf { it.str("type") == "text" }?.str("text")
        if (text != null && lastText != null && kind != "user_message_chunk" && last.str("sessionUpdate") == kind) {
            data.updates[data.updates.lastIndex] = chunk(kind ?: "", lastText + text)
        } else {
            data.updates += update
        }
    }

    /** Send the saved conversation to the app again (for `session/load`). */
    fun replay() {
        data.updates.toList().forEach { client.update(data.id, it) }
    }

    fun setMode(mode: AgentMode) {
        data.mode = mode
        client.update(data.id, buildJsonObject { put("sessionUpdate", "current_mode_update"); put("currentModeId", mode.id) })
    }

    companion object {
        /** A safety net against a model that never stops calling tools. */
        const val MAX_REQUESTS_PER_TURN = 200

        private const val OPTION_ALLOW = "allow"
        private const val OPTION_ALWAYS = "allow-always"
        private const val OPTION_REJECT = "reject"

        private fun textBlock(text: String) = buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        private fun userMessage(content: List<JsonObject>) = buildJsonObject {
            put("role", "user")
            put("content", JsonArray(content))
        }

        private fun chunk(kind: String, text: String) = buildJsonObject {
            put("sessionUpdate", kind)
            put("content", textBlock(text))
        }

        private fun toolUpdate(toolCallId: String, body: JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("sessionUpdate", "tool_call_update")
            put("toolCallId", toolCallId)
            body()
        }

        private fun option(id: String, name: String, kind: String) = buildJsonObject {
            put("optionId", id)
            put("name", name)
            put("kind", kind)
        }

        private fun diffContent(change: FileChange) = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "diff")
                    put("path", change.path)
                    change.oldText?.let { put("oldText", it) }
                    put("newText", change.newText)
                },
            )
        }

        /** Tool output for the tool card: a code block, so it keeps its line breaks. */
        private fun textContent(text: String): JsonArray {
            val shown = clip(text, 6000)
            val fence = if ("```" in shown) "````" else "```"
            return buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "content")
                        put("content", textBlock("$fence\n$shown\n$fence"))
                    },
                )
            }
        }

        private fun serverToolKind(name: String) = if (name == "web_fetch") "fetch" else "search"

        private fun serverToolTitle(name: String, input: JsonObject?): String = when (name) {
            "web_search" -> input?.str("query")?.let { "Search the web: $it" } ?: "Search the web"
            "web_fetch" -> input?.str("url")?.let { "Fetch $it" } ?: "Fetch a web page"
            else -> name
        }
    }
}
