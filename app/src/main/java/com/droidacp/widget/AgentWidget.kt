package com.droidacp.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.droidacp.R
import com.droidacp.core.chat.ChatState
import com.droidacp.service.Notifications

/**
 * The home-screen widget. Android draws widgets in the launcher's process using
 * RemoteViews (a description of a layout), so we can't use Compose here — we fill
 * in `res/layout/widget_agent.xml` instead.
 */
class AgentWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        AgentWidget.render(context, appWidgetManager, appWidgetIds, AgentWidget.load(context))
    }
}

object AgentWidget {
    /** What the widget shows. Saved so it survives the app being closed. */
    data class Snapshot(val title: String, val status: String, val body: String)

    private const val PREFS = "widget"

    fun snapshotOf(state: ChatState, previous: Snapshot?): Snapshot {
        val body = state.lastAgentText?.let(::plainText)
        return Snapshot(
            title = if (state.profile == null) "DroidACP" else state.agentName,
            status = Notifications.statusText(state),
            // Keep the last reply visible after disconnecting or starting a new session.
            body = body ?: previous?.body ?: "",
        )
    }

    fun update(context: Context, snapshot: Snapshot) {
        save(context, snapshot)
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, AgentWidgetProvider::class.java))
        if (ids.isNotEmpty()) render(context, manager, ids, snapshot)
    }

    fun render(context: Context, manager: AppWidgetManager, ids: IntArray, snapshot: Snapshot?) {
        val views = RemoteViews(context.packageName, R.layout.widget_agent).apply {
            setTextViewText(R.id.widget_title, snapshot?.title ?: context.getString(R.string.widget_default_title))
            setTextViewText(R.id.widget_status, snapshot?.status ?: "")
            setTextViewText(
                R.id.widget_body,
                snapshot?.body?.takeIf { it.isNotBlank() } ?: context.getString(R.string.widget_default_body),
            )
            setOnClickPendingIntent(R.id.widget_root, Notifications.openApp(context, focusInput = false, requestCode = 20))
            setOnClickPendingIntent(R.id.widget_ask, Notifications.openApp(context, focusInput = true, requestCode = 21))
        }
        manager.updateAppWidget(ids, views)
    }

    fun load(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = prefs.getString("title", null) ?: return null
        return Snapshot(title, prefs.getString("status", "") ?: "", prefs.getString("body", "") ?: "")
    }

    private fun save(context: Context, s: Snapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", s.title)
            .putString("status", s.status)
            .putString("body", s.body)
            .apply()
    }

    /** Markdown → plain text, roughly, for the small widget. */
    private fun plainText(markdown: String): String =
        markdown.take(1500)
            .replace(Regex("```[a-zA-Z0-9]*"), "")
            .replace(Regex("[*_`#>]"), "")
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(600)
}
