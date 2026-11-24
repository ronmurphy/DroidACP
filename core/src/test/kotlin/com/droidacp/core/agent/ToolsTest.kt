package com.droidacp.core.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolsTest {
    private val dir: File = Files.createTempDirectory("droidacp-tools").toFile()
    private val ctx = ToolContext(dir, LocalAgentConfig.defaultShell())

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun run(tool: AgentTool, input: String): ToolResult = runBlocking { tool.run(json(input), ctx) }

    private fun file(path: String, text: String) = File(dir, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    @Test
    fun readNumbersLinesAndPages() {
        file("a.txt", (1..10).joinToString("\n") { "line $it" })
        val all = run(ReadTool(), """{"file_path":"a.txt"}""").text
        assertTrue(all.startsWith("     1\tline 1\n     2\tline 2"), all)
        val page = run(ReadTool(), """{"file_path":"a.txt","offset":4,"limit":2}""").text
        assertTrue(page.startsWith("     4\tline 4\n     5\tline 5\n\n[5 more lines"), page)
        assertFailsWith<ToolFailure> { run(ReadTool(), """{"file_path":"missing.txt"}""") }
    }

    @Test
    fun editNeedsAUniqueMatch() {
        val f = file("b.txt", "one two one")
        assertFailsWith<ToolFailure> { run(EditTool(), """{"file_path":"b.txt","old_string":"one","new_string":"1"}""") }
        assertFailsWith<ToolFailure> { run(EditTool(), """{"file_path":"b.txt","old_string":"nope","new_string":"1"}""") }
        assertEquals("one two one", f.readText(), "a failed edit must not change the file")
        run(EditTool(), """{"file_path":"b.txt","old_string":"two","new_string":"2"}""")
        run(EditTool(), """{"file_path":"b.txt","old_string":"one","new_string":"1","replace_all":true}""")
        assertEquals("1 2 1", f.readText())
    }

    @Test
    fun editPreviewShowsTheChangeWithoutMakingIt() {
        val f = file("c.txt", "abc")
        val change = EditTool().preview(json("""{"file_path":"c.txt","old_string":"b","new_string":"B"}"""), ctx)
        assertEquals(FileChange(f.path, "abc", "aBc"), change)
        assertEquals("abc", f.readText())
        assertNull(WriteTool().preview(json("""{"file_path":"new.txt","content":"x"}"""), ctx)!!.oldText)
    }

    @Test
    fun globAndGrepFindFiles() {
        file("Main.kt", "fun main() = println(\"hi\")")
        file("src/util/Text.kt", "// TODO tidy\nfun shout(s: String) = s.uppercase()")
        file("src/notes.md", "todo: nothing")
        file("build/Generated.kt", "// TODO generated")

        val kt = run(GlobTool(), """{"pattern":"**/*.kt"}""").text.lines().map { File(it).name }.toSet()
        assertEquals(setOf("Main.kt", "Text.kt"), kt, "top-level files match and build/ is skipped")
        assertEquals("No files found.", run(GlobTool(), """{"pattern":"*.java"}""").text)

        val files = run(GrepTool(), """{"pattern":"TODO"}""").text
        assertEquals(File(dir, "src/util/Text.kt").path, files)
        val content = run(GrepTool(), """{"pattern":"todo","case_insensitive":true,"output_mode":"content","glob":"*.md"}""").text
        assertEquals("${File(dir, "src/notes.md").path}:1: todo: nothing", content)
        assertFailsWith<ToolFailure> { run(GrepTool(), """{"pattern":"("}""") }
    }

    @Test
    fun bashReportsOutputExitCodeAndTimeout() {
        assertEquals("hi", run(BashTool(), """{"command":"echo hi"}""").text)
        assertEquals(dir.canonicalPath, File(run(BashTool(), """{"command":"pwd"}""").text).canonicalPath)
        val failed = run(BashTool(), """{"command":"echo oops >&2; exit 3"}""")
        assertTrue(failed.isError)
        assertEquals("oops\n\n[Exit code 3]", failed.text)
        val slow = run(BashTool(), """{"command":"sleep 30","timeout":1000}""")
        assertTrue(slow.isError && "ran longer than 1 seconds" in slow.text, slow.text)
    }

    @Test
    fun validateChecksRequiredFieldsAndTypes() {
        val read = ReadTool()
        assertNull(read.validate(json("""{"file_path":"a","limit":5}""")))
        assertEquals("Missing required parameter `file_path`", read.validate(json("""{"limit":5}""")))
        assertEquals("Parameter `limit` must be of type integer", read.validate(json("""{"file_path":"a","limit":"5"}""")))
        assertTrue(read.definition().toString().contains("\"eager_input_streaming\":true"))
    }

    @Test
    fun permissionRules() {
        fun decide(tool: AgentTool, input: String, mode: AgentMode, allowed: Set<String> = emptySet()) =
            Permissions.decide(tool, json(input), mode, allowed, ctx)

        val inside = """{"file_path":"x.txt","content":""}"""
        val outside = """{"file_path":"/etc/hosts","content":""}"""

        assertIs<Decision.Allow>(decide(ReadTool(), inside, AgentMode.Default))
        assertIs<Decision.Ask>(decide(ReadTool(), outside, AgentMode.Default))
        assertIs<Decision.Allow>(decide(ReadTool(), """{"file_path":"../${dir.name}/x.txt"}""", AgentMode.Plan))
        assertIs<Decision.Ask>(decide(ReadTool(), """{"file_path":"../elsewhere/x.txt"}""", AgentMode.Default))

        assertIs<Decision.Ask>(decide(WriteTool(), inside, AgentMode.Default))
        assertIs<Decision.Allow>(decide(WriteTool(), inside, AgentMode.AcceptEdits))
        assertIs<Decision.Allow>(decide(WriteTool(), inside, AgentMode.Default, setOf(Permissions.RULE_EDIT)))
        assertIs<Decision.Deny>(decide(WriteTool(), inside, AgentMode.Plan))
        // Outside the project, edits always ask, and can't be remembered.
        val out = decide(WriteTool(), outside, AgentMode.AcceptEdits, setOf(Permissions.RULE_EDIT))
        assertNull(assertIs<Decision.Ask>(out).rememberAs)
        assertIs<Decision.Allow>(decide(WriteTool(), outside, AgentMode.Bypass))

        assertEquals("bash:ls", assertIs<Decision.Ask>(decide(BashTool(), """{"command":"ls -la"}""", AgentMode.AcceptEdits)).rememberAs)
        assertIs<Decision.Allow>(decide(BashTool(), """{"command":"ls src"}""", AgentMode.Default, setOf("bash:ls")))
        assertNotNull(decide(BashTool(), """{"command":"ls; rm -rf ."}""", AgentMode.Default, setOf("bash:ls")) as? Decision.Ask)
        assertIs<Decision.Deny>(decide(BashTool(), """{"command":"ls"}""", AgentMode.Plan))

        assertEquals("git", Permissions.commandWord("  git status "))
        for (sneaky in listOf("ls && rm x", "ls | sh", "echo \$(rm x)", "ls > f", "a\nb", "echo `id`")) {
            assertNull(Permissions.commandWord(sneaky), sneaky)
        }
        assertFalse(ctx.isInsideProject(File(dir.path + "-other")))
    }
}
