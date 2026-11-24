package com.droidacp.data

import android.content.Context
import com.droidacp.core.chat.ProfileKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Project folders you have used before, newest first, so the folder field can offer
 * them in a drop-down. Bridge folders (paths on your computer) and on-device folders
 * are kept in separate lists, since a path only makes sense on its own machine.
 */
class FolderHistory(context: Context) {
    private val prefs = context.getSharedPreferences("folders", Context.MODE_PRIVATE)

    private val lists = ProfileKind.entries.associateWith { MutableStateFlow(read(it)) }

    fun recent(kind: ProfileKind): StateFlow<List<String>> = lists.getValue(kind).asStateFlow()

    /** Put [path] at the top of the list (moving it there if it was already listed). */
    fun remember(kind: ProfileKind, path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        write(kind, (listOf(clean) + lists.getValue(kind).value.filterNot { it == clean }).take(MAX_ENTRIES))
    }

    fun forget(kind: ProfileKind, path: String) = write(kind, lists.getValue(kind).value.filterNot { it == path })

    private fun read(kind: ProfileKind): List<String> = try {
        (Json.parseToJsonElement(prefs.getString(kind.id, null) ?: "[]") as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(kind: ProfileKind, list: List<String>) {
        lists.getValue(kind).value = list
        prefs.edit().putString(kind.id, JsonArray(list.map(::JsonPrimitive)).toString()).apply()
    }

    private companion object {
        const val MAX_ENTRIES = 15
    }
}
