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
 * Only bank alerts are kept as text: anything that names no bank (personal SMS, other apps' chats, one-time codes) is
 * logged as lengths only, see [redact].
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
            File(dir, "alerts-${LocalDate.now()}.jsonl").appendText(redact(entry).toString() + "\n")
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

    private val textKeys = listOf("title", "text", "bigText", "candidate", "extras", "droppedText")
    private val otp = Regex("\\bOTP\\b|one[- ]time|verification code|\\bcode\\b|\\bpassword\\b|\\bPIN\\b|\\bCVV\\b", RegexOption.IGNORE_CASE)
    private val digitRun = Regex("\\d{4,8}")
    private val moneyLike = Regex(
        "(?:₹|Rs\\.?|INR)\\s*[\\d,]+.*?(?:debited|credited|spent|paid|sent|received|withdrawn|charged|deducted|refund)" +
            "|(?:debited|credited|spent|paid|sent|received|withdrawn|charged|deducted|refund).*?(?:₹|Rs\\.?|INR)\\s*[\\d,]+",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * What may be written to the log (and, for lines logged by older versions, to the debug bundle).
     *  - An entry that names no bank keeps no text: each text field becomes its length, a personal sender becomes
     *    "(masked)" (a registered sender header such as "VM-SWIGGY-S" is kept), and "moneyLike" says whether the text
     *    read like a transaction, so a bank sender the app doesn't know yet still shows up.
     *  - One-time codes: in any text that mentions a code, runs of 4 to 8 digits are replaced by "#".
     */
    fun redact(entry: JSONObject): JSONObject {
        if (entry.optBoolean("masked")) return entry
        val kind = if (entry.isNull("kind")) "" else entry.optString("kind")
        if (kind != "sms" && kind != "notif") return entry
        val texts = textKeys.filter { !entry.isNull(it) }.associateWith { entry.optString(it) }
        val sender = if (entry.isNull("sender")) "" else entry.optString("sender")
        val banked = when (kind) {
            "sms" -> com.girish.expensetracker.core.SmsReader.bankOf(sender) != null
            else -> !entry.isNull("bankGuess") || texts.values.any {
                com.girish.expensetracker.core.BankFromText.code(it) != null || com.girish.expensetracker.core.SmsReader.bankOf(it) != null
            }
        }
        if (!banked) {
            entry.put("moneyLike", texts.values.any { moneyLike.containsMatchIn(it) })
            texts.forEach { (k, v) -> entry.remove(k); entry.put(k + "Len", v.length) }
            if (kind == "sms" && !com.girish.expensetracker.core.SmsReader.isSenderHeader(sender)) entry.put("sender", "(masked)")
            entry.put("masked", true)
        } else if (texts.values.any { otp.containsMatchIn(it) }) {
            texts.forEach { (k, v) -> entry.put(k, digitRun.replace(v) { "#".repeat(it.value.length) }) }
        }
        return entry
    }

    /** One stored log line, redacted again by today's rules (lines written by older versions held everything). */
    fun redactLine(line: String): String =
        runCatching { redact(JSONObject(line)).toString() }.getOrDefault("{\"kind\":\"unreadable\",\"masked\":true}")

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
