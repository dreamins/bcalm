package com.example.anxietytracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.format.DateTimeFormatter

/** Classic home-screen widget: ten number buttons that log an entry with carried-over values. */
class BcalmWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Store.init(context)
        val views = build(context)
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
    }

    companion object {
        const val ACTION_LOG = "com.example.anxietytracker.WIDGET_LOG"
        const val EXTRA_N = "n"
        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        private val CELL_IDS = intArrayOf(
            R.id.widget_n1, R.id.widget_n2, R.id.widget_n3, R.id.widget_n4, R.id.widget_n5,
            R.id.widget_n6, R.id.widget_n7, R.id.widget_n8, R.id.widget_n9, R.id.widget_n10,
        )

        private fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_quick_log)
            for (i in 1..10) {
                val intent = Intent(ctx, WidgetLogReceiver::class.java)
                    .setAction(ACTION_LOG).putExtra(EXTRA_N, i)
                val pi = PendingIntent.getBroadcast(ctx, 100 + i, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                v.setOnClickPendingIntent(CELL_IDS[i - 1], pi)
            }
            val open = PendingIntent.getActivity(ctx, 200,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.widget_title, open)
            val last = Store.entries.value.lastOrNull { it.anxiety != null }
            v.setTextViewText(R.id.widget_status,
                if (last == null) "No entries yet" else "Last: ${last.anxiety} · ${last.dateTime.format(timeFmt)}")
            return v
        }

        /** Redraws every placed instance of the widget (cheap no-op when none exist). */
        fun refresh(ctx: Context) {
            try {
                val app = ctx.applicationContext
                val mgr = AppWidgetManager.getInstance(app)
                val ids = mgr.getAppWidgetIds(ComponentName(app, BcalmWidget::class.java))
                if (ids.isEmpty()) return
                val views = build(app)
                for (id in ids) mgr.updateAppWidget(id, views)
            } catch (e: Exception) {
                // widget refresh must never break logging
            }
        }
    }
}

/** Not exported: only the widget's own PendingIntents can trigger a log. */
class WidgetLogReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BcalmWidget.ACTION_LOG) return
        val n = intent.getIntExtra(BcalmWidget.EXTRA_N, 0)
        if (n !in 1..10) return
        Store.init(context)
        Store.quickLog(context, n)   // adds the entry, which also refreshes the widget and syncs
    }
}
