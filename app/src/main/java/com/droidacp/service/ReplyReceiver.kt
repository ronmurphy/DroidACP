package com.droidacp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.droidacp.DroidAcpApp

/**
 * Gets the text typed into a notification's Reply box and sends it to the chat the
 * notification came from. If it can't go (PlasmaLLM moved to another chat, a turn is
 * running, the connection dropped), the notification says why and keeps the text.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = DroidAcpApp.get(context)
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val agentName = intent.getStringExtra(EXTRA_AGENT_NAME)?.ifEmpty { null } ?: app.controller.state.value.agentName
        val refused = app.controller.sendReply(sessionId, text)
        if (refused == null) {
            Notifications.showReplySent(context, agentName, text)
        } else {
            Notifications.showNotSent(context, agentName, refused, text.ifEmpty { null })
        }
    }

    companion object {
        const val KEY_TEXT = "com.droidacp.reply.TEXT"
        const val EXTRA_SESSION_ID = "com.droidacp.reply.SESSION_ID"
        const val EXTRA_AGENT_NAME = "com.droidacp.reply.AGENT_NAME"
    }
}
