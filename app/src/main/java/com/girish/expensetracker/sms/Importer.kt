package com.girish.expensetracker.sms

import android.content.Context
import android.provider.Telephony
import com.girish.expensetracker.SpendApp
import com.girish.expensetracker.data.Repo

/** Reads existing SMS from the inbox (first run, or "import history" in the app). Needs READ_SMS. */
object Importer {

    data class Result(val scanned: Int, val stored: Int)

    /** SMS stored per database transaction: big enough to be fast, small enough to show progress. */
    private const val CHUNK = 500

    /** Reads the whole inbox, oldest first; [onProgress] gets (read so far, total) after each chunk. */
    fun importInbox(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val repo = (context.applicationContext as SpendApp).repo
        var scanned = 0
        var stored = 0
        repo.importing = true
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null,
                null,
                "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                val total = c.count
                val chunk = ArrayList<Repo.Incoming>(CHUNK)
                fun flush() {
                    stored += repo.ingestAll(chunk)
                    chunk.clear()
                    onProgress(scanned, total)
                }
                while (c.moveToNext()) {
                    scanned++
                    val sender = c.getString(0) ?: continue
                    val body = c.getString(1) ?: continue
                    chunk += Repo.Incoming(sender, body, c.getLong(2))
                    if (chunk.size >= CHUNK) flush()
                }
                flush()
            }
            repo.markImported()
        } finally {
            // Detection must still run on the next refresh even if the import failed part-way.
            repo.importing = false
        }
        return Result(scanned, stored)
    }
}
