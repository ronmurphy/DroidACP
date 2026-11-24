package com.droidacp.core.agent

import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext

// Glob and Grep: finding files by name and by content.
//
// On a computer a coding agent would shell out to `find` and `ripgrep`. Android has
// neither reliably, so both are written here in plain Kotlin on top of java.nio.

private const val MAX_RESULTS = 200
private const val MAX_GREP_FILE_BYTES = 2L * 1024 * 1024

/** Folders that are never worth searching. */
private val SKIPPED_DIRS = setOf(".git", ".gradle", "node_modules", "build", ".idea")

// Matches paths (relative to the search root) against a glob such as "src/[star][star]/[star].kt",
// where [star] stands for the asterisk character (writing the real thing here would
// end this comment early, since Kotlin comments nest).
//
// In java.nio's glob a leading double-star-slash needs at least one folder, so it would
// miss a file at the top of the search root. People expect it to match, so we also
// try the pattern with that prefix removed.
internal class GlobMatcher(pattern: String) {
    private val matchers: List<PathMatcher> = buildList {
        val fs = FileSystems.getDefault()
        add(fs.getPathMatcher("glob:$pattern"))
        if (pattern.startsWith("**/")) add(fs.getPathMatcher("glob:" + pattern.removePrefix("**/")))
        // A bare name like "*.kt" should match in any folder, as it does in most tools.
        if ('/' !in pattern) add(fs.getPathMatcher("glob:**/$pattern"))
    }

    fun matches(relative: Path): Boolean = matchers.any { it.matches(relative) }
}

/** Visit every regular file below [root], skipping [SKIPPED_DIRS] and anything unreadable. */
private fun walkFiles(root: Path, visit: (file: Path, attrs: BasicFileAttributes) -> Boolean) {
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.fileName?.toString() in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (attrs.isRegularFile && !visit(file, attrs)) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
}

class GlobTool : AgentTool(
    name = "Glob",
    description = "Find files by name pattern, e.g. \"**/*.kt\" or \"src/**/*.xml\". Returns matching paths, " +
        "most recently modified first. Use \"*\" to list a folder. Skips .git, node_modules and build folders.",
    kind = "search",
    access = Access.Read,
) {
    override val inputSchema = schema {
        string("pattern", "Glob pattern, matched against paths relative to the search folder", required = true)
        string("path", "Folder to search in (default: the project folder)")
    }

    override fun title(input: JsonObject, ctx: ToolContext) = "Find files: ${input.str("pattern") ?: ""}"

    override fun targetFile(input: JsonObject, ctx: ToolContext): File = input.str("path")?.let(ctx::resolve) ?: ctx.cwd

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val root = targetFile(input, ctx)
        if (!root.isDirectory) throw ToolFailure("Not a folder: ${root.path}")
        val matcher = try {
            GlobMatcher(input.str("pattern")!!)
        } catch (e: Exception) {
            throw ToolFailure("Invalid glob pattern: ${e.message}")
        }
        val job = coroutineContext
        val found = mutableListOf<Pair<Path, Long>>()
        walkFiles(root.toPath()) { file, attrs ->
            job.ensureActive()
            if (matcher.matches(root.toPath().relativize(file))) found += file to attrs.lastModifiedTime().toMillis()
            found.size < 5000
        }
        if (found.isEmpty()) return@withContext ToolResult("No files found.")
        val sorted = found.sortedByDescending { it.second }
        val listed = sorted.take(MAX_RESULTS).joinToString("\n") { it.first.toString() }
        val more = if (sorted.size > MAX_RESULTS) "\n[${sorted.size - MAX_RESULTS} more not shown. Use a narrower pattern.]" else ""
        ToolResult(listed + more, display = "${found.size} files\n\n$listed")
    }
}

class GrepTool : AgentTool(
    name = "Grep",
    description = "Search file contents with a regular expression. output_mode \"files_with_matches\" (default) lists " +
        "the files, \"content\" shows matching lines as path:line: text, \"count\" shows how many matches per file. " +
        "Skips binary files, very large files, and .git, node_modules and build folders.",
    kind = "search",
    access = Access.Read,
) {
    override val inputSchema = schema {
        string("pattern", "Regular expression to search for", required = true)
        string("path", "File or folder to search in (default: the project folder)")
        string("glob", "Only search files whose path matches this glob, e.g. \"*.kt\"")
        string("output_mode", "What to return", enum = listOf("files_with_matches", "content", "count"))
        boolean("case_insensitive", "Ignore upper/lower case (default false)")
        integer("head_limit", "Maximum number of result lines (default $MAX_RESULTS)")
    }

    override fun title(input: JsonObject, ctx: ToolContext) = "Search for \"${input.str("pattern") ?: ""}\""

    override fun targetFile(input: JsonObject, ctx: ToolContext): File = input.str("path")?.let(ctx::resolve) ?: ctx.cwd

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val root = targetFile(input, ctx)
        if (!root.exists()) throw ToolFailure("Path does not exist: ${root.path}")
        val regex = try {
            Regex(input.str("pattern")!!, if (input.bool("case_insensitive") == true) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: Exception) {
            throw ToolFailure("Invalid regular expression: ${e.message}")
        }
        val filter = input.str("glob")?.let {
            try {
                GlobMatcher(it)
            } catch (e: Exception) {
                throw ToolFailure("Invalid glob: ${e.message}")
            }
        }
        val mode = input.str("output_mode") ?: "files_with_matches"
        val limit = (input.long("head_limit") ?: MAX_RESULTS.toLong()).coerceIn(1L, 2000L).toInt()
        val job = coroutineContext
        val out = mutableListOf<String>()
        var truncated = false

        fun search(file: Path): Boolean {
            job.ensureActive()
            val f = file.toFile()
            if (f.length() > MAX_GREP_FILE_BYTES || looksBinary(f)) return true
            val lines = try {
                f.readLines()
            } catch (e: Exception) {
                return true
            }
            var count = 0
            for ((index, line) in lines.withIndex()) {
                if (!regex.containsMatchIn(line)) continue
                count++
                if (mode == "files_with_matches") break
                if (mode == "content") {
                    if (out.size >= limit) {
                        truncated = true
                        return false
                    }
                    out += "$file:${index + 1}: ${line.take(500)}"
                }
            }
            if (count > 0 && mode != "content") {
                if (out.size >= limit) {
                    truncated = true
                    return false
                }
                out += if (mode == "count") "$file: $count" else file.toString()
            }
            return true
        }

        if (root.isFile) {
            search(root.toPath())
        } else {
            walkFiles(root.toPath()) { file, _ ->
                if (filter == null || filter.matches(root.toPath().relativize(file))) search(file) else true
            }
        }
        if (out.isEmpty()) return@withContext ToolResult("No matches found.")
        val text = out.joinToString("\n") + if (truncated) "\n[More results not shown. Narrow the search or raise head_limit.]" else ""
        ToolResult(clip(text), display = clip(text, 6000))
    }
}
