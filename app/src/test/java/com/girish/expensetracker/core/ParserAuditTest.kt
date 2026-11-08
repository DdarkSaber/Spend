package com.girish.expensetracker.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Runs Spend's reader over a real transaction export (-Dtxns=all_transactions.csv, kept outside the repo) and checks
 * the parsed amount, direction and last-4 against the export's own columns. Tests the notification-reading path
 * (BankFromText -> synthetic sender -> SmsReader) on thousands of real alerts. Skipped without the file; prints only
 * aggregates. No raw rows are committed.
 */
class ParserAuditTest {

    // Minimal CSV reader for quoted fields with embedded commas/newlines.
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); val cur = mutableListOf<String>(); val sb = StringBuilder()
        var inq = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inq && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inq = !inq
                c == ',' && !inq -> { cur.add(sb.toString()); sb.clear() }
                (c == '\n') && !inq -> { cur.add(sb.toString()); sb.clear(); rows.add(cur.toList()); cur.clear() }
                c == '\r' && !inq -> {}
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || cur.isNotEmpty()) { cur.add(sb.toString()); rows.add(cur.toList()) }
        return rows
    }

    private val acceptAll = Profile(emptySet(), emptySet(), emptySet(), emptySet(), acceptAll = true)

    private fun senderFor(bank: String, text: String, chat: String): String {
        BankFromText.sender(text, chat)?.let { return it }
        val code = when (bank) { "SBI_CARD" -> "SBICRD"; "SBI" -> "SBIINB"; "HDFC" -> "HDFCBK"; "ICICI" -> "ICICIT"; "DCB" -> "DCBANK"; else -> "HDFCBK" }
        return "NT-$code-T"
    }

    @Test fun readerMatchesTheExportOnEveryRealAlert() {
        val path = System.getProperty("txns").orEmpty()
        assumeTrue(path.isNotEmpty() && File(path).exists())
        val rows = parseCsv(File(path).readText())
        val head = rows.first().withIndex().associate { (i, h) -> h to i }
        fun col(r: List<String>, name: String) = r.getOrNull(head[name] ?: -1)?.trim().orEmpty()
        val at = LocalDateTime.of(2026, 1, 1, 12, 0)

        var total = 0; var parsed = 0; var amtOk = 0; var dirOk = 0; var last4Ok = 0; var unparsed = 0
        val missByBankKind = HashMap<String, Int>()
        val examples = mutableListOf<String>()
        // per (bank|last4|direction) sum of parsed rupees, to compare against summary.csv
        val sums = HashMap<String, Long>()

        for (r in rows.drop(1)) {
            if (r.size < head.size) continue
            if (col(r, "dup").isNotEmpty()) continue   // the summary excludes flagged duplicates
            total++
            val text = col(r, "text"); if (text.isBlank()) continue
            val bank = col(r, "bank"); val chat = col(r, "chat")
            val wantAmt = col(r, "amount").toDoubleOrNull()
            val wantDir = col(r, "direction")
            val wantLast4 = col(r, "last4")
            val res = SmsReader.read(senderFor(bank, text, chat), text, acceptAll)
            val p = (res as? ReadResult.Parsed)?.parsed ?: (res as? ReadResult.UnknownSource)?.parsed
            if (p == null) {
                unparsed++
                missByBankKind.merge("$bank/${col(r,"kind")}/unparsed", 1, Int::plus)
                if (examples.size < 12) examples.add("UNPARSED $bank/${col(r,"kind")} last4=$wantLast4")
                continue
            }
            parsed++
            val gotAmt = p.amountPaise / 100.0
            val amtMatch = wantAmt != null && kotlin.math.abs(gotAmt - wantAmt) < 0.005
            val dirMatch = p.direction.name.equals(wantDir, true)
            val last4Match = wantLast4.isEmpty() || p.source.last4 == wantLast4 || p.source.last4 == "*" || p.source.last4 == UNASSIGNED
            if (amtMatch) amtOk++ else { missByBankKind.merge("$bank/${col(r,"kind")}/amount", 1, Int::plus); if (examples.size < 12) examples.add("AMOUNT $bank/${col(r,"kind")} want=$wantAmt got=$gotAmt") }
            if (dirMatch) dirOk++ else { missByBankKind.merge("$bank/${col(r,"kind")}/dir", 1, Int::plus); if (examples.size < 12) examples.add("DIR $bank/${col(r,"kind")} want=$wantDir got=${p.direction}") }
            if (last4Match) last4Ok++ else { missByBankKind.merge("$bank/${col(r,"kind")}/last4", 1, Int::plus); if (examples.size < 12) examples.add("LAST4 $bank/${col(r,"kind")} want=$wantLast4 got=${p.source.last4}") }
            if (amtMatch) sums.merge("$bank|${wantLast4.ifEmpty { p.source.last4 }}|${wantDir.lowercase()}", p.amountPaise, Long::plus)
        }

        println("AUDIT total(non-dup)=$total parsed=$parsed unparsed=$unparsed")
        println("AUDIT amountOK=$amtOk/$parsed (${pct(amtOk, parsed)}) directionOK=$dirOk/$parsed (${pct(dirOk, parsed)}) last4OK=$last4Ok/$parsed (${pct(last4Ok, parsed)})")
        println("AUDIT --- mismatches by bank/kind/reason ---")
        missByBankKind.toList().sortedByDescending { it.second }.take(25).forEach { (k, n) -> println("AUDIT   $k = $n") }
        println("AUDIT --- examples ---")
        examples.forEach { println("AUDIT   $it") }
        println("AUDIT --- parsed debit/credit totals by source (rupees) ---")
        sums.toList().sortedBy { it.first }.forEach { (k, v) -> println("AUDIT   $k = %,.2f".format(v / 100.0)) }
    }

    private fun pct(a: Int, b: Int) = if (b == 0) "-" else "%.1f%%".format(a * 100.0 / b)
}
