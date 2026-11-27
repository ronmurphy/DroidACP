package com.droidacp.core.chat

/**
 * After a reboot, the tablet's relay connects again by itself if it was on (plan D15), so
 * the tablet is ready without opening DroidACP. This decides, from the connection's state,
 * which agent that is: one with "Let the agent use this tablet" on, connected (or connecting,
 * or waiting to connect again) when the device went off.
 */
object AfterReboot {
    sealed interface Change {
        /** Connect to this agent after a reboot. */
        data class Remember(val profileId: String) : Change

        /** Connect to none. */
        data object Forget : Change
    }

    /** What [state] changes about the agent to connect after a reboot; null leaves it as it was. */
    fun change(state: ChatState): Change? {
        // The app has only just started: nothing has been chosen since.
        val profile = state.profile ?: return null
        val live = state.status == ConnectionStatus.Connecting || state.status == ConnectionStatus.Connected || state.reconnecting
        return when {
            live && profile.offersTablet -> Change.Remember(profile.id)
            // Another agent, or the setting went off.
            live -> Change.Forget
            // The user disconnected.
            state.status == ConnectionStatus.Disconnected -> Change.Forget
            // It failed and gave up (refused): a reboot is worth another try.
            else -> null
        }
    }
}
