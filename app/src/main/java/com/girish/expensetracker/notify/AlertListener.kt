package com.girish.expensetracker.notify

import android.app.Notification
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.girish.expensetracker.SpendApp
import com.girish.expensetracker.data.Audit
import java.util.concurrent.Executors

/**
 * Reads bank alerts that never arrive as SMS: Truecaller Business Chat, RCS in Google Messages, bank emails in Gmail.
 * Only apps on the owner's allowlist are looked at; nothing is sent anywhere (the app has no internet permission).
 * Swiping a notification away doesn't matter: it is read when posted.
 */
class AlertListener : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = applicationContext as? SpendApp ?: return
        val pkg = sbn.packageName
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
        @Suppress("DEPRECATION")
        val messages = (extras.getParcelableArray(Notification.EXTRA_MESSAGES) as Array<Parcelable>?)
            ?.mapNotNull { (it as? android.os.Bundle)?.getCharSequence("text")?.toString() }.orEmpty()
        val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val at = sbn.postTime

        worker.execute {
            runCatching {
                val repo = app.repo
                if (repo.alertApps().none { it.pkg == pkg && it.enabled }) return@runCatching
                if (summary || ongoing) return@runCatching Audit.skipped(this, pkg, title, if (summary) "group-summary" else "ongoing")
                // Each part is read on its own; Gmail's big text is the subject plus snippet.
                val candidates = (if (pkg == GMAIL) listOf(big ?: text) else listOf(big ?: text) + lines + messages)
                    .filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                var stored = emptyList<Long>()
                for (c in candidates) {
                    val o = repo.ingestNotification(pkg, title, c, at)
                    Audit.notification(this, pkg, title, text, big, o)
                    o.smsId?.let { stored = stored + it }
                }
                if (stored.isNotEmpty()) {
                    Notifier.refresh(this)
                    stored.forEach { runCatching { QuickTag.maybePost(this, it) } }
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val GMAIL = "com.google.android.gm"

        /** Whether the owner has given this app notification access. */
        fun accessGranted(context: android.content.Context): Boolean =
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
