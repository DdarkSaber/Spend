package com.girish.expensetracker.sms

import android.content.Context
import android.provider.Telephony
import com.girish.expensetracker.SpendApp
import java.time.Duration

/** Reads existing SMS from the inbox (first run, or "import history" in the app). Needs READ_SMS. */
object Importer {

    data class Result(val scanned: Int, val stored: Int)

    fun importInbox(context: Context, days: Long): Result {
        val repo = (context.applicationContext as SpendApp).repo
        val since = System.currentTimeMillis() - Duration.ofDays(days).toMillis()
        var scanned = 0
        var stored = 0
        repo.importing = true
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    scanned++
                    val sender = c.getString(0) ?: continue
                    val body = c.getString(1) ?: continue
                    if (repo.ingest(sender, body, c.getLong(2))) stored++
                }
            }
            repo.markImported()
        } finally {
            // Detection must still run on the next refresh even if the import failed part-way.
            repo.importing = false
        }
        return Result(scanned, stored)
    }
}
