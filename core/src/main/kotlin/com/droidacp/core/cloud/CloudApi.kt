package com.droidacp.core.cloud

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// "Cloud mode" runs the agent on Anthropic's side, using Claude Managed Agents
// (https://platform.claude.com/docs/en/managed-agents/overview). Anthropic runs the
// agent loop and gives each session a Linux container to work in; this app only
// sends messages and shows what comes back.
//
// Three kinds of object are involved:
//
//   Agent        a saved configuration (model, instructions, tools). Created once.
//   Environment  a template for the container (network rules). Created once.
//   Session      one conversation, with its own container. Created per chat.
//
// [CloudApi] is the handful of calls the app needs, with JSON in the API's own wire
// format. [AnthropicCloudApi] implements it on Anthropic's SDK; the tests use a fake.

data class CloudSessionInfo(val id: String, val title: String?, val updatedAt: String)

/** A request failed. [message] is fit to show the user. [notFound]: the object no longer exists. */
class CloudException(message: String, val notFound: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

interface CloudApi {
    /** Create the saved agent configuration. Returns its id. */
    suspend fun createAgent(name: String, model: String, system: String, tools: JsonArray): String

    /** Create a container template. Returns its id. */
    suspend fun createEnvironment(name: String, config: JsonObject): String

    /**
     * Start a session. [agent] is the session's `agent` field: here always an
     * `agent_with_overrides` object, so each session can pick its own model and tools.
     */
    suspend fun createSession(agent: JsonObject, environmentId: String, title: String, resources: List<JsonObject>): String

    /** Change a session's title and/or its tool configuration. The session must not be mid-turn. */
    suspend fun updateSession(sessionId: String, title: String? = null, tools: JsonArray? = null)

    suspend fun listSessions(agentId: String): List<CloudSessionInfo>

    /** Everything that has happened in a session so far, oldest first. */
    suspend fun listEvents(sessionId: String): List<JsonObject>

    /** Send one event to a session, e.g. `user.message` or `user.interrupt`. */
    suspend fun send(sessionId: String, event: JsonObject)

    /**
     * The session's live events. The first item is always [STREAM_OPEN], emitted once
     * the connection is up: only events that happen after that point are delivered,
     * so anything that should be seen must be sent after it. Collect until done;
     * stopping the collection closes the connection.
     */
    fun stream(sessionId: String): Flow<JsonObject>

    fun close() {}

    companion object {
        const val STREAM_OPEN = "stream.open"
    }
}
