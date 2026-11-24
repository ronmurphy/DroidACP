package com.droidacp.core.agent

import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

// Read, Write and Edit: the tools that look at and change files.

private const val MAX_FILE_BYTES = 10L * 1024 * 1024
private const val DEFAULT_LINE_LIMIT = 2000
private const val MAX_LINE_CHARS = 2000

/** Files with a NUL byte near the start are almost certainly not text. */
internal fun looksBinary(file: File): Boolean = try {
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        val n = input.read(buffer)
        (0 until n).any { buffer[it] == 0.toByte() }
    }
} catch (e: Exception) {
    false
}

private fun readText(file: File): String {
    if (!file.exists()) throw ToolFailure("File does not exist: ${file.path}")
    if (file.isDirectory) throw ToolFailure("${file.path} is a directory, not a file. Use Glob to list it.")
    if (file.length() > MAX_FILE_BYTES) throw ToolFailure("File is too large to read (${file.length()} bytes).")
    if (looksBinary(file)) throw ToolFailure("${file.path} looks like a binary file.")
    return file.readText()
}

class ReadTool : AgentTool(
    name = "Read",
    description = "Read a text file. Returns the lines prefixed with their line numbers. " +
        "Reads up to $DEFAULT_LINE_LIMIT lines; use offset and limit to read a different part of a long file.",
    kind = "read",
    access = Access.Read,
) {
    override val inputSchema = schema {
        string("file_path", "Path of the file: absolute, or relative to the project folder", required = true)
        integer("offset", "Line number to start at (1 = first line)")
        integer("limit", "Maximum number of lines to read")
    }

    override fun title(input: JsonObject, ctx: ToolContext) = "Read ${targetFile(input, ctx)?.let(ctx::display) ?: "file"}"

    override fun targetFile(input: JsonObject, ctx: ToolContext): File? = input.str("file_path")?.let(ctx::resolve)

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val file = ctx.resolve(input.str("file_path")!!)
        val lines = readText(file).lines()
        val first = ((input.long("offset") ?: 1L).coerceAtLeast(1L) - 1).toInt()
        val limit = (input.long("limit") ?: DEFAULT_LINE_LIMIT.toLong()).coerceIn(1L, 20_000L).toInt()
        if (first >= lines.size && lines.isNotEmpty()) throw ToolFailure("offset ${first + 1} is past the end of the file (${lines.size} lines).")

        val shown = lines.drop(first).take(limit)
        val body = shown.withIndex().joinToString("\n") { (i, line) ->
            val text = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + "… [line cut]" else line
            "${(first + i + 1).toString().padStart(6)}\t$text"
        }
        val rest = lines.size - first - shown.size
        val note = if (rest > 0) "\n\n[$rest more lines. Read again with offset=${first + shown.size + 1} to continue.]" else ""
        ToolResult(
            text = if (lines.all { it.isEmpty() }) "[The file exists but is empty.]" else body + note,
            display = "${shown.size} of ${lines.size} lines",
        )
    }
}

class WriteTool : AgentTool(
    name = "Write",
    description = "Create a file, or replace the whole contents of an existing one. Missing folders are created. " +
        "To change part of an existing file, prefer Edit.",
    kind = "edit",
    access = Access.Edit,
) {
    override val inputSchema = schema {
        string("file_path", "Path of the file: absolute, or relative to the project folder", required = true)
        string("content", "The complete new contents of the file", required = true)
    }

    override fun title(input: JsonObject, ctx: ToolContext) = "Write ${targetFile(input, ctx)?.let(ctx::display) ?: "file"}"

    override fun targetFile(input: JsonObject, ctx: ToolContext): File? = input.str("file_path")?.let(ctx::resolve)

    override fun preview(input: JsonObject, ctx: ToolContext): FileChange? {
        val file = targetFile(input, ctx) ?: return null
        val old = try {
            if (file.isFile && file.length() <= MAX_FILE_BYTES && !looksBinary(file)) file.readText() else null
        } catch (e: Exception) {
            null
        }
        return FileChange(file.path, old, input.str("content") ?: return null)
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val file = ctx.resolve(input.str("file_path")!!)
        if (file.isDirectory) throw ToolFailure("${file.path} is a directory.")
        val existed = file.exists()
        file.parentFile?.mkdirs()
        file.writeText(input.str("content")!!)
        ToolResult(if (existed) "Replaced ${file.path}" else "Created ${file.path}")
    }
}

class EditTool : AgentTool(
    name = "Edit",
    description = "Replace one piece of text in a file with another. old_string must match the file exactly " +
        "(including indentation) and must be unique in the file, unless replace_all is true. " +
        "Read the file first so you know its exact text.",
    kind = "edit",
    access = Access.Edit,
) {
    override val inputSchema = schema {
        string("file_path", "Path of the file: absolute, or relative to the project folder", required = true)
        string("old_string", "The exact text to replace", required = true)
        string("new_string", "The text to put in its place", required = true)
        boolean("replace_all", "Replace every occurrence instead of requiring exactly one (default false)")
    }

    override fun title(input: JsonObject, ctx: ToolContext) = "Edit ${targetFile(input, ctx)?.let(ctx::display) ?: "file"}"

    override fun targetFile(input: JsonObject, ctx: ToolContext): File? = input.str("file_path")?.let(ctx::resolve)

    /** Work out the edited text without writing it. Shared by [preview] and [run]. */
    private fun plan(input: JsonObject, ctx: ToolContext): FileChange {
        val file = ctx.resolve(input.str("file_path")!!)
        val old = input.str("old_string")!!
        val new = input.str("new_string")!!
        if (old.isEmpty()) throw ToolFailure("old_string is empty. To create a file, use Write.")
        if (old == new) throw ToolFailure("old_string and new_string are the same; nothing to change.")

        val text = readText(file)
        val count = text.windowed(old.length, 1).count { it == old }
        if (count == 0) throw ToolFailure("old_string was not found in ${file.path}. It must match exactly, including whitespace.")
        if (count > 1 && input.bool("replace_all") != true) {
            throw ToolFailure("old_string appears $count times in ${file.path}. Add surrounding lines to make it unique, or set replace_all.")
        }
        return FileChange(file.path, text, text.replace(old, new))
    }

    override fun preview(input: JsonObject, ctx: ToolContext): FileChange? = try {
        plan(input, ctx)
    } catch (e: Exception) {
        null // run() will report the problem to the model
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val change = plan(input, ctx)
        File(change.path).writeText(change.newText)
        ToolResult("Edited ${change.path}")
    }
}
