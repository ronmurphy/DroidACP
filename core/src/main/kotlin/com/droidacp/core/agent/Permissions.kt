package com.droidacp.core.agent

import com.droidacp.core.rpc.str
import kotlinx.serialization.json.JsonObject

/**
 * The session modes, named after Claude Code's permission modes. A mode decides
 * which tool calls run straight away and which stop to ask the user first.
 */
enum class AgentMode(val id: String, val title: String, val description: String) {
    Default("default", "Ask first", "Ask before editing files or running commands"),
    AcceptEdits("acceptEdits", "Accept edits", "Edit files in the project without asking; still ask before commands"),
    Plan("plan", "Plan", "Look around and make a plan, but change nothing"),
    Bypass("bypassPermissions", "Don't ask", "Run everything without asking"),
    ;

    companion object {
        fun fromId(id: String?): AgentMode? = entries.firstOrNull { it.id == id }
    }
}

/** What to do with one tool call. */
sealed interface Decision {
    data object Allow : Decision

    /** Refuse without asking; [reason] is what the model is told. */
    data class Deny(val reason: String) : Decision

    /**
     * Ask the user. If they choose "always allow", [rememberAs] is added to the
     * session's allow-list and [rememberLabel] is the button text for it.
     */
    data class Ask(val rememberAs: String?, val rememberLabel: String?) : Decision
}

object Permissions {
    /** Characters that let one command line run several commands or redirect output. */
    private val SHELL_OPERATORS = Regex("[;&|`\$<>(){}\\n]")

    /**
     * The first word of a simple command (`ls -la` gives `ls`), or null when the
     * command uses pipes, `&&`, redirection, `$(...)` and so on. "Always allow ls"
     * must not end up allowing `ls && rm -rf .`, so compound commands always ask.
     */
    fun commandWord(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || SHELL_OPERATORS.containsMatchIn(trimmed)) return null
        return trimmed.split(Regex("\\s+")).first()
    }

    /**
     * Decide whether [tool] may run with [input].
     *
     * @param allowed rules the user approved with "always allow" earlier in this session.
     */
    fun decide(tool: AgentTool, input: JsonObject, mode: AgentMode, allowed: Set<String>, ctx: ToolContext): Decision {
        if (mode == AgentMode.Bypass || tool.access == Access.None) return Decision.Allow

        val file = tool.targetFile(input, ctx)
        val inProject = file == null || ctx.isInsideProject(file)

        return when (tool.access) {
            Access.None -> Decision.Allow

            // Looking around inside the project is always fine. Outside it, ask.
            Access.Read -> when {
                inProject || RULE_READ_ANYWHERE in allowed -> Decision.Allow
                else -> Decision.Ask(RULE_READ_ANYWHERE, "Always allow reading outside the project")
            }

            Access.Edit -> when {
                mode == AgentMode.Plan -> Decision.Deny("Plan mode is on: files can't be changed. Describe the change in your plan instead.")
                !inProject -> Decision.Ask(null, null) // never remembered: each file outside the project is asked about
                mode == AgentMode.AcceptEdits || RULE_EDIT in allowed -> Decision.Allow
                else -> Decision.Ask(RULE_EDIT, "Always allow edits")
            }

            Access.Execute -> {
                val word = commandWord(input.str("command") ?: "")
                when {
                    mode == AgentMode.Plan -> Decision.Deny("Plan mode is on: commands can't be run. Describe the command in your plan instead.")
                    word != null && commandRule(word) in allowed -> Decision.Allow
                    word != null -> Decision.Ask(commandRule(word), "Always allow `$word` commands")
                    else -> Decision.Ask(null, null)
                }
            }
        }
    }

    const val RULE_EDIT = "edit"
    const val RULE_READ_ANYWHERE = "read-anywhere"

    fun commandRule(word: String) = "bash:$word"
}
