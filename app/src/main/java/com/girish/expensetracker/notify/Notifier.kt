package com.girish.expensetracker.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.girish.expensetracker.R
import com.girish.expensetracker.SpendApp
import com.girish.expensetracker.core.Alerts
import com.girish.expensetracker.data.Repo
import com.girish.expensetracker.data.Snapshot
import com.girish.expensetracker.ui.MainActivity
import com.girish.expensetracker.ui.rupees
import com.girish.expensetracker.ui.sourceLabel
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        val repo = (context.applicationContext as SpendApp).repo
        val snap = repo.snapshot(days = 40)
        val title = "Today ${rupees(snap.todayTotal)} · Month ${rupees(snap.monthTotal)}"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TITLE, title).apply()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.areNotificationsEnabled()) nm.notify(ID, build(context, title))
        SpendWidget.update(context, snap.todayTotal, snap.monthTotal)
        postAlerts(context, repo, snap)
    }

    // ---------------------------------------------------------------- alerts

    private const val ALERTS = "alerts"
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /**
     * Card utilisation over 30%, a bill due in 3 days or today, a budget at 80% / 100% or ahead of pace. Checked only
     * when the numbers change anyway (new SMS, midnight, app open), and each alert is posted once.
     */
    private fun postAlerts(context: Context, repo: Repo, snap: Snapshot) {
        val alerts = Alerts.credit(snap.cards, snap.today, ::sourceLabel, ::rupees) { it.format(dayMonth) } +
            Alerts.budgets(snap.budgets, snap.cycleStart, ::rupees) +
            Alerts.subscriptions(snap.subscriptions, snap.today, ::rupees)
        val sent = repo.alertsSent()
        val fresh = alerts.filter { it.key !in sent }
        if (fresh.isEmpty()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ALERTS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ALERTS, "Card and budget alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Credit card over 30% of limit, bills due, budgets running out"
                },
            )
        }
        if (nm.areNotificationsEnabled()) {
            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            fresh.forEach { a ->
                nm.notify(
                    a.key.hashCode(),
                    Notification.Builder(context, ALERTS)
                        .setSmallIcon(R.drawable.ic_stat_spend)
                        .setContentTitle(a.title)
                        .setContentText(a.text)
                        .setStyle(Notification.BigTextStyle().bigText(a.text))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .build(),
                )
            }
        }
        repo.markAlertsSent(fresh.map { it.key })
    }
}
