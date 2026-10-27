package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OweTest {

    private val acct = Source("HDFC", "1234", SourceType.ACCOUNT)

    private fun pay(day: String, rs: Long, to: String, kind: Kind = Kind.SPEND, split: List<SplitShare> = emptyList()) = Txn(
        ParsedSms(Template.HDFC_UPI_SENT, Direction.DEBIT, rs * 100, acct, to, LocalDate.parse(day), null, null),
        kind, PayeeType.PERSON, if (kind == Kind.LENT) "Lent" else "Food", LocalDate.parse(day).atTime(12, 0), splitWith = split,
    )

    private fun got(day: String, rs: Long, from: String) = Txn(
        ParsedSms(Template.HDFC_UPI_CREDIT, Direction.CREDIT, rs * 100, acct, from, LocalDate.parse(day), null, null),
        Kind.PERSON_CREDIT, PayeeType.PERSON, "Paid back", LocalDate.parse(day).atTime(18, 0),
    )

    @Test fun splitCountsOnlyMyShareAndPaybackSettlesIt() {
        val dinner = pay("2026-10-10", 1200, "SPICE GARDEN RESTAURANT", split = listOf(
            SplitShare("Suresh", 400_00, label = "ksuresh2001@okaxis"),
            SplitShare("Meera", 400_00),
        ))
        assertEquals(400_00L, Ledger.monthAmount(dinner))

        val back = got("2026-10-11", 400, "SURESH KUMAR RAMAN ANAND")
        val s = Owe.settle(listOf(dinner, back))
        assertEquals(Kind.REPAID, s.txns[1].kind)
        assertEquals(0L, Ledger.monthAmount(s.txns[1])) // the payback doesn't lower my spend: it was never mine
        assertEquals(listOf("Meera"), s.debts.map { it.name })
        assertEquals(400_00L, s.debts.single().leftPaise)
    }

    @Test fun loanIsNotSpendAndPartialPaybacksAddUp() {
        val loan = pay("2026-10-01", 5000, "ravi@oksbi", Kind.LENT)
        assertEquals(0L, Ledger.monthAmount(loan))
        val s = Owe.settle(listOf(loan, got("2026-10-05", 2000, "ravi@oksbi"), got("2026-10-09", 3000, "ravi@oksbi"), got("2026-10-12", 500, "ravi@oksbi")))
        assertEquals(listOf(Kind.REPAID, Kind.REPAID, Kind.PERSON_CREDIT), s.txns.drop(1).map { it.kind })
        assertTrue(s.debts.isEmpty())
    }

    @Test fun biggerPaymentClearsDebtButStillLowersSpend() {
        val dinner = pay("2026-10-10", 354, "CAFE", split = listOf(SplitShare("Ravi", 118_00, label = "ravi@oksbi")))
        val s = Owe.settle(listOf(dinner, got("2026-10-12", 2000, "ravi@oksbi")))
        assertEquals(Kind.PERSON_CREDIT, s.txns[1].kind)
        assertTrue(s.debts.isEmpty())
    }

    @Test fun cashbackIsNotARefund() {
        val p = Profile(setOf(acct), emptySet(), emptySet(), emptySet())
        val t = Classifier.classify(ParsedSms(Template.HDFC_UPI_CREDIT, Direction.CREDIT, 50_00, acct, "gpaycashback@okaxis", LocalDate.of(2026, 10, 1), null, null), LocalDate.of(2026, 10, 1).atTime(9, 0), p)
        assertEquals(Kind.INCOME, t.kind)
        assertEquals(0L, Ledger.monthAmount(t))
    }

    @Test fun priceRiseAlertOnlyWhenRecent() {
        val s = Subscription("NETFLIX", "Subscriptions", acct, 799_00, 649_00, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 5), false, 3)
        assertEquals("NETFLIX went up to 799", Alerts.subscriptions(listOf(s), LocalDate.of(2026, 10, 8)) { "${it / 100}" }.single().title)
        assertTrue(Alerts.subscriptions(listOf(s), LocalDate.of(2026, 10, 20)) { "${it / 100}" }.isEmpty())
    }

    @Test fun settledShareIsNotOwed() {
        val t = pay("2026-10-10", 900, "PVR", split = listOf(SplitShare("Anu", 300_00, settled = true), SplitShare("Kiran", 300_00)))
        assertEquals(listOf("Kiran"), Owe.settle(listOf(t)).debts.map { it.name })
        assertEquals(300_00L, Owe.equalShare(900_00L, 2))
    }
}
