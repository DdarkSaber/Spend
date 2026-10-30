package com.girish.expensetracker.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.girish.expensetracker.SpendApp
import com.girish.expensetracker.notify.Notifier
import kotlin.concurrent.thread

/** Woken by Android for every incoming SMS, even when the app is closed. Stores bank SMS and refreshes the notification. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        // Long SMS arrive as several parts from the same sender: join them back into one message.
        val messages = parts.groupBy { it.originatingAddress.orEmpty() }.map { (sender, list) ->
            Triple(sender, list.joinToString("") { it.messageBody.orEmpty() }, System.currentTimeMillis())
        }

        val app = context.applicationContext as SpendApp
        val pending = goAsync()
        thread(name = "sms-ingest") {
            try {
                val ids = messages.mapNotNull { (sender, body, at) -> app.repo.ingestId(sender, body, at) }
                if (ids.isNotEmpty()) Notifier.refresh(context)
                // A payment to a new or unsorted payee: offer a one-tap category (closes itself after 3 minutes).
                ids.forEach { runCatching { com.girish.expensetracker.notify.QuickTag.maybePost(context, it) } }
            } finally {
                pending.finish()
            }
        }
    }
}
