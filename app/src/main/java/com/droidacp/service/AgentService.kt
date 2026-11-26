package com.droidacp.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.droidacp.DroidAcpApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A foreground service that runs while an agent is connected.
 *
 * It doesn't do any work itself — the connection lives in [DroidAcpApp]. Its job is
 * to tell Android "this app is doing something the user cares about", which stops
 * the system from freezing the app (and dropping the WebSocket) when you switch to
 * another app in the middle of a long agent turn.
 */
class AgentService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = DroidAcpApp.get(this)

        if (intent?.action == ACTION_DISCONNECT) {
            app.controller.disconnect()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(this, Notifications.ID_ONGOING, Notifications.ongoing(this, app.controller.state.value), foregroundType())
        } catch (e: Exception) {
            // E.g. Android refused because the app was in the background. The
            // connection still works while the app is open.
            Log.w(TAG, "Could not start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (watcher == null) {
            watcher = app.appScope.launch {
                app.controller.state
                    .map { it.agentName to Notifications.ongoingText(it) }
                    .distinctUntilChanged()
                    .collect { Notifications.updateOngoing(this@AgentService, app.controller.state.value) }
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 limits some foreground service types to a few hours a day.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    private fun foregroundType(): Int = when {
        // "remoteMessaging": exchanging messages with another device — closest match, and no daily time limit.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    companion object {
        private const val TAG = "AgentService"
        const val ACTION_DISCONNECT = "com.droidacp.action.DISCONNECT"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
