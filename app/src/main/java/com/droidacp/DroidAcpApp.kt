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
import com.droidacp.core.chat.AgentController
import com.droidacp.core.cloud.CloudAgentConfig
import com.droidacp.core.cloud.CloudTransport
import com.droidacp.core.chat.AgentEvent
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.ProfileKind
import com.droidacp.core.chat.TransportFactory
import com.droidacp.data.FolderHistory
import com.droidacp.data.ProfileStore
import com.droidacp.service.AgentService
import com.droidacp.service.Notifications
import com.droidacp.widget.AgentWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /** Asks a bridge which folders its computer has, for the folder picker. */
    val bridgeFolders by lazy { BridgeFolders(WebSocketTransport.defaultClient()) }

    @Volatile
    private var startedActivities = 0
    val isInForeground: Boolean get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        controller = AgentController(appScope, BuildConfig.VERSION_NAME, transportFactory())
        profiles = ProfileStore(this)
        folders = FolderHistory(this)
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

    @OptIn(FlowPreview::class)
    private fun watchController() {
        // Run the foreground service exactly while we're connecting or connected.
        appScope.launch {
            controller.state
                .map { it.status == ConnectionStatus.Connecting || it.status == ConnectionStatus.Connected }
                .distinctUntilChanged()
                .collect { live -> if (live) AgentService.start(this@DroidAcpApp) else AgentService.stop(this@DroidAcpApp) }
        }

        // Remember each agent's latest session for "Continue last session".
        appScope.launch {
            controller.state
                .map { it.profile?.id to it.sessionId }
                .distinctUntilChanged()
                .collect { (profileId, sessionId) ->
                    if (profileId != null && sessionId != null) profiles.setLastSessionId(profileId, sessionId)
                }
        }

        // Keep the home-screen widget up to date (at most a couple of times a second).
        appScope.launch {
            var previous = AgentWidget.load(this@DroidAcpApp)
            controller.state
                .debounce(500)
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
                when (event) {
                    is AgentEvent.TurnFinished -> Notifications.showEvent(
                        this@DroidAcpApp,
                        "${event.agentName} finished",
                        event.lastText?.take(400) ?: "Stop reason: ${event.stopReason}",
                    )
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
            Notifications.clearEvents(this@DroidAcpApp)
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
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
