package com.droidacp.data

import android.content.Context
import com.droidacp.core.chat.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Saved agents, stored as JSON in private SharedPreferences.
 * (Only this app can read them. The bridge token is stored here too.)
 */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(Profile.listFromJson(prefs.getString(KEY_LIST, null)))
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    fun get(id: String?): Profile? = _profiles.value.firstOrNull { it.id == id }

    fun save(profile: Profile) {
        val list = _profiles.value
        val updated = if (list.any { it.id == profile.id }) {
            list.map { if (it.id == profile.id) profile else it }
        } else {
            list + profile
        }
        persist(updated)
    }

    fun delete(id: String) {
        persist(_profiles.value.filterNot { it.id == id })
        prefs.edit().remove(sessionKey(id)).apply()
        if (lastProfileId == id) lastProfileId = null
    }

    /** The agent used most recently (the widget reconnects to it). */
    var lastProfileId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    fun lastSessionId(profileId: String): String? = prefs.getString(sessionKey(profileId), null)

    fun setLastSessionId(profileId: String, sessionId: String) {
        prefs.edit().putString(sessionKey(profileId), sessionId).apply()
    }

    private fun persist(list: List<Profile>) {
        _profiles.value = list
        prefs.edit().putString(KEY_LIST, Profile.listToJson(list)).apply()
    }

    private fun sessionKey(profileId: String) = "session:$profileId"

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_LAST = "last"
    }
}
