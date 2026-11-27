package com.droidacp

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.droidacp.core.acp.BridgeFolders
import com.droidacp.core.acp.WebSocketTransport
import com.droidacp.core.agent.LocalAgentConfig
import com.droidacp.core.agent.LocalTransport
import com.droidacp.core.chat.AfterReboot
import com.droidacp.core.chat.AgentController
import com.droidacp.core.cloud.CloudAgentConfig
import com.droidacp.core.cloud.CloudTransport
import com.droidacp.core.tablet.Tablet
import com.droidacp.core.chat.AgentEvent
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.core.chat.TransportFactory
import com.droidacp.data.BookmarkStore
import com.droidacp.data.FolderHistory
import com.droidacp.data.ProfileStore
import com.droidacp.data.ViewSettings
import com.droidacp.service.AgentService
import com.droidacp.service.Notifications
import com.droidacp.tablet.TabDeskLink
import com.droidacp.widget.AgentWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.File

/**
 * The Application object lives as long as the app's process. It owns the single
 * [AgentController] (so the connection survives screen rotation and navigation)
 * and wires its state to the foreground service, notifications and widget.
 */
class DroidAcpApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var controller: AgentController
        private set
    lateinit var profiles: ProfileStore
        private set
    lateinit var folders: FolderHistory
        private set

    /** Bookmarked chats, per agent. */
    lateinit var bookmarks: BookmarkStore
        private set

    /** The developer view or the standard one, for every agent on this device. */
    lateinit var view: ViewSettings
        private set

    /**
     * TabDesk's agent service on this device, through which PlasmaLLM's agent may use the
     * tablet (only for an agent with "Let the agent use this tablet" on).
     */
    lateinit var tabDesk: TabDeskLink
        private set

    /** Asks a bridge which folders its computer has, for the folder picker. */
    val bridgeFolders by lazy { BridgeFolders(WebSocketTransport.defaultClient()) }

    @Volatile
    private var startedActivities = 0
    val isInForeground: Boolean get() = startedActivities > 0

    // The chat screen's activity, on screen (also as a floating window that isn't focused).
    @Volatile
    private var startedMain = 0
    val isChatOnScreen: Boolean get() = startedMain > 0

    override fun onCreate() {
        super.onCreate()
        tabDesk = TabDeskLink(this)
        controller = AgentController(
            appScope,
            BuildConfig.VERSION_NAME,
            tablet = Tablet(tabDesk, TabDeskLink.serverId(this), TabDeskLink.isHeld(this)) { TabDeskLink.saveHeld(this, it) },
            transports = transportFactory(),
        )
        profiles = ProfileStore(this)
        folders = FolderHistory(this)
        bookmarks = BookmarkStore(this)
        view = ViewSettings(this) { profiles.profiles.value.isNotEmpty() }
        Notifications.createChannels(this)
        registerActivityLifecycleCallbacks(ForegroundTracker())
        watchController()
    }

    /**
     * Decides how to reach an agent: bridge and PlasmaLLM agents over a WebSocket; the built-in
     * agent and cloud mode through an in-memory pipe inside this process.
     */
    private fun transportFactory(): TransportFactory {
        val http = WebSocketTransport.defaultClient()
        val home = File(filesDir, "agent-home").apply { mkdirs() }
        val localAgent = LocalAgentConfig(
            sessionsDir = File(filesDir, "agent-sessions"),
            platform = "Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})",
            version = BuildConfig.VERSION_NAME,
            // Shell commands run as this app; give them somewhere of their own for HOME and temp files.
            shellEnv = mapOf("HOME" to home.path, "TMPDIR" to cacheDir.path),
        )
        val cloudAgent = CloudAgentConfig(stateDir = File(filesDir, "agent-cloud"), version = BuildConfig.VERSION_NAME)
        return TransportFactory { profile, listener ->
            when (profile.kind) {
                ProfileKind.Local -> LocalTransport(listener, localAgent, profile.apiKey)
                ProfileKind.Cloud -> CloudTransport(listener, cloudAgent, profile.apiKey, profile.repoUrl, profile.repoToken)
                ProfileKind.Bridge -> WebSocketTransport(http, profile.url, profile.token, listener)
                // No token: PlasmaLLM lets in devices signed in to the computer's own Tailscale account.
                ProfileKind.PlasmaLlm -> WebSocketTransport(
                    http, profile.connectUrl, null, listener,
                    refused = "PlasmaLLM refused this device. Phone access only lets in devices signed in to the computer's own Tailscale account",
                )
            }
        }
    }

    /** Connect again to the agent used last time, and its last chat. False if there's none. */
    fun reconnectLast(): Boolean {
        val last = profiles.get(profiles.lastProfileId) ?: return false
        connectAgain(last)
        return true
    }

    private fun connectAgain(profile: Profile) =
        controller.connect(profile, resumeSessionId = profiles.lastSessionId(profile.id), resumeCwd = profiles.lastSessionCwd(profile.id))

    /**
     * The device has started (plan D15): if the tablet's relay was on, connect it again, so
     * the tablet is ready without opening DroidACP. A connection that fails (the network
     * isn't up yet) is tried again by itself, as for any drop. False if there's nothing to do.
     */
    fun connectAfterBoot(): Boolean {
        val profile = profiles.get(profiles.afterRebootId)?.takeIf { it.offersTablet } ?: return false
        val status = controller.state.value.status
        if (status != ConnectionStatus.Disconnected && status !is ConnectionStatus.Failed) return false
        connectAgain(profile)
        return true
    }

    @OptIn(FlowPreview::class)
    private fun watchController() {
        // Run the foreground service exactly while we're connecting or connected.
        appScope.launch {
            controller.state
                // Also while waiting to connect again by itself (the tablet's relay).
                .map { it.status == ConnectionStatus.Connecting || it.status == ConnectionStatus.Connected || it.reconnecting }
                .distinctUntilChanged()
                .collect { live -> if (live) AgentService.start(this@DroidAcpApp) else AgentService.stop(this@DroidAcpApp) }
        }

        // Which agent the tablet's relay connects to after a reboot, if any (plan D15).
        appScope.launch {
            controller.state
                .map(AfterReboot::change)
                .distinctUntilChanged()
                .collect { change ->
                    when (change) {
                        is AfterReboot.Change.Remember -> if (profiles.afterRebootId != change.profileId) profiles.afterRebootId = change.profileId
                        AfterReboot.Change.Forget -> if (profiles.afterRebootId != null) profiles.afterRebootId = null
                        null -> {}
                    }
                }
        }

        // Remember each agent's latest session, and its folder, for "Continue last session".
        appScope.launch {
            controller.state
                .map { Triple(it.profile?.id, it.sessionId, it.sessionCwd) }
                .distinctUntilChanged()
                .collect { (profileId, sessionId, cwd) ->
                    if (profileId != null && sessionId != null) profiles.setLastSession(profileId, sessionId, cwd)
                }
        }

        // A bookmarked chat's title follows the chat's own (the agent names chats as they go).
        appScope.launch {
            controller.state
                .map { Triple(it.profile?.id, it.sessionId, it.sessionTitle) }
                .distinctUntilChanged()
                .collect { (profileId, sessionId, title) ->
                    if (profileId != null && sessionId != null) bookmarks.retitle(profileId, sessionId, title)
                }
        }

        // Keep the home-screen widget up to date, about once a second while the agent writes.
        appScope.launch {
            var previous = AgentWidget.load(this@DroidAcpApp)
            controller.state
                .sample(1000)
                .map { AgentWidget.snapshotOf(it, previous) }
                .distinctUntilChanged()
                .collect { snapshot ->
                    previous = snapshot
                    AgentWidget.update(this@DroidAcpApp, snapshot)
                }
        }

        // Tell the user about important things while they're in another app.
        appScope.launch {
            controller.events.collect { event ->
                if (isInForeground) return@collect
                // Quiet on this device: the phone has this agent's notifications.
                if (controller.state.value.profile?.let { it.offersTablet && it.quietHere } == true) return@collect
                when (event) {
                    is AgentEvent.TurnFinished -> {
                        // Offer Reply when the chat it finished in is still open and ready for a message.
                        val s = controller.state.value
                        val replyTo = event.sessionId?.takeIf { it == s.sessionId && s.canChat && !s.busy }
                        Notifications.showEvent(
                            this@DroidAcpApp,
                            "${event.agentName} finished",
                            event.lastText?.take(400) ?: "Stop reason: ${event.stopReason}",
                            replyTo = replyTo,
                            agentName = event.agentName,
                        )
                    }
                    is AgentEvent.PromptFailed -> Notifications.showNotSent(this@DroidAcpApp, event.agentName, event.message, event.unsent)
                    is AgentEvent.PermissionRequested -> Notifications.showEvent(
                        this@DroidAcpApp,
                        "${event.agentName} needs your permission",
                        event.title,
                    )
                    is AgentEvent.ConnectionLost -> Notifications.showEvent(
                        this@DroidAcpApp,
                        "${event.agentName} disconnected",
                        event.reason,
                    )
                }
            }
        }
    }

    /** Counts visible activities so we know whether the user is looking at the app. */
    private inner class ForegroundTracker : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (activity is MainActivity) startedMain++
            Notifications.clearEvents(this@DroidAcpApp)
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
            if (activity is MainActivity) startedMain--
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        fun get(context: Context): DroidAcpApp = context.applicationContext as DroidAcpApp
    }
}
