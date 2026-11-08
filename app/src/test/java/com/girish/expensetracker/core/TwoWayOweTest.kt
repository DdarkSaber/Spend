package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Two-way splits with netting: made-up people and handles. */
class TwoWayOweTest {

    private val acct = Source("HDFC", "1234", SourceType.ACCOUNT)
    private var n = 0

    private fun pay(day: String, rs: Long, to: String, kind: Kind = Kind.SPEND, split: List<SplitShare> = emptyList(), type: PayeeType = PayeeType.PERSON) = Txn(
        ParsedSms(Template.HDFC_UPI_SENT, Direction.DEBIT, rs * 100, acct, to, LocalDate.parse(day), "p${n++}", null),
        kind, type, if (kind == Kind.LENT) "Lent" else "Food", LocalDate.parse(day).atTime(12, n % 60), splitWith = split,
    )

    private fun got(day: String, rs: Long, from: String) = Txn(
        ParsedSms(Template.HDFC_UPI_CREDIT, Direction.CREDIT, rs * 100, acct, from, LocalDate.parse(day), "g${n++}", null),
        Kind.PERSON_CREDIT, PayeeType.PERSON, "Paid back", LocalDate.parse(day).atTime(18, n % 60),
    )

    /** "They paid for me" as the app stores it: a hand entry owed to that person. */
    private fun theyPaid(day: String, rs: Long, name: String, label: String? = null) = Txn(
        ParsedSms(Template.MANUAL, Direction.DEBIT, rs * 100, Source("CASH", "CASH", SourceType.ACCOUNT), "Paid by $name", LocalDate.parse(day), null, "Food"),
        Kind.SPEND, PayeeType.MERCHANT, "Food", LocalDate.parse(day).atTime(20, 0), owedTo = SplitShare(name, rs * 100, label),
    )

    private val rakshak = "rakshak99@okaxis"
    private fun share(rs: Long) = listOf(SplitShare("Rakshak", rs * 100, label = rakshak))

    @Test fun bulkPaybackCoversFourItems() {
        val s = Owe.settle(listOf(
            pay("2026-10-01", 600, "CAFE", split = share(300)), pay("2026-10-03", 900, "PVR", split = share(450)),
            pay("2026-10-05", 5000, rakshak, Kind.LENT), pay("2026-10-07", 400, "BLINKIT", split = share(200)),
            got("2026-10-10", 5950, rakshak),
        ))
        assertEquals(Kind.REPAID, s.txns.last().kind)
        assertEquals(0L, Ledger.monthAmount(s.txns.last()))
        assertTrue(s.debts.isEmpty())
    }

    @Test fun partialPaybackLeavesTheRestOwed() {
        val s = Owe.settle(listOf(pay("2026-10-01", 1000, "CAFE", split = share(500)), pay("2026-10-02", 800, "PVR", split = share(400)), got("2026-10-03", 600, rakshak)))
        assertEquals(Kind.REPAID, s.txns.last().kind)
        assertEquals(300_00L, s.debts.single().leftPaise)
    }

    @Test fun overpayClearsAndStaysMoneyIn() {
        val s = Owe.settle(listOf(pay("2026-10-01", 1000, "CAFE", split = share(500)), got("2026-10-03", 2000, rakshak)))
        assertEquals(Kind.PERSON_CREDIT, s.txns.last().kind)
        assertTrue(s.debts.isEmpty() && s.iOwe.isEmpty())
    }

    @Test fun theyPaidForMeIsMySpendAndBulkUpiSettlesIt() {
        val s = Owe.settle(listOf(theyPaid("2026-10-01", 300, "Rakshak", rakshak), theyPaid("2026-10-02", 450, "Rakshak", rakshak), pay("2026-10-05", 750, rakshak)))
        assertEquals(300_00L, Ledger.monthAmount(s.txns[0]))     // what I had counts as my spend
        assertEquals(Kind.REPAID, s.txns[2].kind)                 // paying it back is not spend again
        assertEquals(0L, Ledger.monthAmount(s.txns[2]))
        assertTrue(s.iOwe.isEmpty())
    }

    @Test fun payingMoreThanIOweCountsTheExcessAsAPayment() {
        val s = Owe.settle(listOf(theyPaid("2026-10-01", 300, "Rakshak", rakshak), pay("2026-10-05", 1000, rakshak)))
        assertEquals(Kind.SPEND, s.txns[1].kind)
        assertEquals(300_00L, s.txns[1].settlesPaise)
        assertEquals(700_00L, Ledger.monthAmount(s.txns[1]))
        // Partial: I still owe the rest.
        val p = Owe.settle(listOf(theyPaid("2026-10-01", 300, "Rakshak", rakshak), pay("2026-10-05", 100, rakshak)))
        assertEquals(200_00L, p.iOwe.single().leftPaise)
    }

    @Test fun bothDirectionsNetToOneBalance() {
        // Rakshak owes me 1,540 from a dinner; he paid 300 for my lunch: he owes me 1,240.
        val s = Owe.settle(listOf(pay("2026-10-01", 3080, "SPICE GARDEN", split = share(1540)), theyPaid("2026-10-02", 300, "Rakshak", rakshak)))
        assertEquals(1240_00L, s.debts.single().leftPaise)
        assertTrue(s.iOwe.isEmpty())
        // The other way round: he paid 2,000 for me, I lent him 500 later -> I owe him 1,500.
        val r = Owe.settle(listOf(theyPaid("2026-10-01", 2000, "Rakshak", rakshak), pay("2026-10-02", 500, rakshak, Kind.LENT)))
        assertEquals(Kind.REPAID, r.txns[1].kind)
        assertEquals(1500_00L, r.iOwe.single().leftPaise)
        assertTrue(r.debts.isEmpty())
    }

    @Test fun splitWhereIPaidThenTheyPayPart() {
        val s = Owe.settle(listOf(pay("2026-10-01", 1200, "PIZZA PLACE", split = share(600)), got("2026-10-02", 250, rakshak)))
        assertEquals(1200_00L - 600_00L, Ledger.monthAmount(s.txns[0]))
        assertEquals(Kind.REPAID, s.txns[1].kind)
        assertEquals(350_00L, s.debts.single().leftPaise)
    }

    @Test fun payerUnderAnotherNameAssignedToThePerson() {
        // His father paid me back; the owner put that credit under Rakshak (the app rewrites the payer to his label).
        val s = Owe.settle(listOf(pay("2026-10-01", 1000, "CAFE", split = share(500)), got("2026-10-04", 500, rakshak)))
        assertTrue(s.debts.isEmpty())
        // Unassigned, the father's payment settles nothing.
        val u = Owe.settle(listOf(pay("2026-10-01", 1000, "CAFE", split = share(500)), got("2026-10-04", 500, "SOMEONE ELSE SENIOR")))
        assertEquals(500_00L, u.debts.single().leftPaise)
    }

    @Test fun shopsNeverSettleWhatIOwe() {
        val s = Owe.settle(listOf(theyPaid("2026-10-01", 300, "Zomato"), pay("2026-10-02", 300, "ZOMATO", type = PayeeType.MERCHANT)))
        assertEquals(Kind.SPEND, s.txns[1].kind)
        assertEquals(300_00L, s.iOwe.single().leftPaise)
    }
}
