package com.droidacp.core.people

import com.droidacp.core.chat.ChatItem
import com.droidacp.core.chat.ChatState

/**
 * One person in the strip above the message box: a PlasmaLLM tab's agent. [name] is the
 * tab's folder (as PlasmaLLM names its tabs), [activity] what the agent is doing ([Activity]).
 */
data class StripPerson(
    val sessionId: String,
    val cwd: String,
    val name: String,
    val agent: String?,
    val chatTitle: String?,
    val look: Look,
    val activity: String,
    val front: Boolean,
    /** What it's doing in a few words (a tool's title, say), when known. */
    val detail: String? = null,
) {
    val pose: String get() = Activity.poseFor(activity)

    /** For the floating label and TalkBack: "DroidACP (Claude): reading files". */
    val description: String
        get() = buildString {
            append(name)
            if (agent != null) append(" ($agent)")
            append(": ").append(Activity.describe(activity))
        }
}

/**
 * The people to show: one per PlasmaLLM tab with a chat (protocol 8), when there are two or
 * more. The chat on screen's pose comes from DroidACP's own view of it; the others' from
 * PlasmaLLM's `activity` (protocol 9), else working or idle. Until a tab says which agent it
 * runs, it's taken to be the same as this chat's. Its look is the one its agent chose
 * (protocol 10's `person`), else a default that stays the same for its folder.
 */
fun ChatState.stripPeople(people: PixelPeople = PixelPeople.default): List<StripPerson> {
    val tabs = plasmaLlm?.tabs?.filter { it.sessionId.isNotEmpty() } ?: return emptyList()
    if (tabs.size < 2) return emptyList()
    val ownAgent = agent?.name
    return tabs.map { tab ->
        val name = folderName(tab.cwd).ifEmpty { tab.title ?: "Agent" }
        val agentName = tab.agentName ?: ownAgent
        val shirt = people.shirtFor(agentName ?: tab.agentTitle)
        val isThis = tab.sessionId == sessionId
        StripPerson(
            sessionId = tab.sessionId,
            cwd = tab.cwd,
            name = name,
            agent = agentName?.let(::agentLabel) ?: tab.agentTitle,
            chatTitle = tab.title,
            look = people.defaultLook(tab.cwd.ifEmpty { tab.sessionId }, shirt, name.firstOrNull()).let { default ->
                tab.person?.let { p -> people.chosen(default, p.skin, p.hair, p.hairColor, p.accessories, p.letter) } ?: default
            },
            activity = when {
                isThis -> activity
                tab.activity != null -> Activity.known(tab.activity, tab.busy)
                tab.busy -> Activity.WORKING
                else -> Activity.IDLE
            },
            front = isThis,
            detail = if (isThis) runningTool() else tab.detail,
        )
    }
}

// The title of the tool running in this chat, if one is, shortened as PlasmaLLM does (80).
private fun ChatState.runningTool(): String? {
    val call = (items.lastOrNull() as? ChatItem.Tool)?.call ?: return null
    if (!busy || (call.status != "pending" && call.status != "in_progress")) return null
    return call.title.trim().lineSequence().firstOrNull()?.take(80)?.ifBlank { null }
}

private fun folderName(cwd: String): String = cwd.trimEnd('/').substringAfterLast('/')

// "@agentclientprotocol/claude-agent-acp" → "Claude"; anything else as it is.
private fun agentLabel(name: String): String {
    val n = name.lowercase()
    return listOf("claude", "gemini", "codex", "copilot", "qwen", "mistral", "deepseek", "opencode", "goose")
        .firstOrNull { it in n }?.replaceFirstChar { it.uppercase() } ?: name.substringAfterLast('/')
}
