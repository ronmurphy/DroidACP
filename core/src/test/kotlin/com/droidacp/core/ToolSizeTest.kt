package com.droidacp.core

import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.acp.ToolContent
import com.droidacp.core.chat.ToolInput
import com.droidacp.core.chat.ToolSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolSizeTest {
    @Test
    fun aLongLineCountsAsSeveral() {
        assertEquals(3, ToolSize.lines("a\nb\nc"))
        assertEquals(1, ToolSize.lines(""))
        assertEquals(50 + 1, ToolSize.lines("x".repeat(5000) + "\nshort"))
    }

    @Test
    fun onlyVeryLongLinesAreCut() {
        val short = "fine\nlines"
        assertSame(short, ToolSize.clipLongLines(short))
        val cut = ToolSize.clipLongLines("x".repeat(2500) + "\nnext")
        assertEquals("x".repeat(2000) + " … (500 more characters)\nnext", cut)
        assertEquals("abc", ToolSize.clipLine("abc"))
    }

    @Test
    fun aCommandWithLongOutputIsLongAndAShortEditIsnt() {
        val command = ToolCall(
            id = "1", title = "Run tests", kind = "execute", rawInput = "./gradlew test",
            content = listOf(ToolContent.Block(ContentBlock.Text((1..30).joinToString("\n") { "line $it" }))),
        )
        assertEquals(31, ToolSize.detailLines(command))
        assertTrue(ToolSize.detailLines(command) > ToolSize.LONG_LINES)
        assertNull(ToolSize.diffStat(command))

        val edit = ToolCall(
            id = "2", title = "Edit a.kt", kind = "edit",
            content = listOf(ToolContent.Diff("/p/a.kt", "one\ntwo\nthree", "one\nTWO\nthree\nfour")),
        )
        assertTrue(ToolSize.detailLines(edit) <= ToolSize.LONG_LINES)
        assertEquals(ToolSize.DiffStat(added = 2, removed = 1), ToolSize.diffStat(edit))

        // A picture adds nothing; a new file's lines all count.
        val picture = ToolCall(id = "3", title = "Screenshot", content = listOf(ToolContent.Block(ContentBlock.Image("image/png", "", null))))
        assertEquals(0, ToolSize.detailLines(picture))
        val newFile = ToolCall(id = "4", title = "Write b.kt", kind = "edit", content = listOf(ToolContent.Diff("/p/b.kt", null, (1..40).joinToString("\n"))))
        assertTrue(ToolSize.detailLines(newFile) > ToolSize.LONG_LINES)
        assertEquals(ToolSize.DiffStat(added = 40, removed = 0), ToolSize.diffStat(newFile))
    }

    @Test
    fun aCommandShowsAsWrittenWithEveryOtherField() {
        val raw = """
            {
              "command": "python3 - <<'EOF'\nprint(\"hi\")\nEOF",
              "description": "Say hi",
              "timeout": 600000
            }
        """.trimIndent()
        val c = ToolInput.command(raw)!!
        assertEquals("python3 - <<'EOF'\nprint(\"hi\")\nEOF", c.command)
        assertEquals(3, c.command.lines().size)
        assertEquals("Say hi", c.description)
        assertEquals(listOf("timeout" to "600000"), c.other)

        // Not a command: shown as it came.
        assertNull(ToolInput.command("""{"path": "/a"}"""))
        assertNull(ToolInput.command("not json"))
        assertNull(ToolInput.commandOf("read", raw))

        // The card counts the lines it shows: the command's 3, its description and the other fields.
        val call = ToolCall(id = "5", title = "python3", kind = "execute", rawInput = raw)
        assertEquals(5, ToolSize.detailLines(call))
    }
}
