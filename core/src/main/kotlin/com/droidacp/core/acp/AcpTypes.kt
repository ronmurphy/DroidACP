package com.droidacp.core.acp

import com.droidacp.core.rpc.arr
import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.double
import com.droidacp.core.rpc.has
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.pretty
import com.droidacp.core.rpc.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// Kotlin models for the parts of ACP v1 (https://agentclientprotocol.com) that the
// app shows. Each `parse` function reads a JSON object defensively and never throws
// on unexpected input — unknown things become `Unknown`/`Other` or are skipped.

/** Name/version of the agent, from `initialize`. */
data class Implementation(val name: String, val title: String?, val version: String?) {
    val displayName: String get() = title ?: name

    companion object {
        fun parse(o: JsonObject?): Implementation? {
            if (o == null) return null
            val name = o.str("name") ?: return null
            return Implementation(name, o.str("title"), o.str("version"))
        }
    }
}

data class AgentCapabilities(
    val loadSession: Boolean = false,
    val listSessions: Boolean = false,
    val resumeSession: Boolean = false,
    val closeSession: Boolean = false,
    val images: Boolean = false,
    val embeddedContext: Boolean = false,
) {
    companion object {
        fun parse(o: JsonObject?): AgentCapabilities {
            if (o == null) return AgentCapabilities()
            val prompt = o.obj("promptCapabilities")
            val session = o.obj("sessionCapabilities")
            return AgentCapabilities(
                loadSession = o.bool("loadSession") ?: false,
                listSessions = session?.has("list") ?: false,
                resumeSession = session?.has("resume") ?: false,
                closeSession = session?.has("close") ?: false,
                images = prompt?.bool("image") ?: false,
                embeddedContext = prompt?.bool("embeddedContext") ?: false,
            )
        }
    }
}

/**
 * A way to sign in. `agent` methods are handled by the agent itself when the client
 * calls `authenticate`; `terminal` methods expect the user to run a command (which we
 * can't do from Android, so we explain that instead).
 */
data class AuthMethod(val id: String, val name: String, val description: String?, val type: String) {
    companion object {
        fun parse(o: JsonObject): AuthMethod? {
            val id = o.str("id") ?: return null
            return AuthMethod(id, o.str("name") ?: id, o.str("description"), o.str("type") ?: "agent")
        }
    }
}

data class InitializeResult(
    val protocolVersion: Long,
    val agent: Implementation?,
    val capabilities: AgentCapabilities,
    val authMethods: List<AuthMethod>,
    /** Set when the other end is PlasmaLLM (its `_meta.plasmallm`). */
    val plasmaLlm: PlasmaHello? = null,
)

data class SessionMode(val id: String, val name: String, val description: String?)

data class SelectChoice(val value: String, val name: String, val description: String?, val group: String?)

/** Session configuration options (model picker, reasoning level, toggles, ...). */
sealed interface ConfigOption {
    val id: String
    val name: String
    val description: String?
    val category: String?

    data class Select(
        override val id: String,
        override val name: String,
        override val description: String?,
        override val category: String?,
        val currentValue: String,
        val choices: List<SelectChoice>,
    ) : ConfigOption {
        val currentName: String get() = choices.firstOrNull { it.value == currentValue }?.name ?: currentValue
    }

    data class Toggle(
        override val id: String,
        override val name: String,
        override val description: String?,
        override val category: String?,
        val currentValue: Boolean,
    ) : ConfigOption

    companion object {
        fun parseList(array: List<JsonObject>): List<ConfigOption> = array.mapNotNull { parse(it) }

        fun parse(o: JsonObject): ConfigOption? {
            val id = o.str("id") ?: return null
            val name = o.str("name") ?: id
            val description = o.str("description")
            val category = o.str("category")
            return when (o.str("type")) {
                "select" -> {
                    val choices = mutableListOf<SelectChoice>()
                    for (entry in (o["options"] as? JsonArray).orEmpty()) {
                        val item = entry as? JsonObject ?: continue
                        if (item.has("group")) {
                            // Grouped options: { group, name, options: [...] }
                            val groupName = item.str("name")
                            for (inner in item.objects("options")) {
                                val value = inner.str("value") ?: continue
                                choices += SelectChoice(value, inner.str("name") ?: value, inner.str("description"), groupName)
                            }
                        } else {
                            val value = item.str("value") ?: continue
                            choices += SelectChoice(value, item.str("name") ?: value, item.str("description"), null)
                        }
                    }
                    Select(id, name, description, category, o.str("currentValue") ?: "", choices)
                }
                "boolean" -> Toggle(id, name, description, category, o.bool("currentValue") ?: false)
                else -> null
            }
        }
    }
}

/** Everything the agent tells us when a session is created, loaded or resumed. */
data class SessionSetup(
    val sessionId: String,
    val modes: List<SessionMode>,
    val currentModeId: String?,
    val configOptions: List<ConfigOption>?,
) {
    companion object {
        fun parse(sessionId: String, o: JsonObject?): SessionSetup {
            val modes = o?.obj("modes")
            return SessionSetup(
                sessionId = sessionId,
                modes = modes?.objects("availableModes")?.mapNotNull { m ->
                    val id = m.str("id") ?: return@mapNotNull null
                    SessionMode(id, m.str("name") ?: id, m.str("description"))
                } ?: emptyList(),
                currentModeId = modes?.str("currentModeId"),
                configOptions = o?.arr("configOptions")?.let { arr -> ConfigOption.parseList(arr.mapNotNull { it as? JsonObject }) },
            )
        }
    }
}

/** An entry from `session/list`. */
data class SessionSummary(val sessionId: String, val cwd: String, val title: String?, val updatedAt: String?)

/** A slash command the agent supports, e.g. `/review`. */
data class AvailableCommand(val name: String, val description: String, val inputHint: String?)

// ---------------------------------------------------------------------------
// Content blocks
// ---------------------------------------------------------------------------

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Image(val mimeType: String, val data: String, val uri: String?) : ContentBlock
    data class Audio(val mimeType: String) : ContentBlock
    data class ResourceLink(val uri: String, val name: String, val title: String?) : ContentBlock
    data class Resource(val uri: String, val text: String?, val mimeType: String?) : ContentBlock
    data class Other(val type: String) : ContentBlock

    companion object {
        fun parse(o: JsonObject?): ContentBlock {
            if (o == null) return Other("missing")
            return when (val type = o.str("type")) {
                "text" -> Text(o.str("text") ?: "")
                "image" -> Image(o.str("mimeType") ?: "image/png", o.str("data") ?: "", o.str("uri"))
                "audio" -> Audio(o.str("mimeType") ?: "audio/*")
                "resource_link" -> ResourceLink(o.str("uri") ?: "", o.str("name") ?: o.str("uri") ?: "file", o.str("title"))
                "resource" -> {
                    val r = o.obj("resource")
                    Resource(r?.str("uri") ?: "", r?.str("text"), r?.str("mimeType"))
                }
                else -> Other(type ?: "unknown")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tool calls
// ---------------------------------------------------------------------------

sealed interface ToolContent {
    data class Block(val block: ContentBlock) : ToolContent
    data class Diff(val path: String, val oldText: String?, val newText: String) : ToolContent
    data class Terminal(val terminalId: String) : ToolContent
    data class SessionRef(val sessionId: String) : ToolContent

    companion object {
        fun parseList(items: List<JsonObject>): List<ToolContent> = items.mapNotNull { c ->
            when (c.str("type")) {
                "content" -> Block(ContentBlock.parse(c.obj("content")))
                "diff" -> Diff(c.str("path") ?: "", c.str("oldText"), c.str("newText") ?: "")
                "terminal" -> Terminal(c.str("terminalId") ?: "")
                "session" -> SessionRef(c.str("sessionId") ?: "")
                else -> null
            }
        }
    }
}

data class ToolLocation(val path: String, val line: Long?)

/**
 * The current state of one tool call. `tool_call` creates it; `tool_call_update`
 * patches it — every field in a [ToolCallPatch] that is non-null replaces ours.
 */
data class ToolCall(
    val id: String,
    val title: String,
    val kind: String = "other",
    val status: String = "pending",
    val content: List<ToolContent> = emptyList(),
    val locations: List<ToolLocation> = emptyList(),
    val rawInput: String? = null,
    val rawOutput: String? = null,
) {
    fun apply(patch: ToolCallPatch): ToolCall = copy(
        title = patch.title ?: title,
        kind = patch.kind ?: kind,
        status = patch.status ?: status,
        content = patch.content ?: content,
        locations = patch.locations ?: locations,
        rawInput = patch.rawInput ?: rawInput,
        rawOutput = patch.rawOutput ?: rawOutput,
    )

    val isFinished: Boolean get() = status == "completed" || status == "failed"
}

data class ToolCallPatch(
    val id: String,
    val title: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val content: List<ToolContent>? = null,
    val locations: List<ToolLocation>? = null,
    val rawInput: String? = null,
    val rawOutput: String? = null,
) {
    fun toToolCall(): ToolCall = ToolCall(id = id, title = title ?: "Tool call").apply(this)

    companion object {
        fun parse(o: JsonObject): ToolCallPatch = ToolCallPatch(
            id = o.str("toolCallId") ?: "",
            title = o.str("title"),
            kind = o.str("kind"),
            status = o.str("status"),
            content = (o["content"] as? JsonArray)?.let { arr -> ToolContent.parseList(arr.mapNotNull { it as? JsonObject }) },
            locations = (o["locations"] as? JsonArray)?.let { arr ->
                arr.mapNotNull { it as? JsonObject }.map { ToolLocation(it.str("path") ?: "", it.long("line")) }
            },
            rawInput = o["rawInput"].pretty(),
            rawOutput = o["rawOutput"].pretty(),
        )
    }
}

// ---------------------------------------------------------------------------
// Plans
// ---------------------------------------------------------------------------

data class PlanEntry(val content: String, val priority: String, val status: String)

/** A plan is either a checklist ([entries]) or a markdown document ([markdown]). */
data class Plan(val entries: List<PlanEntry>? = null, val markdown: String? = null, val path: String? = null)

private fun parsePlanEntries(o: JsonObject): List<PlanEntry> = o.objects("entries").map {
    PlanEntry(it.str("content") ?: "", it.str("priority") ?: "medium", it.str("status") ?: "pending")
}

// ---------------------------------------------------------------------------
// Session updates (the `session/update` notification)
// ---------------------------------------------------------------------------

sealed interface SessionUpdate {
    data class UserMessageChunk(val content: ContentBlock, val messageId: String?) : SessionUpdate
    data class AgentMessageChunk(val content: ContentBlock, val messageId: String?) : SessionUpdate
    data class AgentThoughtChunk(val content: ContentBlock, val messageId: String?) : SessionUpdate
    data class ToolCallStarted(val call: ToolCall) : SessionUpdate
    data class ToolCallUpdated(val patch: ToolCallPatch) : SessionUpdate

    /** Legacy `plan` update: replaces the whole (single) plan. */
    data class PlanReplaced(val entries: List<PlanEntry>) : SessionUpdate

    /** `plan_update`: create/replace the plan with this id. */
    data class PlanUpdated(val planId: String, val plan: Plan) : SessionUpdate
    data class PlanRemoved(val planId: String) : SessionUpdate
    data class CommandsUpdated(val commands: List<AvailableCommand>) : SessionUpdate
    data class ModeChanged(val modeId: String) : SessionUpdate
    data class ConfigOptionsChanged(val options: List<ConfigOption>) : SessionUpdate
    data class SessionInfoChanged(val title: String?, val updatedAt: String?) : SessionUpdate
    data class Usage(val used: Long, val size: Long, val costAmount: Double?, val costCurrency: String?) : SessionUpdate
    data class Notice(val severity: String, val title: String, val description: String?) : SessionUpdate
    data class Compaction(val id: String, val status: String, val error: String?) : SessionUpdate
    data class Unknown(val type: String) : SessionUpdate

    companion object {
        fun parse(o: JsonObject): SessionUpdate = when (val type = o.str("sessionUpdate")) {
            "user_message_chunk" -> UserMessageChunk(ContentBlock.parse(o.obj("content")), o.str("messageId"))
            "agent_message_chunk" -> AgentMessageChunk(ContentBlock.parse(o.obj("content")), o.str("messageId"))
            "agent_thought_chunk" -> AgentThoughtChunk(ContentBlock.parse(o.obj("content")), o.str("messageId"))
            "tool_call" -> ToolCallStarted(ToolCallPatch.parse(o).toToolCall())
            "tool_call_update" -> ToolCallUpdated(ToolCallPatch.parse(o))
            "plan" -> PlanReplaced(parsePlanEntries(o))
            "plan_update" -> {
                val plan = o.obj("plan")
                when (plan?.str("type")) {
                    "items" -> PlanUpdated(plan.str("planId") ?: "", Plan(entries = parsePlanEntries(plan)))
                    "markdown" -> PlanUpdated(plan.str("planId") ?: "", Plan(markdown = plan.str("content") ?: "", path = plan.str("path")))
                    else -> Unknown("plan_update")
                }
            }
            "plan_removed" -> PlanRemoved(o.str("planId") ?: "")
            "available_commands_update" -> CommandsUpdated(
                o.objects("availableCommands").mapNotNull { c ->
                    val name = c.str("name") ?: return@mapNotNull null
                    AvailableCommand(name, c.str("description") ?: "", c.obj("input")?.str("hint"))
                },
            )
            "current_mode_update" -> ModeChanged(o.str("currentModeId") ?: "")
            "config_option_update" -> ConfigOptionsChanged(ConfigOption.parseList(o.objects("configOptions")))
            "session_info_update" -> SessionInfoChanged(o.str("title"), o.str("updatedAt"))
            "usage_update" -> {
                val cost = o.obj("cost")
                Usage(o.long("used") ?: 0, o.long("size") ?: 0, cost?.double("amount"), cost?.str("currency"))
            }
            "notice" -> Notice(o.str("severity") ?: "info", o.str("title") ?: "", o.str("description"))
            "compaction_update" -> Compaction(o.str("compactionId") ?: "", o.str("status") ?: "", o.str("error"))
            else -> Unknown(type ?: "missing")
        }
    }
}

// ---------------------------------------------------------------------------
// Permission requests (`session/request_permission`, agent → client)
// ---------------------------------------------------------------------------

data class PermissionOption(val optionId: String, val name: String, val kind: String) {
    val isAllow: Boolean get() = kind.startsWith("allow")
}

data class PermissionRequest(
    val sessionId: String,
    val toolCall: ToolCallPatch,
    val options: List<PermissionOption>,
) {
    companion object {
        fun parse(o: JsonObject): PermissionRequest = PermissionRequest(
            sessionId = o.str("sessionId") ?: "",
            toolCall = o.obj("toolCall")?.let { ToolCallPatch.parse(it) } ?: ToolCallPatch(""),
            options = o.objects("options").mapNotNull {
                val id = it.str("optionId") ?: return@mapNotNull null
                PermissionOption(id, it.str("name") ?: id, it.str("kind") ?: "allow_once")
            },
        )
    }
}

/** The user's answer: one of the offered options, or null for "cancelled". */
data class PermissionOutcome(val optionId: String?)
