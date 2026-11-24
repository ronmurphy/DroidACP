package com.droidacp.core.cloud

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.agents.AgentCreateParams
import com.anthropic.models.beta.agents.BetaManagedAgentsModel
import com.anthropic.models.beta.environments.EnvironmentCreateParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsDeltaType
import com.anthropic.models.beta.sessions.BetaManagedAgentsSessionAgentUpdate
import com.anthropic.models.beta.sessions.SessionCreateParams
import com.anthropic.models.beta.sessions.SessionListParams
import com.anthropic.models.beta.sessions.SessionUpdateParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsEventParams
import com.anthropic.models.beta.sessions.events.EventSendParams
import com.anthropic.models.beta.sessions.events.EventStreamParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.optionals.getOrNull

/**
 * [CloudApi] on Anthropic's official Java SDK.
 *
 * As in `AnthropicModelClient`, the rest of the app works with plain JSON, and the
 * SDK's own JSON mapper converts it to and from the SDK's typed objects here.
 */
class AnthropicCloudApi(apiKey: String, baseUrl: String? = null) : CloudApi {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey.trim())
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    private val mapper = jsonMapper()

    /** JSON in our form to one of the SDK's typed objects. */
    private inline fun <reified T> typed(json: JsonElement): T = mapper.readValue(json.toString(), T::class.java)

    /** One of the SDK's typed objects to JSON in our form. */
    private fun json(value: Any): JsonObject = Json.parseToJsonElement(mapper.writeValueAsString(value)) as JsonObject

    /** Run a blocking SDK call off the main threads, turning its errors into [CloudException]. */
    private suspend fun <T> call(block: () -> T): T = try {
        runInterruptible(Dispatchers.IO) { block() }
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        throw describe(e)
    }

    override suspend fun createAgent(name: String, model: String, system: String, tools: JsonArray): String = call {
        client.beta().agents().create(
            AgentCreateParams.builder()
                .name(name)
                .model(BetaManagedAgentsModel.of(model))
                .system(system)
                .tools(tools.map { typed<AgentCreateParams.Tool>(it) })
                .build(),
        ).id()
    }

    override suspend fun createEnvironment(name: String, config: JsonObject): String = call {
        client.beta().environments().create(
            EnvironmentCreateParams.builder()
                .name(name)
                .config(typed<EnvironmentCreateParams.Config>(config))
                .build(),
        ).id()
    }

    override suspend fun createSession(agent: JsonObject, environmentId: String, title: String, resources: List<JsonObject>): String = call {
        client.beta().sessions().create(
            SessionCreateParams.builder()
                .agent(typed<SessionCreateParams.Agent>(agent))
                .environmentId(environmentId)
                .title(title)
                .apply { if (resources.isNotEmpty()) resources(resources.map { typed<SessionCreateParams.Resource>(it) }) }
                .build(),
        ).id()
    }

    override suspend fun updateSession(sessionId: String, title: String?, tools: JsonArray?) {
        call {
            client.beta().sessions().update(
                sessionId,
                SessionUpdateParams.builder()
                    .apply { if (title != null) title(title) }
                    .apply {
                        if (tools != null) agent(typed<BetaManagedAgentsSessionAgentUpdate>(buildJsonObject { put("tools", tools) }))
                    }
                    .build(),
            )
        }
    }

    override suspend fun listSessions(agentId: String): List<CloudSessionInfo> = call {
        client.beta().sessions().list(SessionListParams.builder().agentId(agentId).limit(50).build())
            .data()
            .map { CloudSessionInfo(it.id(), it.title().getOrNull(), it.updatedAt().toString()) }
    }

    override suspend fun listEvents(sessionId: String): List<JsonObject> = call {
        // autoPager() fetches further pages as we iterate.
        client.beta().sessions().events().list(sessionId).autoPager().map { json(it) }
    }

    override suspend fun send(sessionId: String, event: JsonObject) {
        call {
            client.beta().sessions().events().send(
                sessionId,
                EventSendParams.builder().addEvent(typed<BetaManagedAgentsEventParams>(event)).build(),
            )
        }
    }

    override fun stream(sessionId: String): Flow<JsonObject> = channelFlow {
        // "event_deltas" asks for the agent's text piece by piece as it is written,
        // instead of only as whole messages.
        val params = EventStreamParams.builder().addEventDelta(BetaManagedAgentsDeltaType.AGENT_MESSAGE).build()
        val open = AtomicReference<AutoCloseable?>()
        val reader = launch(Dispatchers.IO) {
            try {
                runInterruptible {
                    client.beta().sessions().events().streamStreaming(sessionId, params).use { response ->
                        open.set(response)
                        trySendBlocking(buildJsonObject { put("type", CloudApi.STREAM_OPEN) })
                        response.stream().forEach { event -> trySendBlocking(json(event)) }
                    }
                }
            } catch (e: Exception) {
                ensureActive()
                throw describe(e)
            }
            close() // the server ended the stream
        }
        // Runs when the collector stops or is cancelled: closing the response unblocks the reader.
        awaitClose {
            open.get()?.close()
            reader.cancel()
        }
    }

    private fun describe(e: Exception): CloudException = when (e) {
        is CloudException -> e
        is UnauthorizedException, is PermissionDeniedException ->
            CloudException("Anthropic rejected the API key. Edit this agent and check the key.", cause = e)
        is NotFoundException -> CloudException("Not found: ${e.apiMessage().getOrNull() ?: e.message}", notFound = true, cause = e)
        is RateLimitException -> CloudException("Rate limited by the Anthropic API. Wait a moment and try again.", cause = e)
        is AnthropicServiceException ->
            CloudException("Claude API error ${e.statusCode()}: ${e.apiMessage().getOrNull() ?: e.message ?: "unknown"}", cause = e)
        is AnthropicIoException -> CloudException("Couldn't reach the Claude API: ${e.cause?.message ?: e.message}", cause = e)
        else -> CloudException("Request to Claude failed: ${e.message ?: e.javaClass.simpleName}", cause = e)
    }

    override fun close() = client.close()
}
