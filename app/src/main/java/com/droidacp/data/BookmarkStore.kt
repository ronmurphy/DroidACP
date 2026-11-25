package com.droidacp.data

import android.content.Context
import com.droidacp.core.chat.ChatBookmark
import com.droidacp.core.chat.ChatBookmarks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bookmarked chats, one list per saved agent (a chat only exists on its own agent),
 * kept in private SharedPreferences. They hold chat titles and folder paths, no secrets.
 */
class BookmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    private val _all = MutableStateFlow(
        prefs.all.mapNotNull { (key, value) ->
            val profileId = key.removePrefix(PREFIX).takeIf { key.startsWith(PREFIX) } ?: return@mapNotNull null
            profileId to ChatBookmarks.fromJson(value as? String)
        }.toMap(),
    )

    /** Every agent's bookmarks, by profile id. */
    val all: StateFlow<Map<String, List<ChatBookmark>>> = _all.asStateFlow()

    fun of(profileId: String): List<ChatBookmark> = _all.value[profileId].orEmpty()

    /** Bookmark the chat, or remove its bookmark if it has one. */
    fun toggle(profileId: String, bookmark: ChatBookmark) = change(profileId) { ChatBookmarks.toggle(it, bookmark) }

    fun remove(profileId: String, sessionId: String) = change(profileId) { ChatBookmarks.remove(it, sessionId) }

    /** Keep a bookmark's title in step with the chat's. */
    fun retitle(profileId: String, sessionId: String, title: String?) = change(profileId) { ChatBookmarks.retitle(it, sessionId, title) }

    /** The agent was deleted: its chats can't be opened any more. */
    fun forgetAgent(profileId: String) = change(profileId) { emptyList() }

    private fun change(profileId: String, edit: (List<ChatBookmark>) -> List<ChatBookmark>) {
        val before = of(profileId)
        val after = edit(before)
        if (after == before) return
        _all.update { if (after.isEmpty()) it - profileId else it + (profileId to after) }
        prefs.edit().apply {
            if (after.isEmpty()) remove(PREFIX + profileId) else putString(PREFIX + profileId, ChatBookmarks.toJson(after))
        }.apply()
    }

    private companion object {
        const val PREFIX = "agent:"
    }
}
