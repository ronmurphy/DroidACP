package com.droidacp.core.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaWebFetchTool20250910
import com.anthropic.models.beta.messages.BetaWebFetchTool20260209
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.optionals.getOrNull

/**
 * Talks to Claude through Anthropic's official Java SDK (which Kotlin can use directly).
 *
 * The rest of the agent keeps the conversation as plain JSON in the API's wire format.
 * This class converts that JSON to the SDK's typed objects on the way out and back to
 * JSON on the way in, using the SDK's own JSON mapper, so the two can never disagree
 * about the format.
 *
 * It uses the SDK's "beta" message types (`client.beta().messages()`), because one of
 * the features it turns on, automatic fallback after a refusal, is a beta feature.
 */
class AnthropicModelClient(apiKey: String, baseUrl: String? = null) : ModelClient, AutoCloseable {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey.trim())
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    private val mapper = jsonMapper()

    override suspend fun send(request: ModelRequest, onEvent: (ModelEvent) -> Unit): ModelReply {
        val params = buildParams(request)
        try {
            return withContext(Dispatchers.IO) {
                // The SDK's streaming call blocks its thread while it waits for data, and a
                // blocked network read can't be cancelled the way a coroutine can. So a
                // second coroutine waits for cancellation ("Stop" in the app) and closes
                // the HTTP response, which makes the blocked read fail straight away.
                val open = AtomicReference<AutoCloseable?>()
                val closer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        open.get()?.close()
                    }
                }
                try {
                    runInterruptible {
                        val accumulator = BetaMessageAccumulator.create()
                        client.beta().messages().createStreaming(params).use { response ->
                            open.set(response)
                            response.stream().forEach { event ->
                                accumulator.accumulate(event) // collects the pieces into one whole message
                                translate(event)?.let(onEvent)
                            }
                        }
                        toReply(accumulator)
                    }
                } finally {
                    closer.cancel()
                }
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // cancelled: let the cancellation through
            throw describe(e)
        }
    }

    private fun buildParams(request: ModelRequest): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(request.model)
            // An upper limit on one reply, not a target. Streaming makes a large value safe.
            .maxTokens(MAX_OUTPUT_TOKENS)
            .system(request.system)
            // Cache the unchanging start of each request (tools, system prompt, earlier
            // messages). Cached input is much cheaper and faster, and an agent resends
            // the whole conversation on every step.
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            // "Adaptive": the model decides how much to think. "Summarized" returns a
            // readable summary of that thinking, which the app shows.
            .thinking(BetaThinkingConfigAdaptive.builder().display(BetaThinkingConfigAdaptive.Display.SUMMARIZED).build())
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(request.effort)).build())
            .messages(request.messages.map { mapper.readValue(it.toString(), BetaMessageParam::class.java) })

        request.tools.forEach { builder.addTool(mapper.readValue(it.toString(), BetaTool::class.java)) }

        if (request.webAccess) {
            // These run on Anthropic's servers; we only declare them.
            if (request.model in NEWER_WEB_TOOLS) {
                builder.addTool(BetaWebSearchTool20260209.builder().maxUses(WEB_USES_PER_REQUEST).build())
                builder.addTool(BetaWebFetchTool20260209.builder().maxUses(WEB_USES_PER_REQUEST).build())
            } else {
                builder.addTool(BetaWebSearchTool20250305.builder().maxUses(WEB_USES_PER_REQUEST).build())
                builder.addTool(BetaWebFetchTool20250910.builder().maxUses(WEB_USES_PER_REQUEST).build())
            }
        }

        // If a safety classifier declines a request, let the API retry it on another
        // model it picks, instead of the turn just stopping.
        if (request.model in SERVER_FALLBACK) {
            builder.addBeta("server-side-fallback-2026-07-01").fallbacks(BetaFallbacksParam.ofDefault())
        }
        return builder.build()
    }

    /** Pick out the stream events the app shows live. Everything else is left to the accumulator. */
    private fun translate(event: BetaRawMessageStreamEvent): ModelEvent? {
        event.contentBlockDelta().getOrNull()?.delta()?.let { delta ->
            delta.text().getOrNull()?.let { return ModelEvent.Text(it.text()) }
            delta.thinking().getOrNull()?.let { return ModelEvent.Thinking(it.thinking()) }
        }
        event.contentBlockStart().getOrNull()?.contentBlock()?.let { block ->
            block.toolUse().getOrNull()?.let { return ModelEvent.ToolStarted(it.id(), it.name(), server = false) }
            block.serverToolUse().getOrNull()?.let { return ModelEvent.ToolStarted(it.id(), it.name().asString(), server = true) }
        }
        return null
    }

    private fun toReply(accumulator: BetaMessageAccumulator): ModelReply {
        val message = accumulator.message()
        val usage = message.usage()
        // `toParam()` turns the reply into the form the API accepts as conversation history.
        val asJson = Json.parseToJsonElement(mapper.writeValueAsString(message.toParam())) as JsonObject
        return ModelReply(
            message = asJson,
            stopReason = message.stopReason().getOrNull()?.asString() ?: "end_turn",
            contextTokens = usage.inputTokens() +
                (usage.cacheReadInputTokens().getOrNull() ?: 0) +
                (usage.cacheCreationInputTokens().getOrNull() ?: 0) +
                usage.outputTokens(),
            refusalReason = message.stopDetails().getOrNull()?.explanation()?.getOrNull(),
        )
    }

    /** Turn SDK exceptions into messages a person can act on. Most specific first. */
    private fun describe(e: Exception): ModelException = when (e) {
        is ModelException -> e
        is UnauthorizedException, is PermissionDeniedException ->
            ModelException("Anthropic rejected the API key. Edit this agent and check the key.", authProblem = true, cause = e)
        is RateLimitException -> ModelException("Rate limited by the Anthropic API. Wait a moment and try again.", cause = e)
        is AnthropicServiceException ->
            ModelException("Claude API error ${e.statusCode()}: ${e.apiMessage().getOrNull() ?: e.message ?: "unknown"}", cause = e)
        is AnthropicIoException -> ModelException("Couldn't reach the Claude API: ${e.cause?.message ?: e.message}", cause = e)
        else -> ModelException("Request to Claude failed: ${e.message ?: e.javaClass.simpleName}", cause = e)
    }

    override fun close() = client.close()

    private companion object {
        const val MAX_OUTPUT_TOKENS = 64_000L
        const val WEB_USES_PER_REQUEST = 8L

        /** Models that take the newer web tools, which filter results before the model reads them. */
        val NEWER_WEB_TOOLS = setOf("claude-opus-5-5", "claude-fable-5-2", "claude-sonnet-5-5")

        /** Models for which the API offers server-side fallback after a refusal. */
        val SERVER_FALLBACK = setOf("claude-opus-5-5", "claude-fable-5-2", "claude-sonnet-5-5")
    }
}
