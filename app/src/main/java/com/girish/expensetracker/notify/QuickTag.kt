package com.girish.expensetracker.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.girish.expensetracker.R
import com.girish.expensetracker.SpendApp
import com.girish.expensetracker.core.FixedCosts
import com.girish.expensetracker.core.Kind
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.ui.MainActivity
import com.girish.expensetracker.ui.plainRupeesExact
import kotlin.concurrent.thread

/**
 * Right after a payment to a new or uncategorised payee: a notification with the two likeliest categories and
 * "More". Ignored, it closes itself after 3 minutes and nothing changes.
 */
object QuickTag {

    private const val CHANNEL = "quicktag"
    private const val TIMEOUT_MS = 3 * 60 * 1000L
    const val EXTRA_SMS = "sms_id"
    const val EXTRA_CATEGORY = "category"

    fun maybePost(context: Context, smsId: Long) {
        val snap = (context.applicationContext as SpendApp).repo.snapshot(days = 120)
        val row = snap.rows.firstOrNull { it.smsId == smsId } ?: return
        val x = row.txn
        if (x.kind != Kind.SPEND || x.parsed.counterparty.isNullOrBlank()) return
        val key = FixedCosts.key(x)
        val seenBefore = snap.rows.any { it !== row && FixedCosts.key(it.txn) == key && it.txn.receivedAt < x.receivedAt }
        if (seenBefore && x.category != "Uncategorised") return

        val suggestions = suggest(snap.rows, row)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Quick tag", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Sort a new payment from the notification; closes by itself after 3 minutes"
                setSound(null, null)
            })
        }
        if (!nm.areNotificationsEnabled()) return
        val id = (smsId % Int.MAX_VALUE).toInt() + 1000
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spend)
            .setContentTitle("${plainRupeesExact(x.amountPaise)} · ${x.parsed.counterparty}")
            .setContentText(if (x.category == "Uncategorised") "What was this?" else "Filed as ${x.category}. Right?")
            .setTimeoutAfter(TIMEOUT_MS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open(context, smsId, id))
        suggestions.forEachIndexed { i, cat ->
            val tag = Intent(context, QuickTagReceiver::class.java).putExtra(EXTRA_SMS, smsId).putExtra(EXTRA_CATEGORY, cat)
            val pi = PendingIntent.getBroadcast(context, id * 4 + i, tag, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, cat, pi).build())
        }
        builder.addAction(Notification.Action.Builder(null, "More", open(context, smsId, id)).build())
        nm.notify(id, builder.build())
    }

    /** The guessed category first (when there is one), then the owner's most-used categories of the last 60 days. */
    private fun suggest(rows: List<Row>, row: Row): List<String> {
        val guess = row.txn.category.takeIf { it != "Uncategorised" }
        val since = row.txn.day.minusDays(60)
        val used = rows.filter { it.txn.kind == Kind.SPEND && it.txn.day >= since && it.txn.category != "Uncategorised" && Ledger.monthAmount(it.txn) > 0 }
            .groupingBy { it.txn.category }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return (listOfNotNull(guess) + used + listOf("Food", "Shopping")).distinct().take(2)
    }

    private fun open(context: Context, smsId: Long, id: Int) = PendingIntent.getActivity(
        context, id, Intent(context, MainActivity::class.java).putExtra(EXTRA_SMS, smsId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun dismiss(context: Context, smsId: Long) =
        context.getSystemService(NotificationManager::class.java).cancel((smsId % Int.MAX_VALUE).toInt() + 1000)
}

/** A category tapped in the quick-tag notification: file this one payment there, then close the notification. */
class QuickTagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val smsId = intent.getLongExtra(QuickTag.EXTRA_SMS, -1)
        val category = intent.getStringExtra(QuickTag.EXTRA_CATEGORY) ?: return
        if (smsId < 0) return
        val pending = goAsync()
        thread(name = "quick-tag") {
            try {
                val repo = (context.applicationContext as SpendApp).repo
                repo.overrideCategory(smsId, category, null)
                QuickTag.dismiss(context, smsId)
                Notifier.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}
