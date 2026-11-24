package com.droidacp.core.agent

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A scripted stand-in for Claude. Each call to [send] plays the next step of the
 * script, so a test can say "first ask for this tool, then say that".
 */
class FakeModel(private val steps: List<suspend (ModelRequest, (ModelEvent) -> Unit) -> ModelReply>) : ModelClient {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun send(request: ModelRequest, onEvent: (ModelEvent) -> Unit): ModelReply {
        requests += request
        val step = steps.getOrNull(requests.size - 1) ?: error("FakeModel ran out of steps at request ${requests.size}")
        return step(request, onEvent)
    }

    companion object {
        /** A reply that is just text. */
        fun say(text: String): suspend (ModelRequest, (ModelEvent) -> Unit) -> ModelReply = { _, onEvent ->
            onEvent(ModelEvent.Thinking("thinking..."))
            onEvent(ModelEvent.Text(text))
            ModelReply(assistant(textBlock(text)), "end_turn", 100)
        }

        /** A reply that calls tools. Each pair is (tool name, input as JSON text). */
        fun call(vararg calls: Pair<String, String>): suspend (ModelRequest, (ModelEvent) -> Unit) -> ModelReply = { request, onEvent ->
            val n = request.messages.size
            val blocks = calls.mapIndexed { i, (name, input) ->
                val id = "tool-$n-$i"
                onEvent(ModelEvent.ToolStarted(id, name, server = false))
                buildJsonObject {
                    put("type", "tool_use")
                    put("id", id)
                    put("name", name)
                    put("input", Json.parseToJsonElement(input))
                }
            }
            ModelReply(assistant(*blocks.toTypedArray()), "tool_use", 200)
        }

        /** A reply that streams slowly forever, for testing Stop. */
        fun stall(): suspend (ModelRequest, (ModelEvent) -> Unit) -> ModelReply = { _, onEvent ->
            while (true) {
                onEvent(ModelEvent.Text("la "))
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

        fun textBlock(text: String) = buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        fun assistant(vararg blocks: JsonObject) = buildJsonObject {
            put("role", "assistant")
            put("content", buildJsonArray { blocks.forEach { add(it) } })
        }
    }
}
