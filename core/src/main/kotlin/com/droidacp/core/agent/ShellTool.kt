package com.droidacp.core.agent

import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val DEFAULT_TIMEOUT_MS = 120_000L
private const val MAX_TIMEOUT_MS = 600_000L
private const val MAX_OUTPUT_CHARS = 200_000

/**
 * Runs a shell command in the project folder.
 *
 * On Android this is the system shell (`/system/bin/sh`) with the small set of
 * commands Android ships (toybox: ls, cat, grep, find, sed, ...). It runs as this
 * app, so it can only touch what the app is allowed to touch.
 */
class BashTool : AgentTool(
    name = "Bash",
    description = "Run a shell command in the project folder and return its output (stdout and stderr together). " +
        "Each command starts in a fresh shell: `cd` and variables do not carry over, so chain steps with &&. " +
        "Commands that wait for keyboard input will hang until the timeout. " +
        "Prefer the Read, Edit, Glob and Grep tools over cat, sed, find and grep.",
    kind = "execute",
    access = Access.Execute,
) {
    override val inputSchema = schema {
        string("command", "The command line to run", required = true)
        string("description", "A few words saying what the command does, shown to the user")
        integer("timeout", "Milliseconds to wait before stopping the command (default $DEFAULT_TIMEOUT_MS, max $MAX_TIMEOUT_MS)")
    }

    override fun title(input: JsonObject, ctx: ToolContext): String {
        val command = (input.str("command") ?: "").lineSequence().firstOrNull() ?: ""
        return "Run: " + if (command.length > 80) command.take(80) + "…" else command
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val command = input.str("command")!!
        val timeout = (input.long("timeout") ?: DEFAULT_TIMEOUT_MS).coerceIn(1000L, MAX_TIMEOUT_MS)

        val builder = ProcessBuilder(ctx.shell + command).directory(ctx.cwd).redirectErrorStream(true)
        builder.environment().putAll(ctx.shellEnv)
        val process = try {
            builder.start()
        } catch (e: Exception) {
            throw ToolFailure("Could not start the shell: ${e.message}")
        }
        process.outputStream.close() // nothing will be typed into it

        // Collect output on a separate thread, so a chatty command can't fill the pipe and stall.
        val output = StringBuilder()
        val reader = thread(isDaemon = true, name = "bash-output") {
            try {
                process.inputStream.bufferedReader().use { r ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = r.read(buffer)
                        if (n < 0) break
                        synchronized(output) { if (output.length < MAX_OUTPUT_CHARS) output.append(buffer, 0, n) }
                    }
                }
            } catch (e: Exception) {
                // The stream closes when the process is killed; that's fine.
            }
        }

        try {
            // runInterruptible lets "Stop" in the app interrupt the wait; `finally` then kills the command.
            val finished = runInterruptible { process.waitFor(timeout, TimeUnit.MILLISECONDS) }
            if (!finished) process.destroyForcibly()
            reader.join(1500)
            val text = synchronized(output) { output.toString() }.trimEnd()
            when {
                !finished -> ToolResult(clip(text) + "\n\n[Stopped: the command ran longer than ${timeout / 1000} seconds.]", isError = true)
                process.exitValue() != 0 -> ToolResult(clip(text) + "\n\n[Exit code ${process.exitValue()}]", isError = true)
                else -> ToolResult(clip(text).ifEmpty { "[The command finished with no output.]" })
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** Lets the model keep a visible to-do list for multi-step work. The app shows it as the plan bar. */
class TodoWriteTool : AgentTool(
    name = "TodoWrite",
    description = "Create or update your to-do list for the current task. Send the whole list every time. " +
        "Use it for work with three or more steps: mark one item in_progress before starting it and completed " +
        "as soon as it is done. Skip it for simple requests.",
    kind = "think",
    access = Access.None,
) {
    override val inputSchema = schema {
        array(
            "todos",
            "The complete, updated list",
            items = schema {
                string("content", "What needs doing", required = true)
                string("status", "Where it stands", required = true, enum = listOf("pending", "in_progress", "completed"))
            },
            required = true,
        )
    }

    override fun title(input: JsonObject, ctx: ToolContext) = "Update to-do list"

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
        val entries: JsonArray = buildJsonArray {
            for (todo in input.objects("todos")) {
                add(
                    buildJsonObject {
                        put("content", todo.str("content") ?: "")
                        put("priority", "medium")
                        put("status", todo.str("status")?.takeIf { it in setOf("pending", "in_progress", "completed") } ?: "pending")
                    },
                )
            }
        }
        return ToolResult("To-do list updated. Keep it current as you work.", plan = entries)
    }
}

/** Every tool the built-in agent has, by name. */
object Toolbox {
    val all: List<AgentTool> = listOf(ReadTool(), WriteTool(), EditTool(), GlobTool(), GrepTool(), BashTool(), TodoWriteTool())
    private val byName = all.associateBy { it.name }

    fun find(name: String): AgentTool? = byName[name]

    val definitions: List<JsonObject> = all.map { it.definition() }
}
