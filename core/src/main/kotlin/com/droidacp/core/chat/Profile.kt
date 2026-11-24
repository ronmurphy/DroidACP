package com.droidacp.core.chat

import com.droidacp.core.rpc.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Where a saved agent runs. */
enum class ProfileKind(val id: String) {
    /** On another machine (or in Termux), reached through `acp-bridge.js` over a WebSocket. */
    Bridge("bridge"),

    /** Inside this app: the built-in agent, which calls the Claude API directly. */
    Local("local"),
    ;

    companion object {
        fun fromId(id: String?): ProfileKind = entries.firstOrNull { it.id == id } ?: Bridge
    }
}

/**
 * A saved agent.
 *
 * @param url    Bridge agents: WebSocket address of the bridge, e.g. `ws://192.168.1.20:8765`.
 * @param token  Bridge agents: the bridge token (sent as `Authorization: Bearer ...`).
 * @param apiKey Built-in agent: the Anthropic API key it calls Claude with.
 * @param cwd    Absolute path the agent works in. For a bridge agent that's a folder on
 *               the *agent's* machine; for the built-in agent it's a folder on this device.
 */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: ProfileKind = ProfileKind.Bridge,
    val url: String = "",
    val token: String = "",
    val apiKey: String = "",
    val cwd: String,
) {
    val isLocal: Boolean get() = kind == ProfileKind.Local

    /** One line saying where this agent lives, for lists and status text. */
    val address: String get() = if (isLocal) "On this device" else url

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("kind", kind.id)
        put("url", url)
        put("token", token)
        put("apiKey", apiKey)
        put("cwd", cwd)
    }

    /** Problems that would stop a connection from working, for the edit form. */
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("Give the agent a name")
        if (isLocal) {
            if (apiKey.isBlank()) add("Enter your Anthropic API key")
            if (!cwd.startsWith("/")) add("The folder must be an absolute path on this device")
        } else {
            val u = url.trim().lowercase()
            if (!(u.startsWith("ws://") || u.startsWith("wss://"))) add("The address must start with ws:// or wss://")
            if (!(cwd.startsWith("/") || Regex("^[A-Za-z]:[\\\\/].*").matches(cwd))) add("The folder must be an absolute path on the agent's computer")
        }
    }

    companion object {
        fun fromJson(o: JsonObject): Profile? {
            val kind = ProfileKind.fromId(o.str("kind"))
            val url = o.str("url") ?: ""
            if (kind == ProfileKind.Bridge && url.isEmpty()) return null
            return Profile(
                id = o.str("id") ?: UUID.randomUUID().toString(),
                name = o.str("name") ?: url.ifEmpty { "Agent" },
                kind = kind,
                url = url,
                token = o.str("token") ?: "",
                apiKey = o.str("apiKey") ?: "",
                cwd = o.str("cwd") ?: "/",
            )
        }

        fun listToJson(profiles: List<Profile>): String = buildJsonArray { profiles.forEach { add(it.toJson()) } }.toString()

        fun listFromJson(text: String?): List<Profile> {
            if (text.isNullOrBlank()) return emptyList()
            return try {
                (Json.parseToJsonElement(text) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::fromJson) } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
