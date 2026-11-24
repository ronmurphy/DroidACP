package com.droidacp.core.agent

import com.droidacp.core.rpc.arr
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

// A "tool" is something the model can ask us to do: read a file, run a command...
// The model never touches the device itself. It sends a tool call (a name plus JSON
// input), we run it here, and we send the text result back in the next request.

/** What a tool needs to know about where it is running. */
class ToolContext(
    /** The project folder. Relative paths are resolved against it. */
    val cwd: File,
    /** How to run a shell command, e.g. `["/system/bin/sh", "-c"]` on Android. */
    val shell: List<String>,
    /** Extra environment variables for shell commands (HOME, TMPDIR, ...). */
    val shellEnv: Map<String, String> = emptyMap(),
) {
    /** Turn a path from the model into a file: absolute paths as they are, others inside [cwd]. */
    fun resolve(path: String): File {
        val file = if (path.startsWith("/")) File(path) else File(cwd, path)
        return file.toPath().normalize().toFile()
    }

    /** True if [file] is the project folder or somewhere below it (following symlinks). */
    fun isInsideProject(file: File): Boolean {
        val root = canonical(cwd).path
        val target = canonical(file).path
        return target == root || target.startsWith(root + File.separator)
    }

    /** A short path for titles: relative to the project folder when possible. */
    fun display(file: File): String = if (isInsideProject(file)) file.relativeToOrSelf(cwd.toPath().normalize().toFile()).path.ifEmpty { "." } else file.path

    private fun canonical(file: File): File = try {
        file.canonicalFile
    } catch (e: Exception) {
        file.absoluteFile
    }
}

/** A file about to be (or just) changed, so the app can draw a diff. [oldText] is null for a new file. */
data class FileChange(val path: String, val oldText: String?, val newText: String)

data class ToolResult(
    /** What the model gets back. */
    val text: String,
    val isError: Boolean = false,
    /** What the user sees in the tool card, when that should differ from [text]. */
    val display: String? = null,
    /** Set by TodoWrite: the new to-do list, as ACP plan entries. */
    val plan: JsonArray? = null,
)

/** Thrown by a tool to report a problem to the model, which usually corrects itself and retries. */
class ToolFailure(message: String) : Exception(message)

/** What kind of thing a tool does. The permission rules in [Permissions] are written in these terms. */
enum class Access { Read, Edit, Execute, None }

abstract class AgentTool(
    val name: String,
    val description: String,
    /** ACP tool kind, which picks the icon in the app: read, edit, search, execute, think, other. */
    val kind: String,
    val access: Access,
) {
    abstract val inputSchema: JsonObject

    /** A short line for the tool card, e.g. "Read src/main.kt". */
    abstract fun title(input: JsonObject, ctx: ToolContext): String

    /** The file this call is about, if any. Used for permission checks and "locations". */
    open fun targetFile(input: JsonObject, ctx: ToolContext): File? = null

    /** For edit tools: the change this call would make, shown to the user *before* they approve it. */
    open fun preview(input: JsonObject, ctx: ToolContext): FileChange? = null

    abstract suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult

    /** The definition sent to the API so the model knows this tool exists. */
    fun definition(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("input_schema", inputSchema)
        // Stream the tool's input as it is generated instead of buffering it on the
        // server. With this on, the API no longer validates the input for us, which is
        // why [validate] exists.
        put("eager_input_streaming", true)
    }

    /**
     * Check [input] against [inputSchema]: required fields present, simple types right.
     * Returns a description of the first problem, or null if it looks fine.
     */
    fun validate(input: JsonObject): String? {
        val properties = inputSchema.obj("properties") ?: return null
        for (required in inputSchema.arr("required").orEmpty()) {
            val key = (required as? JsonPrimitive)?.content ?: continue
            if (input[key] == null) return "Missing required parameter `$key`"
        }
        for ((key, value) in input) {
            val expected = properties.obj(key)?.str("type") ?: continue
            val ok = when (expected) {
                "string" -> value is JsonPrimitive && value.isString
                "integer", "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
                "array" -> value is JsonArray
                "object" -> value is JsonObject
                else -> true
            }
            if (!ok) return "Parameter `$key` must be of type $expected"
        }
        return null
    }
}

/**
 * A tiny builder for JSON Schema objects, so tool definitions stay readable:
 *
 *     schema {
 *         string("file_path", "The file to read", required = true)
 *         integer("limit", "How many lines to read")
 *     }
 */
class SchemaBuilder {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, required) {
            put("type", "string")
            put("description", description)
            if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
        }

    fun integer(name: String, description: String, required: Boolean = false) = add(name, required) {
        put("type", "integer")
        put("description", description)
    }

    fun boolean(name: String, description: String, required: Boolean = false) = add(name, required) {
        put("type", "boolean")
        put("description", description)
    }

    fun array(name: String, description: String, items: JsonObject, required: Boolean = false) = add(name, required) {
        put("type", "array")
        put("description", description)
        put("items", items)
    }

    private fun add(name: String, isRequired: Boolean, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        properties[name] = buildJsonObject(body)
        if (isRequired) required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (k, v) -> put(k, v) } }
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
}

fun schema(body: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(body).build()

/** Keep very long tool output from flooding the conversation: keep the start and the end. */
fun clip(text: String, max: Int = 30_000): String {
    if (text.length <= max) return text
    val head = max * 2 / 3
    val tail = max - head
    return text.take(head) + "\n\n... [${text.length - max} characters left out] ...\n\n" + text.takeLast(tail)
}
