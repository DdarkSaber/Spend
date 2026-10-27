package com.girish.expensetracker.notify

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.girish.expensetracker.R
import com.girish.expensetracker.SpendApp
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.ui.MainActivity
import com.girish.expensetracker.ui.count
import com.girish.expensetracker.ui.rupees
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.concurrent.thread

/**
 * Opt-in evening summary at 9 pm: today's spend, how many payments, the biggest category, and the month so far.
 * One inexact alarm a day, only while the digest is switched on.
 */
class DigestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "digest") {
            try {
                if (Digest.enabled(context)) {
                    Digest.post(context)
                    Digest.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

object Digest {

    private const val PREFS = "ui"
    private const val KEY = "digest"
    private const val CHANNEL = "digest"
    private const val ID = 3

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (on) schedule(context) else context.getSystemService(AlarmManager::class.java).cancel(intent(context))
    }

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 3, Intent(context, DigestReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Next 9 pm; inexact, so Android can batch it with other wake-ups. */
    fun schedule(context: Context) {
        if (!enabled(context)) return
        val zone = ZoneId.systemDefault()
        var at = ZonedDateTime.now(zone).with(LocalTime.of(21, 0))
        if (!at.isAfter(ZonedDateTime.now(zone))) at = at.plusDays(1)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, at.toInstant().toEpochMilli(), intent(context))
    }

    fun post(context: Context) {
        val snap = (context.applicationContext as SpendApp).repo.snapshot(days = 40)
        val today = snap.rows.filter { it.txn.day == snap.today && Ledger.todayAmount(it.txn) > 0 }
        val top = today.groupBy { it.txn.category }.mapValues { (_, r) -> r.sumOf { Ledger.todayAmount(it.txn) } }.maxByOrNull { it.value }
        val title = if (today.isEmpty()) "No spending today" else "Today ${rupees(snap.todayTotal)} · ${count(today.size, "payment")}"
        val budget = snap.budgets.firstOrNull { it.category == null }?.let { " · ${(it.share * 100).toInt()}% of budget" }.orEmpty()
        val text = listOfNotNull(top?.let { "Most on ${it.key} ${rupees(it.value)}" }, "Month ${rupees(snap.monthTotal)}$budget").joinToString(" · ")

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Evening summary", NotificationManager.IMPORTANCE_LOW).apply { description = "Today's spend at 9 pm" })
        }
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            ID,
            Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_spend).setContentTitle(title).setContentText(text)
                .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build(),
        )
    }
}
