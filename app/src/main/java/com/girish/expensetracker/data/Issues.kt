package com.girish.expensetracker.data

import android.content.Context
import com.girish.expensetracker.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Report this payment": the owner taps what looks wrong on a payment's sheet, and everything needed to see why the
 * app read it that way is saved as one JSON line in getExternalFilesDir("audit")/issues.jsonl. The file goes into the
 * debug bundle; nothing is sent anywhere by the app.
 */
object Issues {

    /** What the owner can say is wrong, as shown on the payment sheet. */
    val REASONS = listOf("Not a spend", "Wrong amount", "Counted twice", "Wrong person or shop", "Wrong card or account", "Something else")

    fun file(context: Context): File? = Audit.dir(context)?.let { File(it, "issues.jsonl") }

    fun entry(row: Row, reason: String, now: Long = System.currentTimeMillis()): JSONObject = JSONObject().apply {
        val x = row.txn
        val p = x.parsed
        put("reported", now); put("reason", reason)
        put("smsId", row.smsId ?: JSONObject.NULL); put("manualId", row.manualId ?: JSONObject.NULL)
        put("channel", row.channel); put("alsoVia", JSONArray(row.alsoVia))
        put("text", row.rawBody ?: JSONObject.NULL)
        put("template", p.template.name); put("direction", p.direction.name); put("amountPaise", p.amountPaise)
        put("source", "${p.source.bank} ${p.source.last4} ${p.source.type}")
        put("counterparty", p.counterparty ?: JSONObject.NULL); put("printedDate", p.date?.toString() ?: JSONObject.NULL)
        put("ref", p.ref ?: JSONObject.NULL)
        put("kind", x.kind.name); put("category", x.category); put("payeeType", x.payeeType?.name ?: JSONObject.NULL)
        put("day", x.day.toString()); put("receivedAt", x.receivedAt.toString()); put("salary", x.isSalary)
        put("splitWith", x.splitWith.size); put("settlesPaise", x.settlesPaise); put("ownPaise", x.ownPaise)
    }

    /** Appends one report; false when the phone gave no place to write it. */
    @Synchronized
    fun report(context: Context, row: Row, reason: String): Boolean = runCatching {
        val f = file(context) ?: return false
        f.appendText(entry(row, reason).put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").toString() + "\n")
        true
    }.getOrDefault(false)

    fun count(context: Context): Int = runCatching { file(context)?.useLines { l -> l.count { it.isNotBlank() } } ?: 0 }.getOrDefault(0)
}
