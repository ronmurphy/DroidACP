package com.droidacp.core.chat

import com.droidacp.core.rpc.pretty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A command's input in a form people read. The agent sends a command's input as JSON
 * (`{"command": "…", "description": "…"}`), so a multi-line command shows as one line of
 * `\n` and `\"`; this gives the command as the agent wrote it, with the rest beside it.
 */
object ToolInput {
    /** [other]: every other field, as "name" to value, so nothing is left out. */
    data class Command(val command: String, val description: String?, val other: List<Pair<String, String>>)

    /** Null unless [rawInput] is a JSON object with a string `command`. */
    fun command(rawInput: String?): Command? {
        if (rawInput == null || !rawInput.trimStart().startsWith("{")) return null
        val o = try {
            Json.parseToJsonElement(rawInput) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val command = (o["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val description = (o["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifBlank { null }
        val other = o.filterKeys { it != "command" && it != "description" }.map { (k, v) -> k to (v.pretty() ?: "null") }
        return Command(command, description, other)
    }

    /** The command of a call that runs one ([kind] "execute"), else null: show [rawInput] as it is. */
    fun commandOf(kind: String, rawInput: String?): Command? = if (kind == "execute") command(rawInput) else null
}
