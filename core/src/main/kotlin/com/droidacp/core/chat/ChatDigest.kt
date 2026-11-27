package com.droidacp.core.chat

import com.droidacp.core.acp.PermissionOption
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.tablet.TabletActions

/** Readable tool names: `mcp__plasmallm__read_log` becomes "read log · plasmallm", as PlasmaLLM shows it. */
object ToolNames {
    // mcp__<server>__<tool>; the server is everything up to the first "__".
    private val mcp = Regex("mcp__(.+?)__(\\S+)")

    fun display(title: String): String {
        val match = mcp.matchEntire(title.trim()) ?: return title
        val (server, tool) = match.destructured
        return "${tool.replace('_', ' ').trim()} · $server"
    }
}

/** The card's title: a tablet action in words (`Tap “Send”`, plan D12), else the tool's readable name. */
val ToolCall.displayTitle: String get() = TabletActions.describe(title, rawInput) ?: ToolNames.display(title)

/**
 * The tablet action this chat's agent is doing now, in words (`Tap “Send”`), for the
 * notification: the newest tablet action still running among the last [RECENT_ITEMS] items,
 * as a note or another tool's card can come after it. Null when none is.
 */
val ChatState.tabletAction: String?
    get() {
        if (!busy) return null
        for (item in items.asReversed().asSequence().take(RECENT_ITEMS)) {
            val call = (item as? ChatItem.Tool)?.call ?: continue
            if (call.status != "pending" && call.status != "in_progress") continue
            TabletActions.describe(call.title, call.rawInput)?.let { return it }
        }
        return null
    }

private const val RECENT_ITEMS = 20

/**
 * The conversation as short lines of plain text, for the home-screen widget: what you
 * asked, the agent's replies, one line per tool call, and notes. Thoughts are left out,
 * and a long run of tool calls is cut to its last few.
 */
object ChatDigest {
    enum class Kind { User, Agent, Tool, Note, Problem }

    data class Row(val kind: Kind, val text: String)

    /** Tool calls in a row that are shown before the earlier ones are counted instead. */
    const val TOOLS_SHOWN = 3

    fun rows(items: List<ChatItem>, max: Int = 40): List<Row> {
        val rows = ArrayList<Row>()
        val tools = ArrayList<Row>()
        fun flushTools() {
            if (tools.size > TOOLS_SHOWN) {
                val hidden = tools.size - TOOLS_SHOWN
                rows += Row(Kind.Tool, "+$hidden more tool ${if (hidden == 1) "call" else "calls"}")
            }
            rows += tools.takeLast(TOOLS_SHOWN)
            tools.clear()
        }
        // The newest part is all the widget can show; don't turn a long chat into text every time.
        for (item in items.takeLast(max * 4)) {
            when (item) {
                is ChatItem.Tool -> tools += Row(Kind.Tool, "${toolMark(item.call.status)} ${item.call.displayTitle}")
                is ChatItem.Thought -> Unit
                else -> {
                    val row = rowOf(item) ?: continue
                    flushTools()
                    rows += row
                }
            }
        }
        flushTools()
        return rows.takeLast(max)
    }

    private fun rowOf(item: ChatItem): Row? = when (item) {
        is ChatItem.User -> if (item.agentNote) null else textOrAttachments(item.text.trim().take(USER_CHARS), item.attachments.size)?.let { Row(Kind.User, it) }
        is ChatItem.Agent -> textOrAttachments(plainText(item.text, AGENT_CHARS), item.attachments.size)?.let { Row(Kind.Agent, it) }
        is ChatItem.Note -> item.text.trim().takeIf { it.isNotEmpty() }?.let {
            Row(if (item.severity == ChatItem.Severity.Info) Kind.Note else Kind.Problem, it.take(USER_CHARS))
        }
        is ChatItem.Tool, is ChatItem.Thought -> null
    }

    private fun textOrAttachments(text: String, attachments: Int): String? = when {
        text.isNotEmpty() -> text
        attachments == 1 -> "🖼 An image"
        attachments > 1 -> "🖼 $attachments images"
        else -> null
    }

    private fun toolMark(status: String): String = when (status) {
        "completed" -> "✓"
        "failed" -> "✗"
        else -> "…"
    }

    /** Markdown → plain text, roughly: the widget can't draw formatting. */
    fun plainText(markdown: String, maxChars: Int = AGENT_CHARS): String =
        markdown.take(maxChars + 500)
            .replace(Regex("```[a-zA-Z0-9]*"), "")
            .replace(Regex("[*_`#>]"), "")
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .let { if (it.length > maxChars) it.take(maxChars).trimEnd() + "…" else it }

    /**
     * The two answers a permission question gets on the home screen: allow once and
     * reject once (or the nearest the agent offers). The rest are in the app.
     */
    fun quickAnswers(options: List<PermissionOption>): Pair<PermissionOption?, PermissionOption?> {
        val allow = options.firstOrNull { it.kind == "allow_once" } ?: options.firstOrNull { it.isAllow }
        val deny = options.firstOrNull { it.kind == "reject_once" } ?: options.firstOrNull { !it.isAllow }
        return allow to deny
    }

    private const val USER_CHARS = 1000
    private const val AGENT_CHARS = 2000
}
