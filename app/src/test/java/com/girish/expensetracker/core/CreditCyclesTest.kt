package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Phase 5 cases: a missed statement, self-heal, add-on groups, two bills at one bank. */
class CreditCyclesTest {

    private val sbi = Source("SBI", "1152", SourceType.CARD)
    private val icici = Source("ICICI", "9999", SourceType.CARD)
    private val icici2 = Source("ICICI", "8008", SourceType.CARD)
    private fun at(d: String, h: Int = 12) = LocalDate.parse(d).atTime(h, 0)
    private val me = Profile(emptySet(), setOf(sbi, icici, icici2), emptySet(), emptySet())

    private fun spendSms(sender: String, body: String, d: String): Txn {
        val r = SmsReader.read(sender, body, me) as ReadResult.Parsed
        return Classifier.classify(r.parsed, at(d), me)
    }

    @Test fun freshStatementIsNotEstimated() {
        val stmt = CardStatement("SBI", "52", LocalDate.of(2026, 10, 1), 12_000_00L, 600_00L, LocalDate.of(2026, 10, 21))
        val h = Credit.health(sbi, emptyList(), listOf(stmt), emptyList(), null, null, LocalDate.of(2026, 10, 10))
        assertFalse(h.statementStale)
        assertFalse(h.balanceEstimated)
        assertNull(h.missedSince)
        assertEquals(stmt, h.currentStatement)
    }

    @Test fun missedStatementShowsLastSeenAndEstimatedBalance() {
        // Statement 1 Oct, next one due ~1 Nov; by 20 Nov nothing newer has arrived.
        val stmt = CardStatement("SBI", "52", LocalDate.of(2026, 10, 1), 12_000_00L, 600_00L, LocalDate.of(2026, 10, 21))
        val h = Credit.health(sbi, emptyList(), listOf(stmt), emptyList(), null, null, LocalDate.of(2026, 11, 20))
        assertTrue(h.statementStale)
        assertTrue(h.balanceEstimated)
        assertEquals(LocalDate.of(2026, 11, 1), h.missedSince)
        assertNull(h.currentStatement)          // UI shows "last statement seen 1 Oct" instead
        assertEquals(stmt, h.statement)
    }

    @Test fun newerStatementHealsAStaleOne() {
        val old = CardStatement("SBI", "52", LocalDate.of(2026, 10, 1), 12_000_00L, 600_00L, LocalDate.of(2026, 10, 21))
        val fresh = CardStatement("SBI", "52", LocalDate.of(2026, 11, 1), 8_000_00L, 400_00L, LocalDate.of(2026, 11, 21))
        val h = Credit.health(sbi, emptyList(), listOf(old, fresh), emptyList(), null, null, LocalDate.of(2026, 11, 5))
        assertFalse(h.statementStale)
        assertFalse(h.balanceEstimated)
        assertEquals(8_000_00L, h.outstandingPaise)   // the new statement's total, not the old one's
        assertEquals(fresh, h.currentStatement)
    }

    @Test fun midCycleLimitMessageHealsAStaleStatement() {
        val stmt = CardStatement("ICICI", "9999", LocalDate.of(2026, 10, 3), 2_000_00L, 100_00L, LocalDate.of(2026, 10, 21))
        // Past when the next statement was due, but an available-limit SMS arrived: balance is current, not estimated.
        val seen = listOf(LimitSeen("ICICI", "9999", 90_000_00L, at("2026-11-18")))
        val h = Credit.health(icici, emptyList(), listOf(stmt), seen, 100_000_00L, null, LocalDate.of(2026, 11, 20))
        assertFalse(h.statementStale)
        assertFalse(h.balanceEstimated)
        assertEquals(10_000_00L, h.outstandingPaise)   // 1,00,000 - 90,000
    }

    @Test fun paymentAtLeastTheBillClosesIt() {
        val stmt = CardStatement("SBI", "52", LocalDate.of(2026, 10, 1), 12_000_00L, 600_00L, LocalDate.of(2026, 10, 21))
        val pay = spendSms("VM-SBICRD-S", "Dear SBI Cardholder, payment of Rs. 15000.00 for your SBI Credit Card has been successfully processed. ref no : P1.", "2026-10-15")
        val h = Credit.health(sbi, listOf(pay), listOf(stmt), emptyList(), null, null, LocalDate.of(2026, 10, 19))
        assertEquals(0L, h.billLeftPaise)
        assertEquals(0L, h.outstandingPaise)
    }

    @Test fun addOnGroupIsNotInCardHealthButStillCountsAsSpend() {
        // An add-on card (bills paid by someone else) is excluded from health: the snapshot filters it out.
        val snap = com.girish.expensetracker.data.Snapshot(
            rows = emptyList(), unparsed = emptyList(), newSources = emptyList(), today = LocalDate.of(2026, 11, 8),
            owner = com.girish.expensetracker.data.Owner("confirmed", "Me", 28, setOf(sbi, icici)),
            sourceMeta = mapOf(sbi to SourceMeta(sbi, owner = SourceOwner.ADDON_PAID_BY_OTHER, label = "father")),
            lastActivity = mapOf(sbi to LocalDate.of(2026, 11, 1), icici to LocalDate.of(2026, 11, 1)),
        )
        assertEquals(listOf("9999"), snap.trackedCards.map { it.last4 })
    }

    @Test fun twoBillsAtOneBankStaySeparate() {
        val sA = CardStatement("ICICI", "9999", LocalDate.of(2026, 10, 3), 5_000_00L, 250_00L, LocalDate.of(2026, 10, 21))
        val sB = CardStatement("ICICI", "8008", LocalDate.of(2026, 10, 5), 7_000_00L, 350_00L, LocalDate.of(2026, 10, 23))
        val a = Credit.health(icici, emptyList(), listOf(sA, sB), emptyList(), 50_000_00L, null, LocalDate.of(2026, 10, 10))
        val b = Credit.health(icici2, emptyList(), listOf(sA, sB), emptyList(), 50_000_00L, null, LocalDate.of(2026, 10, 10))
        assertEquals(5_000_00L, a.billLeftPaise)
        assertEquals(7_000_00L, b.billLeftPaise)
    }
}
