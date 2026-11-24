package com.droidacp.core

import com.droidacp.core.diff.LineDiff
import com.droidacp.core.diff.LineDiff.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class LineDiffTest {

    private fun render(lines: List<LineDiff.Line>) = lines.joinToString("\n") {
        when (it.kind) {
            Kind.Same -> "  ${it.text}"
            Kind.Added -> "+ ${it.text}"
            Kind.Removed -> "- ${it.text}"
        }
    }

    @Test
    fun replaceOneLine() {
        val d = LineDiff.diff("a\nb\nc\n", "a\nB\nc\n")
        assertEquals("  a\n- b\n+ B\n  c", render(d))
        assertEquals(listOf(1, 2, null, 3), d.map { it.oldNumber })
        assertEquals(listOf(1, null, 2, 3), d.map { it.newNumber })
    }

    @Test
    fun newFile() {
        val d = LineDiff.diff(null, "x\ny")
        assertEquals("+ x\n+ y", render(d))
    }

    @Test
    fun insertAndDelete() {
        val d = LineDiff.diff("1\n2\n3\n4", "1\n3\n4\n5")
        assertEquals("  1\n- 2\n  3\n  4\n+ 5", render(d))
    }

    @Test
    fun hunksCollapseFarAwayContext() {
        val old = (1..20).joinToString("\n") { "line $it" }
        val new = old.replace("line 10", "LINE TEN")
        val hunks = LineDiff.hunks(LineDiff.diff(old, new), context = 2)
        assertEquals(1, hunks.size)
        assertEquals(7, hunks[0].collapsedBefore)
        assertEquals(6, hunks[0].lines.size) // 2 context + removed + added + 2 context
    }
}
