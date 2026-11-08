package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * Spec §12: a 50k-alert history must go through the snapshot's hot path in under 1.5 s on the JVM. The hot path is
 * what runs on every refresh once the parse cache is warm: same-text filter (L0), cached readings, classify,
 * template-family dedupe (L1) and loan settling.
 */
class BenchmarkTest {

    private val profile = Profile(
        accounts = setOf(Source("HDFC", "1234", SourceType.ACCOUNT), Source("SBI", "5678", SourceType.ACCOUNT)),
        cards = setOf(Source("HDFC", "4321", SourceType.CARD), Source("SBI", "1111", SourceType.CARD), Source("ICICI", "9999", SourceType.CARD)),
        selfNames = setOf("TEST USER M P"),
        selfVpas = setOf("testuser@oksbi"),
    )

    private class Alert(val id: Long, val sender: String, val body: String, val at: LocalDateTime) {
        val millis = at.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    private val payees = listOf("RAVI KUMAR", "Blinkit", "ZOMATO", "SWIGGY", "AMAZON", "METRO RAIL", "FRIEND TWO", "UBER", "BESCOM", "RENT OWNER")
    private fun f(p: String) = DateTimeFormatter.ofPattern(p, Locale.ENGLISH)

    /** Real bank layouts with made-up names, digits and references; ~30 alerts a day for ~4.5 years. */
    private fun corpus(n: Int): List<Alert> {
        val rnd = Random(50_000)
        val start = LocalDateTime.of(2022, 4, 18, 8, 0)
        var at = start
        return (0 until n).map { i ->
            at = at.plusSeconds(rnd.nextLong(60, 5_400))
            val rs = rnd.nextInt(10, 20_000)
            val amt = "$rs.${rnd.nextInt(10, 99)}"
            val who = payees[rnd.nextInt(payees.size)]
            val ref = 100_000_000_000L + i
            val (sender, body) = when (rnd.nextInt(8)) {
                0 -> "VM-HDFCBK-T" to "Sent Rs.$amt From HDFC Bank A/C *1234 To $who On ${at.format(f("dd/MM/yy"))} Ref $ref Not You? Call 18002586161/SMS BLOCK UPI to 7308080808"
                1 -> "JD-SBIUPI-S" to "Dear UPI user A/C X5678 debited by $amt on date ${at.format(f("ddMMMyy"))} trf to $who Refno $ref If not u?"
                2 -> "VM-SBICRD-S" to "Rs.$amt spent on your SBI Credit Card ending 1111 at $who on ${at.format(f("dd/MM/yy"))}. Trxn."
                3 -> "JD-HDFCBK-S" to "Amount Spent: Rs.$rs with HDFC Bank Credit Card 4321 at $who on ${at.format(f("yyyy-MM-dd:HH:mm:ss"))} This transaction"
                4 -> "AD-HDFCBK-S" to "AutoPay (E-mandate) Success! For $who Txn Amt:INR$rs.00 Dt:${at.format(f("dd/MM/yyyy"))} Via:HDFC Bank CC 4321 Mandate ID: X$i Not You?"
                5 -> "JK-SBIUPI-S" to "Dear SBI User, your A/c X5678-credited by Rs.$rs on ${at.format(f("ddMMMyy"))} transfer from $who Ref No $ref -SBI"
                6 -> "BP-HDFCBK-S" to "UPDATE: INR $amt debited from HDFC Bank XX1234 on ${at.format(f("dd-MMM-yy")).uppercase()}. Info: ACH D- GROWW INVEST TECH PR-ABC$i. Avl bal:INR 1,000.00"
                else -> "VM-HDFCBK-S" to "Your OTP for transaction is ${rnd.nextInt(100000, 999999)}. Do not share it with anyone."
            }
            Alert(i.toLong() + 1, sender, body, at)
        }
    }

    private fun read(a: Alert) = Reading(
        SmsReader.read(a.sender, a.body, profile),
        CardFacts.statement(a.sender, a.body, a.at),
        CardFacts.limit(a.sender, a.body, a.at),
        SmsReader.bankOf(a.sender)?.let { UnusualSpend.foreignCharge(it, a.body, a.at) },
    )

    /** The refresh hot path, as in Repo.snapshot. Returns the kept transactions. */
    private fun hotPath(alerts: List<Alert>, cache: Map<Long, String>): List<Txn> {
        val kept = SameText.keep(alerts, { it.sender }, { it.body }, { it.millis }, { "sms" })
        val txns = ArrayList<Txn>(kept.size)
        for (a in kept) {
            val r = ParseCache.decode(cache.getValue(a.id))!!
            (r.result as? ReadResult.Parsed)?.let { txns += Classifier.classify(it.parsed, a.at, profile) }
        }
        return Owe.settle(Dedupe.apply(txns)).txns
    }

    @Test fun fiftyThousandAlertsUnderOneAndAHalfSeconds() {
        val alerts = corpus(50_000)
        val coldStart = System.nanoTime()
        val cache = alerts.associate { it.id to ParseCache.encode(read(it)) }
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000
        val parsedCount = cache.values.count { it.startsWith("1:R") }
        assertTrue("corpus should be mostly transactions, got $parsedCount", parsedCount > 40_000)

        repeat(2) { hotPath(alerts, cache) } // JIT warm-up, as on a phone after the first refresh
        val best = (1..3).minOf {
            val t0 = System.nanoTime()
            hotPath(alerts, cache)
            (System.nanoTime() - t0) / 1_000_000
        }
        println("BENCHMARK 50k alerts: first read + cache ${coldMs} ms, warm refresh best of 3 ${best} ms ($parsedCount transactions)")
        assertTrue("warm refresh took $best ms (limit 1500)", best < 1_500)
        // Same answer from the cache as from reading every alert again.
        val direct = Owe.settle(Dedupe.apply(alerts.mapNotNull { a ->
            (read(a).result as? ReadResult.Parsed)?.let { Classifier.classify(it.parsed, a.at, profile) }
        })).txns
        assertEquals(direct, hotPath(alerts, cache))
    }
}
