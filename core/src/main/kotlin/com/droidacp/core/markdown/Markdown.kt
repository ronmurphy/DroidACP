package com.droidacp.core.markdown

/**
 * A deliberately small Markdown parser — enough for what coding agents write:
 * headings, paragraphs, fenced code, bullet/numbered/task lists, quotes, tables,
 * horizontal rules, and inline **bold**, *italic*, `code`, ~~strike~~ and [links](url).
 *
 * It is forgiving by design: text is being streamed in, so half-written markup
 * (an unclosed ``` or **) must still render sensibly. Parsing is two steps:
 * [parseBlocks] splits lines into blocks, [parseInline] splits a block's text into
 * styled spans. The Android side turns those into Compose `AnnotatedString`s.
 */
object Markdown {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class Code(val language: String?, val code: String, val closed: Boolean) : Block
        data class Quote(val text: String) : Block
        data class ListItem(val ordered: Boolean, val number: Int, val depth: Int, val text: String, val checked: Boolean?) : Block
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block
        data object Rule : Block
    }

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    )

    private val headingRe = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val fenceRe = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([^`\\s]*)")
    private val bulletRe = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val orderedRe = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val taskRe = Regex("^\\[([ xX])]\\s+(.*)$")
    private val ruleRe = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSepRe = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parseBlocks(markdown: String): List<Block> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(paragraph.toString().trimEnd())
                paragraph.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block: everything until the matching fence (or end of text).
            val fence = fenceRe.find(line)
            if (fence != null) {
                flushParagraph()
                val marker = fence.groupValues[1]
                val language = fence.groupValues[2].ifBlank { null }
                val code = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.trimStart().startsWith(marker) && l.trim().all { it == marker[0] }) {
                        closed = true
                        break
                    }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(l)
                    i++
                }
                blocks += Block.Code(language, code.toString(), closed)
                i++
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
                i++
                continue
            }

            val heading = headingRe.find(line)
            if (heading != null) {
                flushParagraph()
                blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
                i++
                continue
            }

            if (ruleRe.matches(line)) {
                flushParagraph()
                blocks += Block.Rule
                i++
                continue
            }

            // Table: a header row followed by a |---|---| separator row.
            if (line.contains('|') && i + 1 < lines.size && tableSepRe.matches(lines[i + 1])) {
                flushParagraph()
                val header = splitRow(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i])
                    i++
                }
                blocks += Block.Table(header, rows)
                continue
            }

            if (line.trimStart().startsWith(">")) {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                blocks += Block.Quote(quote.toString())
                continue
            }

            val bullet = bulletRe.find(line)
            val ordered = if (bullet == null) orderedRe.find(line) else null
            if (bullet != null || ordered != null) {
                flushParagraph()
                val indent = (bullet ?: ordered)!!.groupValues[1].replace("\t", "    ").length
                var text = if (bullet != null) bullet.groupValues[2] else ordered!!.groupValues[3]
                var checked: Boolean? = null
                taskRe.find(text)?.let {
                    checked = it.groupValues[1] != " "
                    text = it.groupValues[2]
                }
                // Lazy continuation lines (indented, not a new item) belong to this item.
                i++
                while (i < lines.size && lines[i].isNotBlank() && lines[i].startsWith("  ") &&
                    bulletRe.find(lines[i]) == null && orderedRe.find(lines[i]) == null && fenceRe.find(lines[i]) == null
                ) {
                    text += " " + lines[i].trim()
                    i++
                }
                blocks += Block.ListItem(
                    ordered = ordered != null,
                    number = ordered?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                    depth = indent / 2,
                    text = text,
                    checked = checked,
                )
                continue
            }

            // Plain paragraph text; single newlines are kept as line breaks.
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line.trim())
            i++
        }
        flushParagraph()
        return blocks
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        return t.split('|').map { it.trim() }
    }

    /** Split one block's text into styled spans. Unclosed markers are shown literally. */
    fun parseInline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        parseInlineInto(text, Style(), spans)
        return mergeAdjacent(spans)
    }

    private data class Style(val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false, val link: String? = null)

    private fun parseInlineInto(text: String, style: Style, out: MutableList<Span>) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out += Span(plain.toString(), style.bold, style.italic, false, style.strike, style.link)
                plain.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]

            // Backslash escapes: \* \_ \` etc.
            if (c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
                plain.append(text[i + 1])
                i += 2
                continue
            }

            // `inline code` (also ``code with ` inside``)
            if (c == '`') {
                var ticks = 0
                while (i + ticks < text.length && text[i + ticks] == '`') ticks++
                val fence = "`".repeat(ticks)
                val end = text.indexOf(fence, i + ticks)
                if (end > 0) {
                    flush()
                    out += Span(text.substring(i + ticks, end).trim(), style.bold, style.italic, true, style.strike, style.link)
                    i = end + ticks
                    continue
                }
                plain.append(fence)
                i += ticks
                continue
            }

            // [label](url)
            if (c == '[') {
                val close = findClosingBracket(text, i)
                if (close > 0 && close + 1 < text.length && text[close + 1] == '(') {
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd > 0) {
                        flush()
                        val url = text.substring(close + 2, urlEnd).trim().substringBefore(' ')
                        parseInlineInto(text.substring(i + 1, close), style.copy(link = url), out)
                        i = urlEnd + 1
                        continue
                    }
                }
            }

            // **bold** / __bold__
            if ((c == '*' || c == '_') && text.startsWith("$c$c", i)) {
                val marker = "$c$c"
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2 && !text[i + 2].isWhitespace()) {
                    flush()
                    parseInlineInto(text.substring(i + 2, end), style.copy(bold = true), out)
                    i = end + 2
                    continue
                }
            }

            // ~~strike~~
            if (c == '~' && text.startsWith("~~", i)) {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    parseInlineInto(text.substring(i + 2, end), style.copy(strike = true), out)
                    i = end + 2
                    continue
                }
            }

            // *italic* / _italic_  (underscores only at word boundaries, so snake_case stays intact)
            if (c == '*' || c == '_') {
                val prevIsWord = i > 0 && text[i - 1].isLetterOrDigit()
                val nextOk = i + 1 < text.length && !text[i + 1].isWhitespace() && text[i + 1] != c
                if (nextOk && !(c == '_' && prevIsWord)) {
                    var end = text.indexOf(c, i + 1)
                    while (end > 0 && c == '_' && end + 1 < text.length && text[end + 1].isLetterOrDigit()) {
                        end = text.indexOf(c, end + 1)
                    }
                    if (end > i + 1 && !text[end - 1].isWhitespace()) {
                        flush()
                        parseInlineInto(text.substring(i + 1, end), style.copy(italic = true), out)
                        i = end + 1
                        continue
                    }
                }
            }

            // Bare URLs become links.
            if ((c == 'h') && (text.startsWith("https://", i) || text.startsWith("http://", i)) && style.link == null) {
                var end = i
                while (end < text.length && !text[end].isWhitespace() && text[end] !in ")>]") end++
                while (end > i && text[end - 1] in ".,;:!?") end--
                flush()
                val url = text.substring(i, end)
                out += Span(url, style.bold, style.italic, false, style.strike, url)
                i = end
                continue
            }

            plain.append(c)
            i++
        }
        flush()
    }

    private fun findClosingBracket(text: String, open: Int): Int {
        var depth = 0
        for (j in open until text.length) {
            when (text[j]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
        }
        return -1
    }

    private fun mergeAdjacent(spans: List<Span>): List<Span> {
        if (spans.size < 2) return spans
        val merged = ArrayList<Span>(spans.size)
        for (s in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == s.copy(text = "")) {
                merged[merged.size - 1] = last.copy(text = last.text + s.text)
            } else {
                merged += s
            }
        }
        return merged
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"
}
