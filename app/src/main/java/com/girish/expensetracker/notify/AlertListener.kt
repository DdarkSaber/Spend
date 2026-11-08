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
        // The header and sub texts ("Business chat from SBI Card") name the bank when the text itself doesn't.
        val hint = listOf(Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_CONVERSATION_TITLE, Notification.EXTRA_TITLE_BIG)
            .mapNotNull { extras.getCharSequence(it)?.toString()?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        // Apps that draw their own card (Truecaller's transaction card) keep the merchant and amount only in the layout.
        val drawnViews = if (pkg == TRUECALLER) runCatching { viewTexts(n) }.getOrDefault(emptyList()) else emptyList()
        val drawn = drawnViews.map { it.first }
        // The amount's colour is the only sign of direction on Truecaller's card: from the text's own colour span, else the drawn view.
        val amountColor = if (pkg == TRUECALLER) (listOfNotNull(extras.getCharSequence(Notification.EXTRA_TITLE), extras.getCharSequence(Notification.EXTRA_TEXT))
            .firstNotNullOfOrNull { spanColor(it) } ?: drawnViews.firstOrNull { (t, c) -> c != null && moneyText.containsMatchIn(t) }?.second) else null
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
                val titleHint = (listOfNotNull(title) + hint).joinToString(" · ").ifEmpty { null }
                val extrasDump = (hint + drawn).joinToString(" | ") + (amountColor?.let { " | color=#%08X".format(it) } ?: "")
                var stored = emptyList<Long>()
                var passed = false
                for (c in candidates) {
                    val o = repo.ingestNotification(pkg, titleHint, c, at)
                    Audit.notification(this, pkg, title, text, big, o, extrasDump)
                    o.smsId?.let { stored = stored + it }
                    if (o.decision in setOf("stored", "dup-text", "dup-same-channel")) passed = true
                }
                // Nothing read as an alert: try it as a transaction card (merchant + amount + "Credit Card xx4415").
                if (!passed) {
                    val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    com.girish.expensetracker.core.TxnCard.rebuild(listOfNotNull(title, text, big) + lines + messages + drawn, titleHint, day, amountColor)?.let { rebuilt ->
                        val o = repo.ingestNotification(pkg, titleHint, rebuilt, at)
                        Audit.notification(this, pkg, title, text, big, o, "card:$extrasDump")
                        o.smsId?.let { stored = stored + it }
                    }
                }
                if (stored.isNotEmpty()) {
                    Notifier.refresh(this)
                    stored.forEach { runCatching { QuickTag.maybePost(this, it) } }
                }
            }
        }
    }

    /** On (re)connect, e.g. after an app update, read what is still in the shade; re-posts are dropped as usual. */
    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** Every text drawn in the notification's own layout (big view first), read by inflating it off screen. */
    private fun viewTexts(n: Notification): List<Pair<String, Int?>> {
        val out = LinkedHashMap<String, Int?>()
        fun walk(v: android.view.View) {
            if (v is android.widget.TextView) v.text?.let { cs ->
                cs.toString().trim().takeIf { it.isNotEmpty() }?.let { out.putIfAbsent(it, spanColor(cs) ?: v.currentTextColor) }
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        @Suppress("DEPRECATION")
        for (rv in listOfNotNull(n.bigContentView, n.contentView)) {
            runCatching { walk(rv.apply(applicationContext, android.widget.FrameLayout(applicationContext))) }
        }
        return out.toList()
    }

    /** The colour a styled text paints its amount in (a ForegroundColorSpan), if any. */
    private fun spanColor(cs: CharSequence): Int? {
        val s = cs as? android.text.Spanned ?: return null
        return s.getSpans(0, s.length, android.text.style.ForegroundColorSpan::class.java)
            .firstOrNull { span -> moneyText.containsMatchIn(s.subSequence(s.getSpanStart(span), s.getSpanEnd(span))) }?.foregroundColor
    }

    private val moneyText = Regex("(?:₹|Rs\\.?|INR)\\s*[\\d,]")

    companion object {
        const val GMAIL = "com.google.android.gm"
        const val TRUECALLER = "com.truecaller"

        /** Whether the owner has given this app notification access. */
        fun accessGranted(context: android.content.Context): Boolean =
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
