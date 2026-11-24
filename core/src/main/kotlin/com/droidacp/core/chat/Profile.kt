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

    /** In Anthropic's cloud: Claude Managed Agents runs the agent in a Linux container. */
    Cloud("cloud"),

    /**
     * The chat open in PlasmaLLM (a desktop ACP client) on a computer, reached over
     * Tailscale. DroidACP is a second screen for it: see [com.droidacp.core.acp.PlasmaLlm].
     */
    PlasmaLlm("plasmallm"),
    ;

    companion object {
        fun fromId(id: String?): ProfileKind = entries.firstOrNull { it.id == id } ?: Bridge
    }
}

/**
 * A saved agent.
 *
 * @param url    Bridge agents: WebSocket address of the bridge, e.g. `ws://192.168.1.20:8765`.
 *               PlasmaLLM: the computer's Tailscale name, e.g. `my-laptop` (see [connectUrl]).
 * @param token  Bridge agents: the bridge token (sent as `Authorization: Bearer ...`).
 * @param apiKey Built-in and cloud agents: the Anthropic API key.
 * @param cwd    Absolute path the agent works in. For a bridge agent that's a folder on
 *               the *agent's* machine; for the built-in agent it's a folder on this device;
 *               for a cloud agent it's always the container's `/workspace`. For PlasmaLLM
 *               it's optional: empty means "whatever folder PlasmaLLM has open".
 * @param repoUrl   Cloud agents: a GitHub repository to clone into the container (optional).
 * @param repoToken Cloud agents: a GitHub access token for that repository.
 */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: ProfileKind = ProfileKind.Bridge,
    val url: String = "",
    val token: String = "",
    val apiKey: String = "",
    val cwd: String,
    val repoUrl: String = "",
    val repoToken: String = "",
) {
    val isLocal: Boolean get() = kind == ProfileKind.Local
    val isCloud: Boolean get() = kind == ProfileKind.Cloud
    val isPlasmaLlm: Boolean get() = kind == ProfileKind.PlasmaLlm

    /** The WebSocket address to connect to (bridge and PlasmaLLM agents). */
    val connectUrl: String get() = if (isPlasmaLlm) com.droidacp.core.acp.PlasmaLlm.url(url) else url

    /** One line saying where this agent lives, for lists and status text. */
    val address: String
        get() = when (kind) {
            ProfileKind.Local -> "On this device"
            ProfileKind.Cloud -> "Anthropic cloud"
            ProfileKind.Bridge -> url
            ProfileKind.PlasmaLlm -> "PlasmaLLM on ${url.trim()}"
        }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("kind", kind.id)
        put("url", url)
        put("token", token)
        put("apiKey", apiKey)
        put("cwd", cwd)
        put("repoUrl", repoUrl)
        put("repoToken", repoToken)
    }

    /** Problems that would stop a connection from working, for the edit form. */
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("Give the agent a name")
        if (isLocal) {
            if (apiKey.isBlank()) add("Enter your Anthropic API key")
            if (!cwd.startsWith("/")) add("The folder must be an absolute path on this device")
        } else if (isCloud) {
            if (apiKey.isBlank()) add("Enter your Anthropic API key")
            if (repoUrl.isNotBlank()) {
                if (!repoUrl.trim().startsWith("https://github.com/")) add("The repository must be a https://github.com/... address")
                if (repoToken.isBlank()) add("A repository needs a GitHub access token")
            }
        } else if (isPlasmaLlm) {
            val host = url.trim()
            if (host.isEmpty()) add("Enter the computer's Tailscale name")
            if (host.any { it.isWhitespace() }) add("The computer's name can't contain spaces")
            // PlasmaLLM expands a leading ~ itself.
            if (cwd.isNotBlank() && !(isAbsolute(cwd) || cwd.startsWith("~"))) add("The folder must be an absolute path on the computer, or empty")
        } else {
            val u = url.trim().lowercase()
            if (!(u.startsWith("ws://") || u.startsWith("wss://"))) add("The address must start with ws:// or wss://")
            if (!isAbsolute(cwd)) add("The folder must be an absolute path on the agent's computer")
        }
    }

    /** `/home/me/x` or `C:\\x`. */
    private fun isAbsolute(path: String): Boolean =
        path.startsWith("/") || Regex("^[A-Za-z]:[\\\\/].*").matches(path)

    companion object {
        fun fromJson(o: JsonObject): Profile? {
            val kind = ProfileKind.fromId(o.str("kind"))
            val url = o.str("url") ?: ""
            if ((kind == ProfileKind.Bridge || kind == ProfileKind.PlasmaLlm) && url.isEmpty()) return null
            return Profile(
                id = o.str("id") ?: UUID.randomUUID().toString(),
                name = o.str("name") ?: url.ifEmpty { "Agent" },
                kind = kind,
                url = url,
                token = o.str("token") ?: "",
                apiKey = o.str("apiKey") ?: "",
                cwd = o.str("cwd") ?: "/",
                repoUrl = o.str("repoUrl") ?: "",
                repoToken = o.str("repoToken") ?: "",
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
