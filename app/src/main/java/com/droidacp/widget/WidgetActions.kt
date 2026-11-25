package com.droidacp.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.droidacp.DroidAcpApp

/**
 * The widget's Stop, Allow and Deny buttons. Each says which chat (and which question)
 * it was drawn for, and does nothing if that has changed since: the widget is then
 * redrawn as things are now.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = DroidAcpApp.get(context).controller
        val s = controller.state.value
        val sessionId = intent.getStringExtra(WidgetActions.EXTRA_SESSION_ID)
        if (sessionId != null && sessionId == s.sessionId && s.isConnected) {
            when (intent.action) {
                WidgetActions.ACTION_STOP -> controller.cancelTurn()
                WidgetActions.ACTION_ANSWER -> {
                    val requestId = intent.getLongExtra(WidgetActions.EXTRA_REQUEST_ID, -1)
                    val optionId = intent.getStringExtra(WidgetActions.EXTRA_OPTION_ID)
                    val question = s.permissions.firstOrNull { it.requestId == requestId }
                    if (question != null && question.options.any { it.optionId == optionId }) controller.answerPermission(requestId, optionId)
                }
            }
        }
        AgentWidget.refresh(context)
    }
}

object WidgetActions {
    const val ACTION_STOP = "com.droidacp.widget.STOP"
    const val ACTION_ANSWER = "com.droidacp.widget.ANSWER"
    const val EXTRA_SESSION_ID = "com.droidacp.widget.SESSION_ID"
    const val EXTRA_REQUEST_ID = "com.droidacp.widget.REQUEST_ID"
    const val EXTRA_OPTION_ID = "com.droidacp.widget.OPTION_ID"

    fun stop(context: Context, sessionId: String): PendingIntent =
        broadcast(context, 22, Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SESSION_ID, sessionId))

    fun answer(context: Context, sessionId: String, requestId: Long, optionId: String, requestCode: Int): PendingIntent =
        broadcast(
            context,
            requestCode,
            Intent(context, WidgetActionReceiver::class.java)
                .setAction(ACTION_ANSWER)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_OPTION_ID, optionId),
        )

    private fun broadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
