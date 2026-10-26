package com.girish.expensetracker.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.girish.expensetracker.R
import com.girish.expensetracker.SpendApp
import com.girish.expensetracker.ui.MainActivity
import com.girish.expensetracker.ui.rupees

/**
 * The one persistent notification: "Today ₹X · Month ₹Y", nothing else.
 * Building it never touches the database: it shows the last computed text from a tiny cache, and [refresh]
 * recomputes off the main thread. That keeps service start (boot, app open) instant whatever the inbox size.
 */
object Notifier {

    const val CHANNEL = "spend"
    const val ID = 1
    private const val PREFS = "notifier"
    private const val KEY_TITLE = "title"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Spend summary", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Today's and this month's spend"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    /** Cheap: uses the cached text (or a neutral placeholder before the first computation). Safe on the main thread. */
    fun cached(context: Context): Notification =
        build(context, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TITLE, null) ?: "Spend · updating…")

    private fun build(context: Context, title: String): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spend)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    /**
     * Recompute the two numbers and re-post (same ID, so the foreground service keeps owning it).
     * Does database work: call from a background thread (receivers' goAsync threads, Dispatchers.IO).
     */
    fun refresh(context: Context) {
        val snap = (context.applicationContext as SpendApp).repo.snapshot(days = 40)
        val title = "Today ${rupees(snap.todayTotal)} · Month ${rupees(snap.monthTotal)}"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TITLE, title).apply()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.areNotificationsEnabled()) nm.notify(ID, build(context, title))
    }
}
