package com.droidacp.core.diff

/**
 * Line-by-line diff for showing file edits (ACP sends `oldText` and `newText`).
 *
 * Uses the classic longest-common-subsequence table: `lcs[i][j]` is the length of
 * the LCS of `old[i..]` and `new[j..]`. Walking the table from the top-left gives
 * the shortest list of removed/added lines. It's O(n·m), so very large inputs fall
 * back to trimming the common start/end and showing the middle as replaced.
 */
object LineDiff {

    enum class Kind { Same, Added, Removed }

    data class Line(val kind: Kind, val text: String, val oldNumber: Int?, val newNumber: Int?)

    /** A run of unchanged lines that the UI may collapse. */
    data class Hunk(val lines: List<Line>, val collapsedBefore: Int)

    private const val MAX_CELLS = 400_000

    fun diff(oldText: String?, newText: String): List<Line> {
        val a = oldText?.let(::splitLines) ?: emptyList()
        val b = splitLines(newText)

        // Common prefix/suffix are cheap and keep the table small.
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }

        val out = ArrayList<Line>(a.size + b.size)
        for (i in 0 until start) out += Line(Kind.Same, a[i], i + 1, i + 1)

        val midA = a.subList(start, endA)
        val midB = b.subList(start, endB)
        if (midA.size.toLong() * midB.size <= MAX_CELLS) {
            lcsDiff(midA, midB, start, out)
        } else {
            midA.forEachIndexed { k, s -> out += Line(Kind.Removed, s, start + k + 1, null) }
            midB.forEachIndexed { k, s -> out += Line(Kind.Added, s, null, start + k + 1) }
        }

        for (k in 0 until a.size - endA) {
            val ia = endA + k
            val ib = endB + k
            out += Line(Kind.Same, a[ia], ia + 1, ib + 1)
        }
        return out
    }

    /**
     * Group lines for display: changed lines with [context] lines around them;
     * longer unchanged stretches are reported as `collapsedBefore` counts.
     */
    fun hunks(lines: List<Line>, context: Int = 3): List<Hunk> {
        if (lines.none { it.kind != Kind.Same }) return emptyList()
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, line ->
            if (line.kind != Kind.Same) {
                for (j in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(lines.lastIndex)) keep[j] = true
            }
        }
        val hunks = mutableListOf<Hunk>()
        var skipped = 0
        var current = mutableListOf<Line>()
        var currentSkipped = 0
        lines.forEachIndexed { i, line ->
            if (keep[i]) {
                if (current.isEmpty()) currentSkipped = skipped
                current += line
                skipped = 0
            } else {
                if (current.isNotEmpty()) {
                    hunks += Hunk(current, currentSkipped)
                    current = mutableListOf()
                }
                skipped++
            }
        }
        if (current.isNotEmpty()) hunks += Hunk(current, currentSkipped)
        return hunks
    }

    private fun lcsDiff(a: List<String>, b: List<String>, offset: Int, out: MutableList<Line>) {
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && a[i] == b[j] -> {
                    out += Line(Kind.Same, a[i], offset + i + 1, offset + j + 1)
                    i++
                    j++
                }
                // On a tie, show the removal first (like `git diff`).
                i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]) -> {
                    out += Line(Kind.Removed, a[i], offset + i + 1, null)
                    i++
                }
                else -> {
                    out += Line(Kind.Added, b[j], null, offset + j + 1)
                    j++
                }
            }
        }
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.replace("\r\n", "\n").split('\n')
        // A trailing newline doesn't create an extra empty line.
        return if (lines.last().isEmpty()) lines.dropLast(1) else lines
    }
}
