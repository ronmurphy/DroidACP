package com.droidacp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How chats look on this device, for every agent ([com.droidacp.core.chat.ChatView]): the
 * developer view, with the agent's tool calls, thinking and plans, or the standard one,
 * with just the conversation and its pictures.
 *
 * A device that had agents before the choice existed keeps the developer view it always
 * had; a new one starts with the standard view. [hadAgents] is asked only that first time.
 */
class ViewSettings(context: Context, hadAgents: () -> Boolean) {
    private val prefs = context.getSharedPreferences("view", Context.MODE_PRIVATE)

    private val _developer = MutableStateFlow(
        if (prefs.contains(KEY_DEVELOPER)) {
            prefs.getBoolean(KEY_DEVELOPER, true)
        } else {
            hadAgents().also { prefs.edit().putBoolean(KEY_DEVELOPER, it).apply() }
        },
    )
    val developer: StateFlow<Boolean> = _developer.asStateFlow()

    fun setDeveloper(on: Boolean) {
        _developer.value = on
        prefs.edit().putBoolean(KEY_DEVELOPER, on).apply()
    }

    private companion object {
        const val KEY_DEVELOPER = "developer"
    }
}
