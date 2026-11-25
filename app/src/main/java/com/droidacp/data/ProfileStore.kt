package com.droidacp.data

import android.content.Context
import com.droidacp.core.chat.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Saved agents, stored as JSON in private SharedPreferences. The list contains
 * secrets (API keys, bridge tokens), so it is encrypted with [SecretBox] first.
 */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(Profile.listFromJson(readList()))
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
        prefs.edit().remove(sessionKey(id)).remove(folderKey(id)).apply()
        if (lastProfileId == id) lastProfileId = null
    }

    /** The agent used most recently (the widget reconnects to it). */
    var lastProfileId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    fun lastSessionId(profileId: String): String? = prefs.getString(sessionKey(profileId), null)

    /** The folder [lastSessionId] runs in, when the app knew it (it can be another than the agent's). */
    fun lastSessionCwd(profileId: String): String? = prefs.getString(folderKey(profileId), null)?.ifEmpty { null }

    fun setLastSession(profileId: String, sessionId: String, cwd: String?) {
        prefs.edit().putString(sessionKey(profileId), sessionId).putString(folderKey(profileId), cwd ?: "").apply()
    }

    /** The encrypted list if there is one; otherwise the plain list older versions of the app saved. */
    private fun readList(): String? =
        prefs.getString(KEY_SEALED, null)?.let(SecretBox::decrypt) ?: prefs.getString(KEY_LIST, null)

    private fun persist(list: List<Profile>) {
        _profiles.value = list
        val json = Profile.listToJson(list)
        val sealed = SecretBox.encrypt(json)
        prefs.edit().apply {
            if (sealed != null) {
                putString(KEY_SEALED, sealed)
                remove(KEY_LIST)
            } else {
                // No usable Keystore on this device: fall back to app-private storage alone.
                putString(KEY_LIST, json)
                remove(KEY_SEALED)
            }
        }.apply()
    }

    private fun sessionKey(profileId: String) = "session:$profileId"

    private fun folderKey(profileId: String) = "sessionCwd:$profileId"

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_SEALED = "sealed"
        const val KEY_LAST = "last"
    }
}
