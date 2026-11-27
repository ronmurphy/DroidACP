package com.droidacp.core

import com.droidacp.core.chat.AfterReboot
import com.droidacp.core.chat.AfterReboot.Change
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which agent connects again by itself after a reboot (plan D15): the tablet's relay, if it was on. */
class AfterRebootTest {
    private val relay = Profile(id = "laptop", name = "Laptop", kind = ProfileKind.PlasmaLlm, url = "boston", cwd = "", useTablet = true)
    private val plain = relay.copy(id = "other", useTablet = false)

    private fun change(status: ConnectionStatus, profile: Profile? = relay, reconnecting: Boolean = false) =
        AfterReboot.change(ChatState(status = status, profile = profile, reconnecting = reconnecting))

    @Test
    fun theRelayIsRememberedWhileItsConnectionIsWanted() {
        assertEquals(Change.Remember("laptop"), change(ConnectionStatus.Connecting))
        assertEquals(Change.Remember("laptop"), change(ConnectionStatus.Connected))
        assertEquals(Change.Remember("laptop"), change(ConnectionStatus.Failed("Wi-Fi"), reconnecting = true), "waiting to connect again")
    }

    @Test
    fun anotherAgentOrADisconnectForgetsIt() {
        assertEquals(Change.Forget, change(ConnectionStatus.Connected, plain), "an agent that doesn't use the tablet")
        assertEquals(Change.Forget, change(ConnectionStatus.Connected, relay.copy(kind = ProfileKind.Bridge)), "only PlasmaLLM uses it")
        assertEquals(Change.Forget, change(ConnectionStatus.Disconnected), "the user disconnected")
    }

    @Test
    fun theAppStartingOrAFailureLeavesItAsItWas() {
        // A new process starts with no agent: that isn't the user disconnecting.
        assertNull(change(ConnectionStatus.Disconnected, profile = null))
        // Given up (e.g. refused): the next boot tries again.
        assertNull(change(ConnectionStatus.Failed("refused")))
    }
}
