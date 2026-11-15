package com.girish.expensetracker.core

import java.time.LocalDate

/**
 * Reads Spend's own "Export CSV" back (date,time,kind,category,payee,source,amount_rupees,...), so a ledger exported
 * before an uninstall can be brought back. The export holds no raw alert text, so rows come back as statement rows
 * (skipped wherever an SMS already covers the same account, amount and day) and cash entries as cash entries.
 */
object SpendExport {

    data class Entry(val day: LocalDate, val source: Source?, val amountPaise: Long, val debit: Boolean, val payee: String, val category: String)

    data class Result(val rows: List<Entry>, val skipped: Int)

    private const val HEADER = "date,time,kind,category,payee,source,amount_rupees"

    fun isExport(text: String): Boolean = text.trimStart('﻿').lineSequence().firstOrNull()?.trim()?.startsWith(HEADER) == true

    fun parse(text: String): Result {
        val lines = text.trimStart('﻿').lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.drop(1).toList()
        val rows = mutableListOf<Entry>()
        var skipped = 0
        for (line in lines) {
            val c = cells(line)
            val day = c.getOrNull(0)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val paise = c.getOrNull(6)?.toBigDecimalOrNull()?.movePointRight(2)?.toLong()
            val layout = c.getOrNull(9).orEmpty()
            if (day == null || paise == null || paise <= 0) { skipped++; continue }
            val source = source(c.getOrNull(5).orEmpty())
            if (source == null && c.getOrNull(5) != "Cash") { skipped++; continue }
            rows += Entry(day, source, paise, debit = !isCredit(layout, c.getOrNull(2).orEmpty()), payee = c.getOrNull(4).orEmpty(), category = c.getOrNull(3).orEmpty())
        }
        return Result(rows, skipped)
    }

    /** Money in: the layouts that read a credit (or, for an unknown layout, a kind that is only ever money in). */
    private fun isCredit(layout: String, kind: String): Boolean {
        val l = layout.uppercase()
        if (l.isNotEmpty() && l != "MANUAL") return "CREDIT" in l || "RECEIVED" in l || "DEPOSIT" in l || "CARD_PAYMENT" in l || "REFUND" in l
        return kind in setOf("INCOME", "PERSON_CREDIT", "REFUND")
    }

    /** The export's source label back to a source; null for cash. Mirrors ui.sourceLabel. */
    fun source(label: String): Source? {
        val s = label.trim()
        Regex("^(\\S+) card · not assigned$").matchEntire(s)?.let { return Source(it.groupValues[1], UNASSIGNED, SourceType.CARD) }
        Regex("^(\\S+) card (\\S+)$").matchEntire(s)?.let { return Source(it.groupValues[1], it.groupValues[2], SourceType.CARD) }
        Regex("^(\\S+) cards?$").matchEntire(s)?.let { return Source(it.groupValues[1], "*", SourceType.CARD) }
        Regex("^(\\S+) a/c (\\S+)$").matchEntire(s)?.let { return Source(it.groupValues[1], it.groupValues[2], SourceType.ACCOUNT) }
        return null
    }

    /** One CSV line: quoted cells with doubled quotes; a leading ' (the export's formula guard) is removed. */
    private fun cells(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && line.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out.map { if (it.startsWith("'") && it.length > 1 && it[1] in "=+-@") it.substring(1) else it }
    }
}
