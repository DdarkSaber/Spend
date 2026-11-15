package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 1.3.0: money in from people, bank names in notification text, promo words in a payee. Made-up people and handles. */
class V13FixesTest {

    private val acct = Source("HDFC", "1234", SourceType.ACCOUNT)
    private var n = 0

    private fun pay(day: String, rs: Long, to: String, type: PayeeType = PayeeType.PERSON) = Txn(
        ParsedSms(Template.HDFC_UPI_SENT, Direction.DEBIT, rs * 100, acct, to, LocalDate.parse(day), "p${n++}", null),
        Kind.SPEND, type, "Food", LocalDate.parse(day).atTime(12, n % 60),
    )

    private fun got(day: String, rs: Long, from: String) = Txn(
        ParsedSms(Template.HDFC_UPI_CREDIT, Direction.CREDIT, rs * 100, acct, from, LocalDate.parse(day), "g${n++}", null),
        Kind.PERSON_CREDIT, PayeeType.PERSON, "Paid back", LocalDate.parse(day).atTime(18, n % 60),
    )

    private val friend = "friend42@okaxis"

    @Test fun moneyFromSomeoneNeverPaidIsPlainMoneyIn() {
        val s = Owe.settle(listOf(pay("2026-10-01", 300, "CAFE", PayeeType.MERCHANT), got("2026-10-05", 5000, friend)))
        assertEquals(0L, Ledger.monthAmount(s.txns.last()))
        // The month stays at the ₹300 spent, not -₹4,700.
        assertEquals(300_00L, s.txns.sumOf(Ledger::monthAmount))
    }

    @Test fun paybackLowersSpendOnlyUpToWhatWasPaidToThem() {
        val s = Owe.settle(listOf(pay("2026-10-01", 1200, friend), got("2026-10-04", 2000, friend)))
        assertEquals(-1200_00L, Ledger.monthAmount(s.txns.last()))
        assertEquals(800_00L, s.txns.last().plainInPaise)
        assertEquals(0L, s.txns.sumOf(Ledger::monthAmount))
    }

    @Test fun oldPaymentsDoNotOffset() {
        val s = Owe.settle(listOf(pay("2026-06-01", 1000, friend), got("2026-10-01", 1000, friend)))
        assertEquals(0L, Ledger.monthAmount(s.txns.last()))
    }

    @Test fun eachPaymentPaysBackOnce() {
        val s = Owe.settle(listOf(pay("2026-10-01", 500, friend), got("2026-10-02", 500, friend), got("2026-10-03", 500, friend)))
        assertEquals(-500_00L, Ledger.monthAmount(s.txns[1]))
        assertEquals(0L, Ledger.monthAmount(s.txns[2]))
    }

    @Test fun transferPairsAreReportedForReview() {
        val sbi = Source("SBI", "9939", SourceType.ACCOUNT)
        val out = Txn(ParsedSms(Template.SBI_UPI_DEBIT, Direction.DEBIT, 10_000_00, sbi, null, LocalDate.parse("2026-10-01"), "a", null),
            Kind.SPEND, null, "Uncategorised", LocalDate.parse("2026-10-01").atTime(10, 0))
        val `in` = Txn(ParsedSms(Template.HDFC_UPI_CREDIT, Direction.CREDIT, 10_000_00, acct, null, LocalDate.parse("2026-10-01"), "b", null),
            Kind.INCOME, null, "Income", LocalDate.parse("2026-10-01").atTime(10, 5))
        val pairs = mutableListOf<Pair<Int, Int>>()
        val r = Transfers.pair(listOf(out, `in`), isOwn = { true }, onPair = { d, c -> pairs += d to c })
        assertEquals(listOf(0 to 1), pairs)
        assertTrue(r.all { it.kind == Kind.TRANSFER })
        // Pinned by the owner ("Not a transfer"): never paired again.
        assertTrue(Transfers.pair(listOf(out, `in`), isOwn = { true }, locked = { it == 0 }).none { it.kind == Kind.TRANSFER })
    }

    @Test fun shopOrPersonAsksOnlyAboutRecentRepeatPayees() {
        val today = LocalDate.parse("2026-11-15")
        val txns = listOf(
            pay("2026-11-01", 120, "RANDOM STALL"), pay("2026-11-03", 80, "RANDOM STALL"), // asked
            pay("2026-11-02", 500, "ONE OFF NAME"), // one payment: not asked
            pay("2024-01-01", 90, "OLD PLACE"), pay("2024-01-05", 90, "OLD PLACE"), // over a year ago: not asked
            pay("2026-10-01", 300, "A FRIEND"), got("2026-10-02", 300, "A FRIEND"), // money both ways: a person
        )
        assertEquals(listOf("RANDOM STALL"), PayeeReviews.of(txns, { false }, emptySet(), today).map { it.label })
    }

    @Test fun bankNamesThatContainAnother() {
        assertEquals("CENTBK", BankFromText.code("Rs 500 debited from your Central Bank of India account"))
        assertEquals("BOIIND", BankFromText.code("Rs 500 debited from your Bank of India account"))
        assertTrue(BankFromText.code("Rs 500 debited from State Bank of India a/c XX1234") != "BOIIND")
        assertEquals("HDFCBK", BankFromText.code("Rs 5000 paid to SBI Cards from HDFC Bank A/c 1234"))
        assertEquals("SBICRD", BankFromText.code("Rs 500 spent on your SBI Credit Card ending 4415"))
    }

    @Test fun promoWordInsideThePayeeIsStillAPayment() {
        val profile = Profile(emptySet(), emptySet(), emptySet(), emptySet(), acceptAll = true)
        val r = SmsReader.read("AX-KOTAKB-S", "Rs 500.00 debited from your A/c XX1234 on 10-11-26 to LUCKY RESTAURANT. UPI Ref 612345678901.", profile)
        assertTrue("got $r", r is ReadResult.Parsed)
        val offer = SmsReader.read("AX-KOTAKB-S", "Lucky you! Rs 500.00 credited to your A/c XX1234 as cashback offer, claim now.", profile)
        assertTrue("got $offer", offer !is ReadResult.Parsed)
    }
}
