package com.droidacp.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.droidacp.MainActivity
import com.droidacp.R
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus

object Notifications {
    private const val CHANNEL_CONNECTION = "connection"
    private const val CHANNEL_EVENTS = "events"
    const val ID_ONGOING = 1
    private const val ID_EVENT = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_connection_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, context.getString(R.string.channel_events), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_events_desc)
            },
        )
    }

    /** One-line summary of what the agent is doing, for the ongoing notification and the widget. */
    fun statusText(state: ChatState): String = when {
        state.status is ConnectionStatus.Connecting -> "Connecting…"
        state.status is ConnectionStatus.Failed -> "Disconnected"
        state.status is ConnectionStatus.Disconnected -> "Not connected"
        state.permissions.isNotEmpty() -> "Needs your permission"
        state.cancelling -> "Stopping…"
        state.busy -> "Working…"
        state.authRequired -> "Sign-in needed"
        else -> "Ready"
    }

    /** The silent, ongoing notification that keeps the foreground service (and connection) alive. */
    fun ongoing(context: Context, state: ChatState): Notification =
        NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(state.agentName)
            .setContentText(statusText(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context, focusInput = false, requestCode = 1))
            .addAction(0, "Disconnect", disconnect(context))
            .build()

    fun updateOngoing(context: Context, state: ChatState) {
        if (!canNotify(context)) return
        context.getSystemService(NotificationManager::class.java)?.notify(ID_ONGOING, ongoing(context, state))
    }

    /** "Agent finished" / "needs permission" alerts, only used while the app is in the background. */
    fun showEvent(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, focusInput = false, requestCode = 2))
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(ID_EVENT, notification)
    }

    fun clearEvents(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_EVENT)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the chat screen (used by notifications and the widget). */
    fun openApp(context: Context, focusInput: Boolean, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
            putExtra(MainActivity.EXTRA_FOCUS_INPUT, focusInput)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun disconnect(context: Context): PendingIntent {
        val intent = Intent(context, AgentService::class.java).setAction(AgentService.ACTION_DISCONNECT)
        return PendingIntent.getService(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
