package com.droidacp.core.acp

/** The shell command that starts `acp-bridge.js`, for the app's "Copy command" button. */
object BridgeCommand {
    const val CLAUDE_CODE = "npx -y @agentclientprotocol/claude-agent-acp"

    /** Characters a POSIX shell would treat specially in an unquoted word. */
    private const val SPECIAL = " \t\"'$`\\&;|<>()*?![]{}#~"

    /**
     * The command to run from the DroidACP folder so the agent works in [folder]
     * (or in the current folder, `.`, when [folder] is empty).
     */
    fun forFolder(folder: String, agent: String = CLAUDE_CODE): String =
        "node bridge/acp-bridge.js --cwd ${quote(folder.trim().ifEmpty { "." })} -- $agent"

    /**
     * Quote [word] for the shell only if it needs it. Inside single quotes nothing is
     * special, except a single quote itself, which is written as `'\''`: close the
     * quotes, add an escaped quote, open them again.
     */
    fun quote(word: String): String =
        if (word.none { it in SPECIAL }) word else "'" + word.replace("'", "'\\''") + "'"
}
