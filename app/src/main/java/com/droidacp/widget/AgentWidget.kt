package com.droidacp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.droidacp.DroidAcpApp
import com.droidacp.MainActivity
import com.droidacp.QuickAskActivity
import com.droidacp.R
import com.droidacp.core.chat.ChatDigest
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.service.Notifications
import com.droidacp.ui.kindVerb
import org.json.JSONArray
import org.json.JSONObject

/**
 * The home-screen widget. Android draws widgets in the launcher's process using
 * RemoteViews (a description of a layout), so we can't use Compose here — we fill
 * in `res/layout/widget_agent.xml` instead. The chat list's rows come from
 * [AgentWidgetService].
 */
class AgentWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        AgentWidget.render(context, appWidgetManager, appWidgetIds, AgentWidget.current(context))
    }
}

object AgentWidget {
    /** A permission question the widget can answer with Allow or Deny. */
    data class Question(val requestId: Long, val text: String, val allowId: String?, val denyId: String?, val moreChoices: Boolean)

    /**
     * What the widget shows. The title, status and rows are saved so they survive the app
     * being closed; the rest only means something while connected, so it isn't.
     */
    data class Snapshot(
        val title: String,
        val status: String,
        val rows: List<ChatDigest.Row>,
        val empty: String = "",
        val sessionId: String? = null,
        val working: Boolean = false,
        val canStop: Boolean = false,
        val question: Question? = null,
    )

    private const val PREFS = "widget"

    // The newest snapshot, for the list's rows (asked for on another thread).
    @Volatile
    private var latest: Snapshot? = null

    fun snapshotOf(state: ChatState, previous: Snapshot?): Snapshot {
        // A fresh start has no chat yet: keep showing the last one until there's a new one.
        val live = state.isConnected || state.items.isNotEmpty()
        val rows = if (live) ChatDigest.rows(state.items) else previous?.rows.orEmpty()
        val connected = state.isConnected && state.sessionId != null
        val permission = state.permissions.firstOrNull()?.takeIf { connected }
        return Snapshot(
            title = if (state.profile == null) previous?.title ?: "DroidACP" else state.agentName,
            status = Notifications.statusText(state),
            rows = rows,
            empty = when {
                state.status is ConnectionStatus.Connecting || state.startingSession -> "Connecting…"
                state.isConnected -> "Tap Ask to send ${state.agentName} a message."
                else -> "Not connected. Tap Ask to connect."
            },
            sessionId = state.sessionId,
            working = connected && state.busy,
            canStop = connected && state.busy && !state.cancelling,
            question = permission?.let { p ->
                val (allow, deny) = ChatDigest.quickAnswers(p.options)
                Question(
                    requestId = p.requestId,
                    text = "Allow ${state.agentName} to ${kindVerb(p.kind)}?\n${p.title}" +
                        if (state.permissions.size > 1) "\n(${state.permissions.size - 1} more waiting)" else "",
                    allowId = allow?.optionId,
                    denyId = deny?.optionId,
                    moreChoices = p.options.size > listOfNotNull(allow, deny).size,
                )
            },
        )
    }

    /** What to show now, for a widget that asks without the app having drawn it yet. */
    fun current(context: Context): Snapshot =
        snapshotOf(DroidAcpApp.get(context).controller.state.value, latest ?: load(context))

    /** The rows for the chat list. */
    fun rows(context: Context): List<ChatDigest.Row> = (latest ?: current(context)).rows

    fun update(context: Context, snapshot: Snapshot) {
        val old = latest
        latest = snapshot
        // Not on every word while the agent writes: when the turn ends, the status changes and it's saved.
        val changed = old == null || old.title != snapshot.title || old.status != snapshot.status
        if (changed || (!snapshot.working && old?.rows != snapshot.rows)) save(context, snapshot)
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, AgentWidgetProvider::class.java))
        if (ids.isEmpty()) return
        render(context, manager, ids, snapshot)
        if (old?.rows != snapshot.rows) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    /** Redraw from the app's state now (after a button press). */
    fun refresh(context: Context) = update(context, current(context))

    fun render(context: Context, manager: AppWidgetManager, ids: IntArray, snapshot: Snapshot) {
        for (id in ids) manager.updateAppWidget(id, views(context, id, snapshot))
    }

    private fun views(context: Context, widgetId: Int, s: Snapshot) = RemoteViews(context.packageName, R.layout.widget_agent).apply {
        setTextViewText(R.id.widget_title, s.title)
        setTextViewText(R.id.widget_status, s.status)
        setViewVisibility(R.id.widget_working, if (s.working) View.VISIBLE else View.GONE)
        setOnClickPendingIntent(R.id.widget_root, Notifications.openApp(context, focusInput = false, requestCode = 20))
        setOnClickPendingIntent(R.id.widget_header, Notifications.openApp(context, focusInput = false, requestCode = 20))

        // The chat. Each widget gets its own adapter intent (Android caches them by intent).
        val adapter = Intent(context, AgentWidgetService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
        @Suppress("DEPRECATION")
        setRemoteAdapter(R.id.widget_list, adapter)
        setEmptyView(R.id.widget_list, R.id.widget_empty)
        setTextViewText(R.id.widget_empty, s.empty)
        setPendingIntentTemplate(R.id.widget_list, rowTapped(context))

        // A permission question, answered here.
        val q = s.question
        setViewVisibility(R.id.widget_permission, if (q != null) View.VISIBLE else View.GONE)
        if (q != null && s.sessionId != null) {
            setTextViewText(R.id.widget_permission_text, q.text)
            setOnClickPendingIntent(R.id.widget_permission_text, Notifications.openApp(context, focusInput = false, requestCode = 20))
            setViewVisibility(R.id.widget_more, if (q.moreChoices) View.VISIBLE else View.INVISIBLE)
            setOnClickPendingIntent(R.id.widget_more, Notifications.openApp(context, focusInput = false, requestCode = 20))
            button(R.id.widget_allow, q.allowId) { WidgetActions.answer(context, s.sessionId, q.requestId, it, requestCode = 23) }
            button(R.id.widget_deny, q.denyId) { WidgetActions.answer(context, s.sessionId, q.requestId, it, requestCode = 24) }
        }

        val stopSession = s.sessionId.takeIf { s.canStop }
        setViewVisibility(R.id.widget_stop, if (stopSession != null) View.VISIBLE else View.GONE)
        if (stopSession != null) setOnClickPendingIntent(R.id.widget_stop, WidgetActions.stop(context, stopSession))

        setViewVisibility(R.id.widget_voice, if (QuickAskActivity.canListen(context)) View.VISIBLE else View.GONE)
        setOnClickPendingIntent(R.id.widget_voice, QuickAskActivity.open(context, voice = true))
        setOnClickPendingIntent(R.id.widget_ask, QuickAskActivity.open(context, voice = false))
    }

    private inline fun RemoteViews.button(viewId: Int, optionId: String?, intent: (String) -> PendingIntent) {
        setViewVisibility(viewId, if (optionId != null) View.VISIBLE else View.GONE)
        if (optionId != null) setOnClickPendingIntent(viewId, intent(optionId))
    }

    /** Tapping a message opens the chat. Rows fill in this template, so it must be mutable. */
    private fun rowTapped(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
        }
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getActivity(context, 25, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    fun load(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = prefs.getString("title", null) ?: return null
        val rows = try {
            prefs.getString("rows", null)?.let(::rowsFromJson)
        } catch (e: Exception) {
            null
        }
        // Saved by an older version: only the last reply.
        val oldBody = prefs.getString("body", null)?.takeIf { it.isNotBlank() }
        return Snapshot(
            title = title,
            status = prefs.getString("status", "") ?: "",
            rows = rows ?: listOfNotNull(oldBody?.let { ChatDigest.Row(ChatDigest.Kind.Agent, it) }),
        )
    }

    private fun save(context: Context, s: Snapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", s.title)
            .putString("status", s.status)
            .putString("rows", rowsToJson(s.rows))
            .remove("body")
            .apply()
    }

    private fun rowsToJson(rows: List<ChatDigest.Row>): String =
        JSONArray().apply {
            for (row in rows) put(JSONObject().put("kind", row.kind.name).put("text", row.text))
        }.toString()

    private fun rowsFromJson(json: String): List<ChatDigest.Row> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val kind = ChatDigest.Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: return@mapNotNull null
            ChatDigest.Row(kind, o.optString("text"))
        }
    }
}
