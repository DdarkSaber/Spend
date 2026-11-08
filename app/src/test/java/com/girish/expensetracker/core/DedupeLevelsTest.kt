package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/** The v1.0 Dedupe scan, kept verbatim as the reference the O(n) version must match exactly. */
private object LegacyDedupe {
    private val families: List<Set<Template>> = listOf(
        setOf(Template.HDFC_CARD_SPENT, Template.HDFC_CARD_AMOUNT_SPENT, Template.HDFC_AUTOPAY_SUCCESS),
        setOf(Template.HDFC_ACH_UPDATE, Template.HDFC_ACH_ALERT),
        setOf(Template.HDFC_CARD_PAYMENT_RECEIVED, Template.HDFC_CARD_PAYMENT_CREDITED),
    )
    private val preference = listOf(
        Template.HDFC_AUTOPAY_SUCCESS, Template.HDFC_CARD_AMOUNT_SPENT, Template.HDFC_CARD_SPENT,
        Template.HDFC_ACH_UPDATE, Template.HDFC_ACH_ALERT,
    )
    private class Entry(var txn: Txn, val templates: MutableSet<Template>)

    fun apply(txns: List<Txn>): List<Txn> {
        val kept = mutableListOf<Entry>()
        for (t in txns) {
            val p = t.parsed
            if (p.ref != null && kept.any { it.txn.parsed.template == p.template && it.txn.parsed.ref == p.ref }) continue
            val family = families.firstOrNull { p.template in it }
            val partner = family?.let { fam ->
                kept.firstOrNull { e ->
                    e.txn.parsed.template in fam && p.template !in e.templates &&
                        e.txn.parsed.source == p.source && e.txn.amountPaise == t.amountPaise && e.txn.day == t.day
                }
            }
            if (partner == null) kept += Entry(t, mutableSetOf(p.template)) else {
                partner.templates += p.template
                val winner = if (preference.indexOf(t.parsed.template) < preference.indexOf(partner.txn.parsed.template)) t else partner.txn
                partner.txn = winner.copy(receivedAt = minOf(partner.txn.receivedAt, t.receivedAt))
            }
        }
        return kept.map { it.txn }
    }
}

class DedupeLevelsTest {

    private val templates = listOf(
        Template.HDFC_CARD_SPENT, Template.HDFC_CARD_AMOUNT_SPENT, Template.HDFC_AUTOPAY_SUCCESS,
        Template.HDFC_ACH_UPDATE, Template.HDFC_ACH_ALERT, Template.HDFC_CARD_PAYMENT_RECEIVED,
        Template.HDFC_CARD_PAYMENT_CREDITED, Template.HDFC_UPI_SENT, Template.SBI_UPI_DEBIT,
    )
    private val sources = listOf(Source("HDFC", "4321", SourceType.CARD), Source("HDFC", "1234", SourceType.ACCOUNT))
    private val start = LocalDateTime.of(2026, 9, 1, 0, 0)

    private fun randomTxns(rnd: Random, n: Int): List<Txn> = (0 until n).map {
        val at = start.plusMinutes(rnd.nextLong(0, 4 * 24 * 60))
        val printed = when (rnd.nextInt(3)) { 0 -> null; 1 -> at.toLocalDate(); else -> at.toLocalDate().minusDays(rnd.nextLong(0, 2)) }
        val p = ParsedSms(
            templates[rnd.nextInt(templates.size)], Direction.DEBIT, 100L * rnd.nextInt(1, 4), sources[rnd.nextInt(2)],
            null, printed, if (rnd.nextInt(3) == 0) "R${rnd.nextInt(4)}" else null, null,
        )
        Txn(p, Kind.SPEND, null, "Other", at)
    }.sortedBy { it.receivedAt }

    @Test fun linearDedupeMatchesTheOriginalScanExactly() {
        val rnd = Random(20261108)
        repeat(3000) {
            val txns = randomTxns(rnd, rnd.nextInt(1, 40))
            assertEquals(LegacyDedupe.apply(txns), Dedupe.apply(txns))
        }
    }

    private data class A(val sender: String, val body: String, val at: Long, val channel: String)
    private val min = 60_000L
    private fun keep(vararg a: A) = SameText.keep(a.toList(), { it.sender }, { it.body }, { it.at }, { it.channel })

    @Test fun sameChannelRepeatWithinTenMinutesIsOne() {
        val body = "Rs.100 spent on card 4321"
        assertEquals(1, keep(A("VM-HDFCBK-S", body, 0, "sms"), A("VM-HDFCBK-S", body, 9 * min, "sms")).size)
        // Two real identical metro taps an hour apart on one channel both count.
        assertEquals(2, keep(A("VM-HDFCBK-S", body, 0, "sms"), A("VM-HDFCBK-S", body, 60 * min, "sms")).size)
    }

    @Test fun copyOnAnotherChannelWithinADayIsOneAndSmsWins() {
        val sms = A("VM-SBICRD-S", "Rs.1,250.00 spent on your SBI Credit Card ending 3652 at SHOP on 01/11/26.", 30 * min, "sms")
        val notif = A("NT-SBICRD-T", "  rs.1,250.00 spent on your SBI Credit Card ending 3652\nat SHOP on 01/11/26. ", 0, "notif:com.truecaller")
        assertEquals(listOf(sms), keep(notif, sms))
        val archive = A("TC-SBICRD-T", sms.body, 40 * min, "archive:truecaller")
        assertEquals(listOf(sms), keep(notif, sms, archive))
        // More than a day apart: two alerts.
        assertEquals(2, keep(notif.copy(at = 0), sms.copy(at = 25 * 60 * min)).size)
    }

    @Test fun differentTextIsNeverMerged() {
        assertEquals(2, keep(A("A", "Rs.100 spent", 0, "sms"), A("B", "Rs.101 spent", 0, "notif:x")).size)
    }

    @Test fun ordinarySmsHistoryIsUntouched() {
        val rnd = Random(7)
        val list = (0 until 2000).map { A("VM-HDFCBK-S", "Rs.${rnd.nextInt(50)} spent", it * 11 * min, "sms") }
        assertEquals(list, keep(*list.toTypedArray()))
    }

    @Test fun normalizeIgnoresCaseAndSpacing() {
        assertEquals("rs.10 spent at shop", SameText.normalize("  Rs.10\n spent  at\tSHOP "))
    }
}
