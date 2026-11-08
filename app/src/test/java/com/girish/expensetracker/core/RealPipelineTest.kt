package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Runs the whole v1.2 snapshot pipeline over a real SMS export (-Dcorpus=<tsv>, kept outside the repo), to check that
 * the new cross-channel dedupe (L2) and transfer pairing behave on real data. Skipped unless the file is given, so CI
 * is unaffected. Prints only aggregates, never rows.
 */
class RealPipelineTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun fullPipelineOnRealInboxIsSaneAndTransferPairingIsConservative() {
        val path = System.getProperty("corpus").orEmpty()
        assumeTrue(path.isNotEmpty() && File(path).exists())
        val inbox = File(path).readLines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            InboxSms(p[0], p[1], LocalDateTime.ofInstant(Instant.ofEpochMilli(p[2].toLong()), zone))
        }
        val profile = ProfileDetector.detect(inbox).toProfile()

        // L0: same text across channels/time (all one channel here, so this only removes exact same-channel repeats).
        data class A(val s: String, val b: String, val at: Long, val ch: String)
        val alerts = inbox.map { A(it.sender, it.body, it.at.atZone(zone).toInstant().toEpochMilli(), "sms") }
        val l0 = SameText.keep(alerts, { it.s }, { it.b }, { it.at }, { it.ch })

        // read -> classify
        data class RC(val txn: Txn, val ch: String)
        val read = l0.mapNotNull { a ->
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(a.at), zone)
            when (val r = SmsReader.read(a.s, a.b, profile)) {
                is ReadResult.Parsed -> RC(Classifier.classify(r.parsed, at, profile), a.ch)
                else -> null
            }
        }
        val l1 = Dedupe.apply(read.map { it.txn })
        // align channels back after L1 by identity
        val chOf = java.util.IdentityHashMap<ParsedSms, String>().apply { read.forEach { put(it.txn.parsed, it.ch) } }
        val l1rc = l1.map { RC(it, chOf[it.parsed] ?: "sms") }
        val l2 = FactDedupe.apply(l1rc, { it.txn }, { it.ch })

        val own: (Source) -> Boolean = profile::isKnown
        val noPair = Owe.settle(l2.kept.map { it.txn }).txns
        val paired = Owe.settle(Transfers.pair(l2.kept.map { it.txn }, own)).txns

        fun monthSpend(ts: List<Txn>) = ts.sumOf { Ledger.monthAmount(it) }
        val transfersAdded = paired.count { it.kind == Kind.TRANSFER } - noPair.count { it.kind == Kind.TRANSFER }

        println("REAL inbox=${inbox.size} afterL0=${l0.size} parsed=${read.size} afterL1=${l1.size} afterL2=${l2.kept.size}")
        println("REAL L2collapses=${l2.collapses.size} transfersAddedByPairing=$transfersAdded")
        println("REAL monthSpend noPair=${monthSpend(noPair)/100} withPairing=${monthSpend(paired)/100}")

        // Invariants that must hold on any real single-channel history:
        assertEquals("L2 must not collapse anything on single-channel data", 0, l2.collapses.size)
        assertEquals(l1.size, l2.kept.size)
        // Transfer pairing only moves money out of spend/income (never invents spend), so the month total can't rise.
        assertTrue("pairing raised the month total", monthSpend(paired) <= monthSpend(noPair))
        // No negative balances leaked through.
        assertTrue(paired.none { Ledger.monthAmount(it) < 0 && it.kind == Kind.SPEND })
    }
}
