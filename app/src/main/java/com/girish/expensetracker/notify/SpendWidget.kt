package com.girish.expensetracker.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.girish.expensetracker.R
import com.girish.expensetracker.ui.MainActivity
import com.girish.expensetracker.ui.rupees
import kotlin.concurrent.thread

/**
 * Home-screen widget with the same two numbers as the notification. It never polls (updatePeriodMillis = 0):
 * [Notifier.refresh] pushes new numbers whenever they change.
 */
class SpendWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // A freshly placed widget: draw the cached numbers now, then recompute in the background.
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        render(context, manager, ids, prefs.getString(KEY_TODAY, null) ?: "–", prefs.getString(KEY_MONTH, null) ?: "–")
        val pending = goAsync()
        thread(name = "widget-refresh") {
            try {
                Notifier.refresh(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val PREFS = "widget"
        private const val KEY_TODAY = "today"
        private const val KEY_MONTH = "month"

        fun update(context: Context, todayPaise: Long, monthPaise: Long) {
            val today = rupees(todayPaise)
            val month = rupees(monthPaise)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TODAY, today).putString(KEY_MONTH, month).apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SpendWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, today, month)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, today: String, month: String) {
            val open = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_spend).apply {
                setTextViewText(R.id.widget_today, today)
                setTextViewText(R.id.widget_month, month)
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
