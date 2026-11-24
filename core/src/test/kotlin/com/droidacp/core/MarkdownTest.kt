package com.droidacp.core

import com.droidacp.core.markdown.Markdown
import com.droidacp.core.markdown.Markdown.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownTest {

    @Test
    fun blocks() {
        val md = """
            # Title
            Some *text* here
            and a second line.

            - one
            - [x] done
              continued
            1. first

            ```kotlin
            val x = 1
            ```

            > quoted

            | a | b |
            |---|---|
            | 1 | 2 |

            ---
        """.trimIndent()
        val blocks = Markdown.parseBlocks(md)
        assertEquals(Block.Heading(1, "Title"), blocks[0])
        assertEquals(Block.Paragraph("Some *text* here\nand a second line."), blocks[1])
        assertEquals(Block.ListItem(false, 0, 0, "one", null), blocks[2])
        assertEquals(Block.ListItem(false, 0, 0, "done continued", true), blocks[3])
        assertEquals(Block.ListItem(true, 1, 0, "first", null), blocks[4])
        assertEquals(Block.Code("kotlin", "val x = 1", true), blocks[5])
        assertEquals(Block.Quote("quoted"), blocks[6])
        assertEquals(Block.Table(listOf("a", "b"), listOf(listOf("1", "2"))), blocks[7])
        assertEquals(Block.Rule, blocks[8])
    }

    @Test
    fun unclosedCodeFenceWhileStreaming() {
        val blocks = Markdown.parseBlocks("Here:\n```js\nconsole.log(1)")
        val code = assertIs<Block.Code>(blocks[1])
        assertEquals("console.log(1)", code.code)
        assertEquals(false, code.closed)
    }

    @Test
    fun inlineStyles() {
        val spans = Markdown.parseInline("a **bold** b *it* `code` ~~gone~~ [link](https://x.y) snake_case_name")
        assertTrue(spans.any { it.text == "bold" && it.bold })
        assertTrue(spans.any { it.text == "it" && it.italic })
        assertTrue(spans.any { it.text == "code" && it.code })
        assertTrue(spans.any { it.text == "gone" && it.strike })
        assertTrue(spans.any { it.text == "link" && it.link == "https://x.y" })
        assertTrue(spans.any { it.text.contains("snake_case_name") && !it.italic })
    }

    @Test
    fun unclosedMarkersStayLiteral() {
        val spans = Markdown.parseInline("2 * 3 and **not closed")
        assertEquals("2 * 3 and **not closed", spans.joinToString("") { it.text })
    }
}
