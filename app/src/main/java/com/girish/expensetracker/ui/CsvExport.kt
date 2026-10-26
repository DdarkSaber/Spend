package com.girish.expensetracker.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.data.Snapshot
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Writes the ledger to Download/Spend/spend-<timestamp>.csv. No storage permission is needed for app-created downloads. */
object CsvExport {

    fun export(context: Context, snap: Snapshot): String {
        val name = "spend-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".csv"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Spend")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create $name")
        context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
            w.write("date,time,kind,category,payee,source,amount_rupees,counts_today,counts_month,layout\n")
            for (row in snap.rows) {
                val t = row.txn
                val cells = listOf(
                    t.day.toString(),
                    t.receivedAt.toLocalTime().withNano(0).toString(),
                    t.kind.name,
                    t.category,
                    t.parsed.counterparty.orEmpty(),
                    sourceLabel(t.parsed.source),
                    rupeesPlain(t.amountPaise),
                    rupeesPlain(Ledger.todayAmount(t)),
                    rupeesPlain(Ledger.monthAmount(t)),
                    t.parsed.template.name,
                )
                w.write(cells.joinToString(",") { csv(it) })
                w.write("\n")
            }
        }
        return "Download/Spend/$name"
    }

    private fun rupeesPlain(paise: Long) = "%.2f".format(paise / 100.0)

    private fun csv(raw: String): String {
        // Text from SMS must never run as a formula when the file is opened in Excel or Sheets.
        val s = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r" && raw.toDoubleOrNull() == null) "'$raw" else raw
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
