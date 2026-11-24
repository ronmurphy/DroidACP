package com.droidacp.core.agent

import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Everything about one conversation that survives closing the app. */
class SessionData(
    val id: String,
    val cwd: String,
    var title: String? = null,
    var updatedAt: String = Instant.now().toString(),
    var mode: AgentMode = AgentMode.Default,
    var model: String = Models.DEFAULT,
    var effort: String = Models.DEFAULT_EFFORT,
    var webAccess: Boolean = true,
    /** The conversation in Claude API format: what the model is sent on every request. */
    val messages: MutableList<JsonObject> = mutableListOf(),
    /**
     * The same conversation as ACP `session/update`s: what the app was shown. Replayed
     * by `session/load` so the app can redraw the chat exactly as it looked.
     */
    val updates: MutableList<JsonObject> = mutableListOf(),
)

/**
 * Saves sessions as JSON files in one folder: `<id>.json` per session, plus a small
 * `index.json` so listing sessions doesn't mean parsing every (possibly large) file.
 */
class SessionStore(private val dir: File) {
    private val indexFile = File(dir, "index.json")

    @Synchronized
    fun save(session: SessionData) {
        dir.mkdirs()
        val json = buildJsonObject {
            put("sessionId", session.id)
            put("cwd", session.cwd)
            session.title?.let { put("title", it) }
            put("updatedAt", session.updatedAt)
            put("mode", session.mode.id)
            put("model", session.model)
            put("effort", session.effort)
            put("webAccess", session.webAccess)
            put("messages", JsonArray(session.messages.toList()))
            put("updates", JsonArray(session.updates.toList()))
        }
        writeAtomically(File(dir, "${session.id}.json"), json.toString())

        val index = readIndex().toMutableMap()
        index[session.id] = buildJsonObject {
            put("sessionId", session.id)
            put("cwd", session.cwd)
            put("title", session.title ?: "Untitled")
            put("updatedAt", session.updatedAt)
        }
        writeAtomically(indexFile, JsonObject(index).toString())
    }

    @Synchronized
    fun load(id: String): SessionData? {
        // Session ids come from the client; make sure one can't point outside our folder.
        if (!id.matches(Regex("[A-Za-z0-9-]+"))) return null
        val o = parse(File(dir, "$id.json")) ?: return null
        return SessionData(
            id = id,
            cwd = o.str("cwd") ?: return null,
            title = o.str("title"),
            updatedAt = o.str("updatedAt") ?: Instant.now().toString(),
            mode = AgentMode.fromId(o.str("mode")) ?: AgentMode.Default,
            model = o.str("model")?.takeIf { m -> Models.all.any { it.id == m } } ?: Models.DEFAULT,
            effort = o.str("effort")?.takeIf { it in Models.efforts } ?: Models.DEFAULT_EFFORT,
            webAccess = o.bool("webAccess") ?: true,
            messages = o.objects("messages").toMutableList(),
            updates = o.objects("updates").toMutableList(),
        )
    }

    /** Summaries (`sessionId`, `cwd`, `title`, `updatedAt`), newest first, optionally for one folder only. */
    @Synchronized
    fun list(cwd: String?): List<JsonObject> = readIndex().values
        .mapNotNull { it as? JsonObject }
        .filter { cwd == null || it.str("cwd") == cwd }
        .sortedByDescending { it.str("updatedAt") ?: "" }

    private fun readIndex(): Map<String, kotlinx.serialization.json.JsonElement> = parse(indexFile) ?: emptyMap()

    private fun parse(file: File): JsonObject? = try {
        if (file.isFile) Json.parseToJsonElement(file.readText()) as? JsonObject else null
    } catch (e: Exception) {
        null
    }

    /** Write to a temporary file, then rename: a crash mid-write can't leave a half-written session. */
    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
