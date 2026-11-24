package com.droidacp.core.acp

import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** One folder on the bridge's computer, with the folders inside it. */
data class FolderListing(
    val path: String,
    /** The folder above, or null at the top of the drive. */
    val parent: String?,
    /** The user's home folder on that computer, e.g. `/home/brad`. */
    val home: String,
    /** The folder the bridge was started in (its `--cwd`). */
    val bridgeCwd: String,
    val folders: List<String>,
    val separator: String,
    val truncated: Boolean,
) {
    fun child(name: String): String = if (path.endsWith(separator)) path + name else path + separator + name
}

class BridgeFolderException(message: String) : Exception(message)

/**
 * Asks a running `acp-bridge.js` which folders exist on its computer, so the app can
 * offer a folder picker instead of a blank text box.
 *
 * This is a plain HTTP GET to the same address as the WebSocket (`ws://host:8765`
 * becomes `http://host:8765/droidacp/folders?path=...`), with the same token.
 */
class BridgeFolders(private val http: OkHttpClient) {

    /** The bridge's WebSocket address as a plain web address: `ws://` -> `http://`, `wss://` -> `https://`. */
    private fun httpAddress(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            else -> trimmed
        }
    }

    suspend fun list(bridgeUrl: String, token: String, path: String?): FolderListing {
        val base = httpAddress(bridgeUrl).toHttpUrlOrNull() ?: throw BridgeFolderException("Not a valid address: $bridgeUrl")
        val url = base.newBuilder()
            .encodedPath("/droidacp/folders")
            .query(null)
            .apply { if (!path.isNullOrBlank()) addQueryParameter("path", path.trim()) }
            .build()
        val request = Request.Builder().url(url).apply {
            if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}")
        }.build()

        // OkHttp's execute() blocks, so run it on an IO thread; runInterruptible lets
        // closing the folder picker cancel a slow request.
        val (status, body) = try {
            runInterruptible(Dispatchers.IO) {
                http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            }
        } catch (e: IOException) {
            throw BridgeFolderException("Couldn't reach the bridge at ${base.host}:${base.port}. Is it running, and is the address right? (${e.message})")
        }

        val json = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw BridgeFolderException(
            // Bridges from before this feature answer every path with a plain-text greeting.
            "This bridge can't list folders yet. Update DroidACP on the computer (git pull) and restart the bridge.",
        )

        if (status != 200) {
            throw BridgeFolderException(
                when (status) {
                    401 -> "The bridge rejected the token. Check the token field."
                    else -> json.str("error") ?: "The bridge answered with HTTP $status"
                },
            )
        }
        return FolderListing(
            path = json.str("path") ?: throw BridgeFolderException("The bridge sent an incomplete answer"),
            parent = json.str("parent"),
            home = json.str("home") ?: "",
            bridgeCwd = json.str("cwd") ?: "",
            folders = (json["folders"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            separator = json.str("separator") ?: "/",
            truncated = json.bool("truncated") ?: false,
        )
    }
}
