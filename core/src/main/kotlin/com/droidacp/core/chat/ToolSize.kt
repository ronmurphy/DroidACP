package com.droidacp.core.chat

import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.acp.ToolContent
import com.droidacp.core.diff.LineDiff

/**
 * How big a tool card's details are, so a long one can start closed (its header says how
 * long) and open full screen instead of filling the chat. Also the cut for lines too long
 * to show: one line of minified code can be tens of thousands of characters, which is no
 * use to read sideways and costly to lay out.
 */
object ToolSize {
    /** Details longer than this many lines start closed and open full screen. */
    const val LONG_LINES = 20

    /** A long line counts as one line per this many characters, roughly what a screen shows. */
    const val CHARS_PER_LINE = 100

    /** Longer lines are cut, and say how much is left out. */
    const val MAX_LINE_CHARS = 2000

    /** The lines [text] takes on screen, a long line counting as several. */
    fun lines(text: String): Int = text.split('\n').sumOf { lineCount(it.length) }

    /** [line], cut at [max] characters with a note of how many more there were. */
    fun clipLine(line: String, max: Int = MAX_LINE_CHARS): String =
        if (line.length <= max) line else line.take(max) + " … (${line.length - max} more characters)"

    /** [text] with every line over [max] characters cut (see [clipLine]). The same object when none is. */
    fun clipLongLines(text: String, max: Int = MAX_LINE_CHARS): String {
        if (text.length <= max || text.split('\n').none { it.length > max }) return text
        return text.split('\n').joinToString("\n") { clipLine(it, max) }
    }

    /** Lines added and removed across a card's file changes. */
    data class DiffStat(val added: Int, val removed: Int)

    /** Null when the call changed no files. */
    fun diffStat(call: ToolCall): DiffStat? {
        val diffs = call.content.filterIsInstance<ToolContent.Diff>()
        if (diffs.isEmpty()) return null
        var added = 0
        var removed = 0
        for (d in diffs) {
            for (line in LineDiff.diff(d.oldText, d.newText)) {
                when (line.kind) {
                    LineDiff.Kind.Added -> added++
                    LineDiff.Kind.Removed -> removed++
                    LineDiff.Kind.Same -> {}
                }
            }
        }
        return DiffStat(added, removed)
    }

    /**
     * About how many lines the card's details take when open, counting what the card
     * shows: its locations, the input (for commands, or when there's nothing else), each
     * content block (a picture counts as none), and the output when there's no content.
     */
    fun detailLines(call: ToolCall): Int {
        var n = call.locations.size
        val input = call.rawInput
        if (input != null && (call.content.isEmpty() || call.kind == "execute")) {
            // As the card shows it: a command as written, with its description and other fields.
            val command = ToolInput.commandOf(call.kind, input)
            n += if (command == null) {
                lines(input)
            } else {
                lines(command.command) + (if (command.description != null) 1 else 0) + (if (command.other.isNotEmpty()) 1 else 0)
            }
        }
        for (content in call.content) {
            n += when (content) {
                is ToolContent.Block -> when (val b = content.block) {
                    is ContentBlock.Text -> lines(b.text)
                    is ContentBlock.Resource -> lines(b.text ?: b.uri)
                    is ContentBlock.Image -> 0
                    else -> 1
                }
                // The file's name and path, then the lines shown (as the card caps them).
                is ToolContent.Diff -> 2 + diffLines(content)
                is ToolContent.Terminal, is ToolContent.SessionRef -> 1
            }
        }
        val output = call.rawOutput
        if (output != null && call.content.isEmpty()) n += lines(output)
        return n
    }

    private fun diffLines(diff: ToolContent.Diff): Int {
        var n = 0
        for (hunk in LineDiff.hunks(LineDiff.diff(diff.oldText, diff.newText))) {
            if (hunk.collapsedBefore > 0) n++
            for (line in hunk.lines) n += lineCount(line.text.length + 7)
            if (n > MAX_DIFF_COUNT) return n
        }
        return n
    }

    private fun lineCount(chars: Int) = maxOf(1, (chars + CHARS_PER_LINE - 1) / CHARS_PER_LINE)

    /** Counting stops here: past it, the card is long whatever the rest holds. */
    private const val MAX_DIFF_COUNT = 1000
}
