package com.droidacp.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import com.droidacp.R
import com.droidacp.core.chat.ChatDigest

/**
 * Fills the widget's chat list. The launcher asks for rows through this service
 * whenever [AgentWidget.update] says the chat changed.
 */
class AgentWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Rows(applicationContext)

    private class Rows(private val context: Context) : RemoteViewsFactory {
        private var rows: List<ChatDigest.Row> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            rows = AgentWidget.rows(context)
        }

        override fun onDestroy() = Unit

        override fun getCount(): Int = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val row = rows.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_row_note)
            val layout = when (row.kind) {
                ChatDigest.Kind.User -> R.layout.widget_row_user
                ChatDigest.Kind.Agent -> R.layout.widget_row_agent
                ChatDigest.Kind.Tool -> R.layout.widget_row_tool
                ChatDigest.Kind.Note, ChatDigest.Kind.Problem -> R.layout.widget_row_note
            }
            return RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.row_text, row.text)
                if (row.kind == ChatDigest.Kind.Problem) setTextColor(R.id.row_text, ContextCompat.getColor(context, R.color.widget_accent))
                // Opens the chat (the list's template says how).
                setOnClickFillInIntent(R.id.row_text, Intent())
            }
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 4

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = false
    }
}
