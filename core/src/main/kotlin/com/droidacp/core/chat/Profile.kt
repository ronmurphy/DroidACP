package com.droidacp.core.chat

import com.droidacp.core.rpc.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * A saved agent connection.
 *
 * @param url   WebSocket address of the bridge, e.g. `ws://192.168.1.20:8765`.
 * @param token The bridge token (sent as `Authorization: Bearer ...`).
 * @param cwd   Absolute path *on the agent's machine* the agent should work in.
 */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val token: String = "",
    val cwd: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("url", url)
        put("token", token)
        put("cwd", cwd)
    }

    /** Problems that would stop a connection from working, for the edit form. */
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("Give the agent a name")
        val u = url.trim().lowercase()
        if (!(u.startsWith("ws://") || u.startsWith("wss://"))) add("The address must start with ws:// or wss://")
        if (!(cwd.startsWith("/") || Regex("^[A-Za-z]:[\\\\/].*").matches(cwd))) add("The folder must be an absolute path on the agent's computer")
    }

    companion object {
        fun fromJson(o: JsonObject): Profile? {
            val url = o.str("url") ?: return null
            return Profile(
                id = o.str("id") ?: UUID.randomUUID().toString(),
                name = o.str("name") ?: url,
                url = url,
                token = o.str("token") ?: "",
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
