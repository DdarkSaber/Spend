package com.girish.expensetracker.core

import java.time.LocalDate

/**
 * The one-time history file (JSONL). First line: {"format":"spend-archive","v":1}. Then one item per line:
 *  - {"t":"alert","channel":"archive:…","sender":"TC-SBICRD-T","body":"…","at":epochMs}  -> stored like an SMS
 *  - {"t":"row","channel":"archive:…","bank":"HDFC","last4":"5185","type":"ACCOUNT","day":"2026-04-16",
 *     "amountPaise":120015,"debit":true,"description":"…"}                             -> a statement row
 * An alert without a date is rejected (never stamped "today"); so is anything else malformed, with its line number.
 */
object Archive {

    const val FORMAT = "spend-archive"
    const val VERSION = 1

    sealed interface Item {
        val channel: String
        data class Alert(override val channel: String, val sender: String, val body: String, val atMillis: Long) : Item
        data class Row(
            override val channel: String, val source: Source, val day: LocalDate,
            val amountPaise: Long, val debit: Boolean, val description: String,
        ) : Item
    }

    data class Rejected(val line: Int, val reason: String)

    data class Parsed(val items: List<Item>, val rejected: List<Rejected>, val problem: String? = null)

    /** [lines] is the whole file; blank lines are skipped. */
    fun parse(lines: List<String>): Parsed {
        val numbered = lines.withIndex().filter { it.value.isNotBlank() }
        val header = numbered.firstOrNull() ?: return Parsed(emptyList(), emptyList(), "The file is empty")
        val h = runCatching { org.json.JSONObject(header.value) }.getOrNull()
        if (h == null || h.optString("format") != FORMAT) return Parsed(emptyList(), emptyList(), "Not a Spend history file")
        if (h.optInt("v", -1) != VERSION) return Parsed(emptyList(), emptyList(), "This history file needs a newer version of Spend")

        val items = ArrayList<Item>(numbered.size)
        val rejected = mutableListOf<Rejected>()
        for ((index, text) in numbered.drop(1)) {
            val line = index + 1
            val o = runCatching { org.json.JSONObject(text) }.getOrNull()
            if (o == null) { rejected += Rejected(line, "not JSON"); continue }
            val channel = o.optString("channel")
            if (!channel.startsWith("archive:") || channel.length <= "archive:".length) { rejected += Rejected(line, "channel must be archive:<name>"); continue }
            when (o.optString("t")) {
                "alert" -> {
                    val sender = o.optString("sender").trim()
                    val body = o.optString("body")
                    val at = if (o.has("at") && !o.isNull("at")) o.optLong("at", 0L) else 0L
                    when {
                        sender.isEmpty() -> rejected += Rejected(line, "alert has no sender")
                        body.isBlank() -> rejected += Rejected(line, "alert has no text")
                        at <= 0L -> rejected += Rejected(line, "alert has no date")
                        else -> items += Item.Alert(channel, sender, body, at)
                    }
                }
                "row" -> {
                    val type = runCatching { SourceType.valueOf(o.optString("type")) }.getOrNull()
                    val day = runCatching { LocalDate.parse(o.optString("day")) }.getOrNull()
                    val bank = o.optString("bank").trim().uppercase()
                    val last4 = o.optString("last4").trim()
                    val amount = o.optLong("amountPaise", 0L)
                    when {
                        bank.isEmpty() || last4.isEmpty() || type == null -> rejected += Rejected(line, "row needs bank, last4 and type")
                        day == null -> rejected += Rejected(line, "row has no date")
                        amount <= 0L -> rejected += Rejected(line, "row has no amount")
                        !o.has("debit") -> rejected += Rejected(line, "row doesn't say debit or credit")
                        else -> items += Item.Row(channel, Source(bank, last4, type), day, amount, o.optBoolean("debit"), o.optString("description"))
                    }
                }
                else -> rejected += Rejected(line, "unknown item type")
            }
        }
        return Parsed(items, rejected)
    }
}
