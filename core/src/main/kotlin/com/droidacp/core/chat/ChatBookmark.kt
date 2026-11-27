package com.droidacp.core.chat

import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A chat saved to come back to quickly: which one, the folder it runs in (opening it
 * there, not in whatever folder is open), and its title, kept up to date while it's open.
 * [agentId]: for PlasmaLLM, which of its agents the chat is with (protocol 15), since only
 * that agent can open it; null for one saved before, which is Claude's ([PlasmaLlm.CLAUDE]).
 */
data class ChatBookmark(val sessionId: String, val cwd: String, val title: String?, val savedAt: Long, val agentId: String? = null)

/** Bookmark lists, newest first, and how they're stored (one JSON list per saved agent). */
object ChatBookmarks {
    /** Old ones drop off the end beyond this many. */
    const val MAX = 50

    fun contains(list: List<ChatBookmark>, sessionId: String?): Boolean = sessionId != null && list.any { it.sessionId == sessionId }

    /** Save [bookmark] at the top, or, if that chat is saved already, remove it. */
    fun toggle(list: List<ChatBookmark>, bookmark: ChatBookmark): List<ChatBookmark> =
        if (contains(list, bookmark.sessionId)) remove(list, bookmark.sessionId) else (listOf(bookmark) + list).take(MAX)

    fun remove(list: List<ChatBookmark>, sessionId: String): List<ChatBookmark> = list.filterNot { it.sessionId == sessionId }

    /** The chat got a (new) title. Unchanged when it isn't saved, or the title is the same. */
    fun retitle(list: List<ChatBookmark>, sessionId: String, title: String?): List<ChatBookmark> {
        if (title.isNullOrBlank() || list.none { it.sessionId == sessionId && it.title != title }) return list
        return list.map { if (it.sessionId == sessionId) it.copy(title = title) else it }
    }

    fun toJson(list: List<ChatBookmark>): String = buildJsonArray {
        list.forEach { b ->
            add(
                buildJsonObject {
                    put("sessionId", b.sessionId)
                    put("cwd", b.cwd)
                    if (b.title != null) put("title", b.title)
                    put("savedAt", b.savedAt)
                    if (b.agentId != null) put("agentId", b.agentId)
                },
            )
        }
    }.toString()

    fun fromJson(text: String?): List<ChatBookmark> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            (Json.parseToJsonElement(text) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.str("sessionId")?.ifEmpty { null } ?: return@mapNotNull null
                ChatBookmark(id, o.str("cwd") ?: "", o.str("title"), o.long("savedAt") ?: 0L, o.str("agentId")?.ifEmpty { null })
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
