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
import androidx.core.app.RemoteInput
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

    /**
     * "Agent finished" / "needs permission" alerts, only used while the app is in the background.
     * With [replyTo] (a session id) it has a Reply button that sends a message to that chat.
     */
    fun showEvent(context: Context, title: String, text: String, replyTo: String? = null, agentName: String = "") {
        if (!canNotify(context)) return
        val builder = event(context, title, text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context, focusInput = false, requestCode = 2))
        if (replyTo != null) builder.addAction(replyAction(context, replyTo, agentName))
        notify(context, builder.build())
    }

    /** A reply from the notification went out: show it there, which also stops Android's "sending" spinner. */
    fun showReplySent(context: Context, agentName: String, reply: String) {
        if (!canNotify(context)) return
        val builder = event(context, "Sent to $agentName", reply)
            .setSubText("Waiting for the answer")
            .setSilent(true)
            .setContentIntent(openApp(context, focusInput = false, requestCode = 2))
        notify(context, builder.build())
    }

    /**
     * A message didn't go through. [unsent] is its text when the agent never got it:
     * tapping the notification puts it back in the message box.
     */
    fun showNotSent(context: Context, agentName: String, reason: String, unsent: String?) {
        if (!canNotify(context)) return
        val title = if (unsent != null) "Message not sent to $agentName" else "$agentName ran into a problem"
        val text = if (unsent != null) "$reason\n\nYour message: $unsent\n\nTap to try again." else reason
        val builder = event(context, title, text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context, focusInput = unsent != null, requestCode = 4, draft = unsent))
        notify(context, builder.build())
    }

    private fun event(context: Context, title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)

    private fun notify(context: Context, notification: Notification) {
        context.getSystemService(NotificationManager::class.java)?.notify(ID_EVENT, notification)
    }

    /** Android's direct reply: type an answer in the notification, without opening the app. */
    private fun replyAction(context: Context, sessionId: String, agentName: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Reply to $agentName").build()
        val intent = Intent(context, ReplyReceiver::class.java)
            .putExtra(ReplyReceiver.EXTRA_SESSION_ID, sessionId)
            .putExtra(ReplyReceiver.EXTRA_AGENT_NAME, agentName)
        // The text box fills in the intent, so it has to be mutable.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, 5, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_agent, "Reply", pending)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    fun clearEvents(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_EVENT)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the chat screen (used by notifications and the widget). */
    fun openApp(context: Context, focusInput: Boolean, requestCode: Int, draft: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
            putExtra(MainActivity.EXTRA_FOCUS_INPUT, focusInput)
            if (draft != null) putExtra(MainActivity.EXTRA_DRAFT, draft)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun disconnect(context: Context): PendingIntent {
        val intent = Intent(context, AgentService::class.java).setAction(AgentService.ACTION_DISCONNECT)
        return PendingIntent.getService(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
