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

    /**
     * Silent catch-up (every app open and the midnight alarm): reads only SMS from two days before the newest one
     * stored, so texts the receiver missed (the phone stopped it) are still counted. Stored ones are skipped.
     */
    fun catchUp(context: Context): Result {
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return Result(0, 0)
        val repo = (context.applicationContext as SpendApp).repo
        val newest = repo.store.newestSmsMillis() ?: return Result(0, 0)
        return runCatching { importInbox(context, sinceMillis = newest - 2L * 24 * 60 * 60 * 1000, markImported = false) }.getOrDefault(Result(0, 0))
    }

    /** Reads the whole inbox (or from [sinceMillis]), oldest first; [onProgress] gets (read so far, total) after each chunk. */
    fun importInbox(
        context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }, sinceMillis: Long = 0L, markImported: Boolean = true,
    ): Result {
        val repo = (context.applicationContext as SpendApp).repo
        var scanned = 0
        var stored = 0
        repo.importing = true
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                if (sinceMillis > 0) "${Telephony.Sms.DATE} >= ?" else null,
                if (sinceMillis > 0) arrayOf(sinceMillis.toString()) else null,
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
            if (markImported) repo.markImported()
        } finally {
            // Detection must still run on the next refresh even if the import failed part-way.
            repo.importing = false
        }
        return Result(scanned, stored)
    }
}
