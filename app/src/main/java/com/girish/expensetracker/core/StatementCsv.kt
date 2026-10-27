package com.girish.expensetracker.core

import java.time.LocalDate

/** One line of a bank statement: date, amount (paise, positive), money out or in, and the bank's description. */
data class CsvRow(val day: LocalDate, val amountPaise: Long, val debit: Boolean, val description: String)

/**
 * Reads the CSV export that most Indian banks offer (net banking "download statement as CSV/Excel → save as CSV").
 * Columns are found by their headers, so HDFC, SBI, ICICI, Axis and Kotak layouts all work: a date column, a
 * description column, and either withdrawal + deposit columns or one amount column (with a Dr/Cr column or a sign).
 */
object StatementCsv {

    data class Result(val rows: List<CsvRow>, val skipped: Int, val problem: String?)

    private val dateHeader = Regex("^(?:txn |transaction |tran |value )?date$|^date\\b", RegexOption.IGNORE_CASE)
    private val descHeader = Regex("narration|description|particulars|details|remarks|transaction remarks", RegexOption.IGNORE_CASE)
    private val debitHeader = Regex("withdrawal|debit|dr amount|amount dr|paid out", RegexOption.IGNORE_CASE)
    private val creditHeader = Regex("deposit|credit|cr amount|amount cr|paid in", RegexOption.IGNORE_CASE)
    private val amountHeader = Regex("^(?:transaction |txn )?amount(?: \\(inr\\)| \\(rs\\.?\\))?$", RegexOption.IGNORE_CASE)
    private val typeHeader = Regex("^(?:dr ?/ ?cr|cr ?/ ?dr|type|transaction type|debit/credit)$", RegexOption.IGNORE_CASE)

    fun parse(text: String): Result {
        val lines = text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return Result(emptyList(), 0, "The file is empty.")
        val sep = listOf(',', ';', '\t').maxBy { s -> lines.take(30).sumOf { l -> l.count { it == s } } }
        val table = lines.map { split(it, sep) }

        val headerAt = table.indexOfFirst { row -> row.any { dateHeader.containsMatchIn(it.trim()) } && row.any { descHeader.containsMatchIn(it) } }
        if (headerAt < 0) return Result(emptyList(), 0, "Couldn't find the header row (a Date column and a Narration/Description column).")
        val header = table[headerAt].map { it.trim() }
        val date = header.indexOfFirst { dateHeader.containsMatchIn(it) }
        val desc = header.indexOfFirst { descHeader.containsMatchIn(it) }
        val debit = header.indexOfFirst { debitHeader.containsMatchIn(it) && !creditHeader.containsMatchIn(it) }
        val credit = header.indexOfFirst { creditHeader.containsMatchIn(it) && !debitHeader.containsMatchIn(it) }
        val amount = header.indexOfFirst { amountHeader.matches(it) }
        val type = header.indexOfFirst { typeHeader.matches(it) }
        if ((debit < 0 || credit < 0) && amount < 0) return Result(emptyList(), 0, "Couldn't find Withdrawal/Deposit or Amount columns.")

        val rows = mutableListOf<CsvRow>()
        var skipped = 0
        for (cells in table.drop(headerAt + 1)) {
            val day = cells.getOrNull(date)?.let(::parseDate)
            val text = cells.getOrNull(desc)?.trim().orEmpty()
            if (day == null) { skipped++; continue }
            val row = if (debit >= 0 && credit >= 0) {
                val out = money(cells.getOrNull(debit))
                val inn = money(cells.getOrNull(credit))
                when {
                    out != null && out > 0 -> CsvRow(day, out, true, text)
                    inn != null && inn > 0 -> CsvRow(day, inn, false, text)
                    else -> null
                }
            } else {
                val raw = cells.getOrNull(amount)
                val value = money(raw)
                val signedOut = raw?.trim()?.startsWith("-") == true
                val kind = cells.getOrNull(type)?.trim()?.uppercase().orEmpty()
                val isDebit = when {
                    kind.startsWith("DR") || kind.startsWith("DEBIT") || kind == "D" -> true
                    kind.startsWith("CR") || kind.startsWith("CREDIT") || kind == "C" -> false
                    raw?.uppercase()?.contains("DR") == true -> true
                    raw?.uppercase()?.contains("CR") == true -> false
                    else -> signedOut
                }
                value?.takeIf { it > 0 }?.let { CsvRow(day, it, isDebit, text) }
            }
            if (row == null) skipped++ else rows += row
        }
        return Result(rows, skipped, if (rows.isEmpty()) "No transactions found under the header row." else null)
    }

    private val hyphenUpi = Regex("^UPI-([^-]+?)-([\\w.\\-]+@[\\w.]+)", RegexOption.IGNORE_CASE)
    private val transferName = Regex("^(?:NEFT|IMPS|RTGS)\\s*(?:CR|DR)?-?(?:[A-Z0-9]+-)?([A-Z][A-Z .&]+?)(?:-|$)", RegexOption.IGNORE_CASE)

    /** The other side from a statement narration: "UPI-DOMINOS PIZZA-dominos@ybl-600..." -> "DOMINOS PIZZA". */
    fun payee(description: String, debit: Boolean): String? {
        val d = description.trim()
        hyphenUpi.find(d)?.let { return it.groupValues[1].trim() }
        transferName.find(d)?.let { return it.groupValues[1].trim() }
        return Generic.counterparty(d, if (debit) Direction.DEBIT else Direction.CREDIT) ?: d.take(40).ifBlank { null }
    }

    private fun money(raw: String?): Long? {
        val s = raw?.uppercase()?.replace(Regex("[^0-9.]"), "")?.trim('.')?.takeIf { it.isNotEmpty() } ?: return null
        val parts = s.split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        return rupees * 100 + (parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L)
    }

    private fun parseDate(raw: String): LocalDate? {
        val s = raw.trim().substringBefore(' ').takeIf { Regex("\\d").containsMatchIn(it) } ?: return null
        return Dates.parse(s) ?: Dates.parse(raw.trim().take(11))
    }

    /** CSV split that respects quotes ("1,234.00" stays one cell). */
    private fun split(line: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == sep && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(ch)
            }
            i++
        }
        out += cell.toString()
        return out
    }
}
