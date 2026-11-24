package com.droidacp.core.agent

import kotlinx.serialization.json.JsonObject

// The agent's view of "a language model", kept deliberately small.
//
// Everything here is plain Kotlin plus JSON in the Claude Messages API's own wire
// format (https://platform.claude.com/docs/en/api/messages). The agent loop only
// knows this interface, which has two benefits:
//
// - the unit tests can run the whole agent against a scripted fake model, and
// - the real implementation ([AnthropicModelClient]) is the only file that touches
//   the Anthropic SDK.

/** One request: "here is the conversation so far, what do you say next?" */
data class ModelRequest(
    val model: String,
    /** How hard the model should think: low, medium, high, xhigh or max. */
    val effort: String,
    val system: String,
    /** Tool definitions: `{name, description, input_schema}` objects. */
    val tools: List<JsonObject>,
    /** The conversation: `{role, content}` objects, oldest first. */
    val messages: List<JsonObject>,
    /** Also offer Anthropic's server-side web search and web fetch tools. */
    val webAccess: Boolean,
)

/** Things that happen while the reply streams in, so the UI can show progress. */
sealed interface ModelEvent {
    data class Text(val text: String) : ModelEvent
    data class Thinking(val text: String) : ModelEvent

    /**
     * The model started writing a tool call. [server] tools (web search/fetch) run on
     * Anthropic's side; the others are ours to run once the reply is complete.
     */
    data class ToolStarted(val id: String, val name: String, val server: Boolean) : ModelEvent
}

data class ModelReply(
    /**
     * The assistant's whole message as `{role: "assistant", content: [...]}`. It goes
     * back into the conversation unchanged, which matters: "thinking" blocks carry a
     * signature and the API rejects edited ones.
     */
    val message: JsonObject,
    /** Why the model stopped: end_turn, tool_use, max_tokens, pause_turn, refusal, ... */
    val stopReason: String,
    /** Tokens in the conversation after this reply (input + output), for the usage meter. */
    val contextTokens: Long,
    /** Set when [stopReason] is `refusal` and the API said why. */
    val refusalReason: String? = null,
)

/** The model could not be reached or rejected the request. [message] is fit to show the user. */
class ModelException(message: String, val authProblem: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

interface ModelClient {
    /**
     * Send [request] and suspend until the reply is complete, calling [onEvent] as it
     * streams. Cancelling the coroutine aborts the HTTP request.
     */
    suspend fun send(request: ModelRequest, onEvent: (ModelEvent) -> Unit): ModelReply
}

/** A model the user can pick. */
data class ModelChoice(val id: String, val name: String, val description: String)

object Models {
    val all = listOf(
        ModelChoice("claude-opus-5-5", "Claude Opus 5.5", "Best all-rounder for coding"),
        ModelChoice("claude-fable-5-2", "Claude Fable 5.2", "Most capable, for the hardest tasks"),
        ModelChoice("claude-sonnet-5-5", "Claude Sonnet 5.5", "Faster and cheaper"),
        ModelChoice("claude-haiku-5-5", "Claude Haiku 5.5", "Fastest and cheapest"),
    )
    const val DEFAULT = "claude-opus-5-5"

    val efforts = listOf("low", "medium", "high", "xhigh", "max")
    const val DEFAULT_EFFORT = "high"

    /** All four models have a one-million-token context window. */
    const val CONTEXT_WINDOW = 1_000_000L
}
