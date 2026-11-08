package com.girish.expensetracker.data

import android.content.Context
import com.girish.expensetracker.core.ReadResult
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Temporary audit log for the validation week: every allowlisted notification and every SMS, with what the app did
 * with it, one JSON object per line in getExternalFilesDir("audit")/alerts-YYYY-MM-DD.jsonl. Kept to 30 days and
 * 5 MB in all, switchable in More. Never leaves the phone unless the owner shares the debug bundle.
 * Remove after validation.
 */
object Audit {

    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val MAX_DAYS = 30L
    private const val PREFS = "audit"

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)

    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()

    fun dir(context: Context): File? = context.getExternalFilesDir("audit")

    fun files(context: Context): List<File> = dir(context)?.listFiles { f -> f.name.startsWith("alerts-") && f.name.endsWith(".jsonl") }
        ?.sortedBy { it.name }.orEmpty()

    @Synchronized
    fun log(context: Context, entry: JSONObject) {
        if (!enabled(context)) return
        runCatching {
            val dir = dir(context) ?: return
            entry.put("logged", System.currentTimeMillis())
            File(dir, "alerts-${LocalDate.now()}.jsonl").appendText(entry.toString() + "\n")
            rotate(context)
        }
    }

    private fun rotate(context: Context) {
        val cutoff = LocalDate.now().minusDays(MAX_DAYS).toString()
        val all = files(context).toMutableList()
        all.filter { it.name.removePrefix("alerts-").removeSuffix(".jsonl") < cutoff }.forEach { it.delete(); all.remove(it) }
        // Oldest days go first until the whole log fits; today's file is always kept.
        while (all.size > 1 && all.sumOf { it.length() } > MAX_BYTES) all.removeAt(0).delete()
    }

    fun result(r: ReadResult?): JSONObject = JSONObject().apply {
        when (r) {
            null -> put("result", JSONObject.NULL)
            is ReadResult.Parsed -> {
                put("result", "parsed"); put("template", r.parsed.template.name)
                put("source", "${r.parsed.source.bank} ${r.parsed.source.last4} ${r.parsed.source.type}")
                put("amountPaise", r.parsed.amountPaise); put("direction", r.parsed.direction.name)
            }
            is ReadResult.UnknownSource -> {
                put("result", "unknown-source"); put("template", r.parsed.template.name)
                put("source", "${r.parsed.source.bank} ${r.parsed.source.last4} ${r.parsed.source.type}")
                put("amountPaise", r.parsed.amountPaise)
            }
            is ReadResult.Unparsed -> put("result", "unparsed")
            ReadResult.Informational -> put("result", "informational")
            ReadResult.Promo -> put("result", "promo")
            ReadResult.NotBank -> put("result", "not-bank")
        }
    }

    private fun merge(into: JSONObject, from: JSONObject) = from.keys().forEach { into.put(it, from.get(it)) }

    fun notification(context: Context, pkg: String, title: String?, text: String?, bigText: String?, o: Repo.NotifOutcome, extras: String? = null) =
        log(context, JSONObject().apply {
            put("kind", "notif"); put("pkg", pkg); put("title", title ?: JSONObject.NULL); put("extras", extras ?: JSONObject.NULL)
            put("text", text ?: JSONObject.NULL); put("bigText", bigText ?: JSONObject.NULL)
            put("candidate", o.text); put("bankGuess", o.sender ?: JSONObject.NULL); put("gate", o.gate)
            put("decision", o.decision); put("smsId", o.smsId ?: JSONObject.NULL)
            merge(this, result(o.result))
        })

    fun skipped(context: Context, pkg: String, title: String?, why: String) =
        log(context, JSONObject().apply { put("kind", "notif"); put("pkg", pkg); put("title", title ?: JSONObject.NULL); put("decision", why) })

    fun sms(context: Context, sender: String, body: String, at: Long, smsId: Long?, r: ReadResult?) =
        log(context, JSONObject().apply {
            put("kind", "sms"); put("sender", sender); put("text", body); put("at", at)
            put("decision", if (smsId != null) "stored" else "not-stored"); put("smsId", smsId ?: JSONObject.NULL)
            merge(this, result(r))
        })

    /** Dedupe level 2 collapses, each logged once (kept in a small "seen" file next to the log). */
    @Synchronized
    fun collapses(context: Context, list: List<Pair<Row, Row>>) {
        if (list.isEmpty() || !enabled(context)) return
        val dir = dir(context) ?: return
        val seenFile = File(dir, "collapses-seen.txt")
        val seen = runCatching { seenFile.readLines().toHashSet() }.getOrDefault(HashSet())
        val fresh = list.filter { (k, d) -> key(k, d) !in seen }
        if (fresh.isEmpty()) return
        fresh.forEach { (kept, dropped) ->
            log(context, JSONObject().apply {
                put("kind", "l2-collapse")
                put("source", "${kept.txn.parsed.source.bank} ${kept.txn.parsed.source.last4}")
                put("amountPaise", kept.txn.amountPaise); put("direction", kept.txn.parsed.direction.name)
                put("keptChannel", kept.channel); put("keptDay", kept.txn.day.toString()); put("keptSmsId", kept.smsId ?: JSONObject.NULL)
                put("droppedChannel", dropped.channel); put("droppedDay", dropped.txn.day.toString()); put("droppedSmsId", dropped.smsId ?: JSONObject.NULL)
                put("droppedText", dropped.rawBody ?: JSONObject.NULL)
            })
        }
        runCatching { seenFile.appendText(fresh.joinToString("") { (k, d) -> key(k, d) + "\n" }) }
    }

    private fun key(k: Row, d: Row) = "${k.channel}:${k.smsId}:${k.txn.day}|${d.channel}:${d.smsId}:${d.txn.day}:${d.txn.amountPaise}"
}
