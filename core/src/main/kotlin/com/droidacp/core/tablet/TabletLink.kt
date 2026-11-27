package com.droidacp.core.tablet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * DroidACP's connection to TabDesk's agent service on this tablet, as the relay sees it
 * (TabDesk's `docs/agent-control.md`, Part 1). The Android side, binding TabDesk's
 * Binder service, lives in the app (`TabDeskLink`); tests use a fake.
 *
 * DroidACP passes MCP messages through and reads only their envelope. TabDesk decides
 * what the agent may do, and says so in its answers.
 */
interface TabletLink {
    /** What TabDesk last said: how far the connection got, its approval and its control state. */
    val status: StateFlow<TabletStatus>

    /**
     * Bind TabDesk's service and `connect()` while [wanted], i.e. while "Let the agent use this
     * tablet" is on for the PlasmaLLM agent in use. TabDesk's "wants to use this tablet"
     * notification comes from that first connect, so it only comes when the user asked.
     * Not wanted, it lets go of the service.
     */
    fun setWanted(wanted: Boolean)

    /**
     * Send one MCP JSON-RPC message under the Binder id [id] (`<connection>:<requestId>`) and
     * return TabDesk's answer, the JSON text of a JSON-RPC result or error.
     *
     * Throws an [com.droidacp.core.rpc.RpcException] with
     * [com.droidacp.core.acp.PlasmaLlm.TABLET_UNAVAILABLE] when TabDesk can't be reached
     * (not installed, not connected yet, another interface version), or
     * [com.droidacp.core.acp.PlasmaLlm.TABLET_FAILED] when the call failed on the way (its
     * process died, the answer's pipe broke). Cancelling the coroutine cancels the call at
     * TabDesk, which then sends no answer.
     */
    suspend fun send(id: String, message: String): String

    /** The user pressed Stop: TabDesk drops the running calls and refuses more until [resume]. */
    fun stop()

    /** The user sent a message: control may go on after a Stop or a pause. A no-op otherwise. */
    fun resume()
}

/** TabDesk's state for DroidACP, from `status()` and `onControlState`. */
data class TabletStatus(
    val reach: Reach = Reach.Off,
    /** TabDesk's own switch, "Let apps use this tablet". */
    val enabled: Boolean = false,
    /** `none`, `asked`, `approved`, `lapsed` or `certificate_changed`. */
    val approval: String? = null,
    /** TabDesk's helper: `running`, `starting`, `not_running` or `not_set_up`. */
    val helper: String? = null,
    /** The tablet's name, as set in TabDesk; it names the tablet to PlasmaLLM. */
    val name: String? = null,
    /** `idle`, `active`, `paused`, `stopped` or `revoked`; null until TabDesk has said. */
    val control: String? = null,
    /** Why, e.g. `tabdesk` or `caller` for `stopped`, `lapsed` for `revoked`. */
    val reason: String? = null,
) {
    enum class Reach {
        /** Not wanted, so not bound. */
        Off,

        /** TabDesk isn't installed (or Android hides it: the `<queries>` entry). */
        NotInstalled,

        /** TabDesk is installed, but too old to have the agent service. */
        NoService,

        /** Binding, checking the version, or connecting. */
        Connecting,

        /** Bound and connected, interface version 1. Calls go to TabDesk, which may refuse them. */
        Connected,

        /** TabDesk's agent interface is another version: DroidACP needs an update. */
        Incompatible,

        /** TabDesk's process went away (updated, or stopped by Android); binding again. */
        Lost,
    }

    val connected: Boolean get() = reach == Reach.Connected

    /**
     * Why calls can't reach TabDesk now, in words that follow PlasmaLLM's "The tablet isn't
     * available right now: ". Null while connected: then TabDesk answers for itself.
     */
    val unavailable: String?
        get() = when (reach) {
            Reach.Connected -> null
            Reach.Off -> "DroidACP isn't connected to TabDesk"
            Reach.NotInstalled -> "TabDesk isn't installed on the tablet"
            Reach.NoService -> "TabDesk on the tablet is too old to be used by an agent: update TabDesk"
            Reach.Connecting -> "DroidACP is still connecting to TabDesk"
            Reach.Incompatible -> "TabDesk's agent interface is newer: update DroidACP"
            Reach.Lost -> "TabDesk stopped, and DroidACP is connecting to it again"
        }

    /** TabDesk lets DroidACP use the tablet: approved, and not switched off or revoked. */
    val approved: Boolean get() = connected && approval == APPROVED && control != REVOKED

    /** Why TabDesk doesn't let DroidACP use the tablet; null while it does, or isn't connected. */
    val refusal: Refusal?
        get() = when {
            !connected || approved -> null
            !enabled || reason == SWITCHED_OFF -> Refusal.SwitchedOff
            approval == LAPSED || reason == LAPSED -> Refusal.Lapsed
            approval == CERTIFICATE_CHANGED || reason == CERTIFICATE_CHANGED -> Refusal.CertificateChanged
            control == REVOKED && reason == REVOKED -> Refusal.Revoked
            else -> Refusal.NotApproved
        }

    enum class Refusal {
        /** TabDesk's "Let apps use this tablet" is off. */
        SwitchedOff,

        /** The approval ran out (it lasts 7 days). */
        Lapsed,

        /** TabDesk approved another copy of DroidACP, signed with another key (a debug build). */
        CertificateChanged,

        /** The user revoked DroidACP's approval in TabDesk. */
        Revoked,

        /** Not approved yet, or asked and waiting. */
        NotApproved,
    }

    companion object {
        const val APPROVED = "approved"
        const val IDLE = "idle"
        const val ACTIVE = "active"
        const val PAUSED = "paused"
        const val STOPPED = "stopped"
        const val REVOKED = "revoked"
        const val SWITCHED_OFF = "switched_off"
        const val LAPSED = "lapsed"
        const val CERTIFICATE_CHANGED = "certificate_changed"

        /** `stopped`'s reason when Stop was pressed on TabDesk's indicator or notification. */
        const val BY_TABDESK = "tabdesk"

        /** `paused`'s reason: the user touched the screen while the agent was using it (tool list 2). */
        const val TOUCHED = "touched"

        /**
         * TabDesk's own `paused` refusal (plan D11), for a tool call it dropped when it paused: an
         * MCP result the agent reads and can tell the user about. [reason] is the pause's.
         */
        fun pausedRefusal(reason: String): JsonObject = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Refused (paused): " + if (reason == TOUCHED) {
                        "The user touched the screen. The agent can go on after the user's next message."
                    } else {
                        "TabDesk paused the agent. It can go on after the user's next message."
                    })
                })
            }
            put("isError", true)
            putJsonObject("_meta") { put("dev.tabdesk/refused", "paused") }
        }
    }
}

/** This device's tablet, for [com.droidacp.core.chat.AgentController]: TabDesk, and the id it's offered under. */
class Tablet(
    val link: TabletLink,
    /** `droidacp:<uuid>`, the same for as long as DroidACP is installed (at most 128 characters). */
    val serverId: String,
    /** On hold when DroidACP started (saved by [saveHeld]). */
    held: Boolean = false,
    /** Keeps the hold through a restart; called on every change, before anything else happens. */
    private val saveHeld: (Boolean) -> Unit = {},
) {
    private val _held = MutableStateFlow(held)

    /**
     * 🛑 On hold (plan D20): the agent may not use this tablet at all, whatever TabDesk allows,
     * until the user lets it go on here. The user's next message doesn't lift it, unlike Stop.
     */
    val held: StateFlow<Boolean> = _held.asStateFlow()

    fun setHeld(on: Boolean) {
        saveHeld(on)
        _held.value = on
    }
}

/**
 * How a [TabletLink] fails the calls TabDesk dropped when it paused (control `paused`, plan
 * D11): TabDesk answers none of them. [TabletRelay] answers a tool call with TabDesk's own
 * refusal ([TabletStatus.pausedRefusal]), anything else as cancelled.
 */
class PausedOnTablet(val reason: String) : Exception("Paused on the tablet ($reason)")
