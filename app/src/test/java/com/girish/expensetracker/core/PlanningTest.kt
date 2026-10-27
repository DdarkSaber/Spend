package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

private val ICICI_CC = Source("ICICI", "9999", SourceType.CARD)
private val SBI_CC = Source("SBI", "1152", SourceType.CARD)
private val HDFC_CC = Source("HDFC", "4321", SourceType.CARD)
private val ME = Profile(emptySet(), setOf(ICICI_CC, SBI_CC, HDFC_CC), emptySet(), emptySet())

private fun at(day: String, h: Int = 12) = LocalDate.parse(day).atTime(h, 0)

private fun sms(sender: String, body: String, at: LocalDateTime): Txn {
    val r = SmsReader.read(sender, body, ME)
    assertTrue("expected Parsed for $body but got $r", r is ReadResult.Parsed)
    return Classifier.classify((r as ReadResult.Parsed).parsed, at, ME)
}

class CardFactsTest {

    @Test fun statementsFromThreeBanks() {
        val sbi = CardFacts.statement("AX-MYSBIC-S", "E-statement of SBI Credit Card ending XX52 dated 01/10/2026 has been mailed. If not received, SMS ENRS to 5676791. Total Amt Due Rs 12450; Min Amt Due Rs 970; Payable by 21/10/2026. Click https://sbicard.com/quickpaynet to pay your bill", at("2026-10-02"))!!
        assertEquals(LocalDate.of(2026, 10, 1), sbi.statementDate)
        assertEquals(1245000L, sbi.totalDuePaise)
        assertEquals(97000L, sbi.minDuePaise)
        assertEquals(LocalDate.of(2026, 10, 21), sbi.dueDate)
        assertEquals("52", sbi.digits)

        val icici = CardFacts.statement("JM-ICICIT-S", "ICICI Bank Credit Card XX9999 Statement is sent to ab****@gmail.com. Total of Rs 2,762.84 or minimum of Rs 140.00 is due by 21-OCT-26.", at("2026-10-03"))!!
        assertEquals(276284L, icici.totalDuePaise)
        assertEquals(14000L, icici.minDuePaise)
        assertEquals(LocalDate.of(2026, 10, 21), icici.dueDate)
        assertEquals(LocalDate.of(2026, 10, 3), icici.statementDate)

        val hdfc = CardFacts.statement("VM-HDFCBK-S", "Statement Generated: For HDFC Bank Credit Card XX4321. View: https://x.y/a Pay: https://x.y/b", at("2026-10-05"))!!
        assertNull(hdfc.totalDuePaise)
        assertEquals(LocalDate.of(2026, 10, 5), hdfc.statementDate)
    }

    @Test fun nagSmsIsNotAStatement() {
        assertNull(CardFacts.statement("AX-SBICRD-S", "Dear SBI Credit Cardholder, please update your email ID instantly & get timely delivery of your monthly e-statement directly into your mail box.", at("2026-10-02")))
    }

    @Test fun availableLimitFromSpendAndPayment() {
        val icici = CardFacts.limit("AD-ICICIT-S", "INR 1,313.46 spent using ICICI Bank Card XX9999 on 05-Oct-26 on PAY*BOOKMYSHOW . Avl Limit: INR 2,97,966.06. If not you, call 18002662.", at("2026-10-05"))!!
        assertEquals(29796606L, icici.availablePaise)
        assertEquals("9999", icici.digits)
        val hdfc = CardFacts.limit("AD-HDFCBK-S", "DEAR HDFCBANK CARDMEMBER, PAYMENT OF Rs. 6000.00 RECEIVED TOWARDS YOUR CREDIT CARD ENDING WITH 4321 ON 3-10-2026.YOUR AVAILABLE LIMIT IS RS. 95000.00", at("2026-10-03"))!!
        assertEquals(9500000L, hdfc.availablePaise)
        assertEquals(30000000L, Credit.estimateLimit(listOf(icici)))
    }
}

class CreditHealthTest {

    @Test fun utilizationFromAvailableLimitAndLaterSpends() {
        val seen = LimitSeen("ICICI", "9999", 70_000_00L, at("2026-10-10"))
        val later = sms("AD-ICICIT-S", "INR 5,000.00 spent using ICICI Bank Card XX9999 on 12-Oct-26 on AMAZON. Avl Limit: INR 65,000.00.", at("2026-10-12"))
        val stmt = CardStatement("ICICI", "9999", LocalDate.of(2026, 10, 3), 2_000_00L, 100_00L, LocalDate.of(2026, 10, 21))
        val h = Credit.health(ICICI_CC, listOf(later), listOf(stmt), listOf(seen), 100_000_00L, null, LocalDate.of(2026, 10, 15))
        assertEquals(35_000_00L, h.outstandingPaise) // 1,00,000 - 70,000 + 5,000
        assertEquals(0.35, h.utilization!!, 1e-9)
        assertEquals(5_000_00L, h.payToReach(Credit.HEALTHY))
        assertEquals(25_000_00L, h.payToReach(Credit.BEST))
        assertEquals(LocalDate.of(2026, 11, 3), h.nextStatement)
        assertFalse(h.limitEstimated)
    }

    @Test fun billPaidAfterStatementClearsTheReminder() {
        val stmt = CardStatement("SBI", "52", LocalDate.of(2026, 10, 1), 12_450_00L, 970_00L, LocalDate.of(2026, 10, 21))
        val pay = sms("VM-SBICRD-S", "Dear SBI Cardholder, payment of Rs. 12450.00 for your SBI Credit Card has been successfully processed. ref no : ABC123XYZ.", at("2026-10-15"))
        val spend = sms("VM-SBICRD-S", "Rs.723.08 spent on your SBI Credit Card ending 1152 at ZOMATO on 12/10/26. Trxn. Not you? Call 18001801290.", at("2026-10-12"))
        val h = Credit.health(SBI_CC, listOf(spend, pay), listOf(stmt), emptyList(), null, null, LocalDate.of(2026, 10, 19))
        assertEquals(0L, h.billLeftPaise)
        assertEquals(72308L, h.outstandingPaise) // 12,450 + 723.08 - 12,450
        assertNull(h.utilization) // no limit known yet: the owner is asked to set one
        val alerts = Alerts.credit(listOf(h), LocalDate.of(2026, 10, 19), { it.bank }, { "₹${it / 100}" }, { it.toString() })
        assertTrue(alerts.isEmpty())
    }

    @Test fun reminderThreeDaysBeforeAndOnTheDay() {
        val stmt = CardStatement("ICICI", "9999", LocalDate.of(2026, 10, 3), 2_762_84L, 140_00L, LocalDate.of(2026, 10, 21))
        fun on(d: Int) = Alerts.credit(
            listOf(Credit.health(ICICI_CC, emptyList(), listOf(stmt), emptyList(), null, null, LocalDate.of(2026, 10, d))),
            LocalDate.of(2026, 10, d), { it.bank }, { "₹${it / 100}" }, { it.toString() },
        )
        assertTrue(on(17).isEmpty())
        assertEquals("due|$ICICI_CC|2026-10-21|soon", on(18).single().key)
        assertEquals("due|$ICICI_CC|2026-10-21|today", on(21).single().key)
    }

    @Test fun over30PercentWarnsWithAmountToPay() {
        val h = CardHealth(HDFC_CC, 1_00_000_00L, false, 34_000_00L, null, LocalDate.of(2026, 11, 5), 0)
        val a = Alerts.credit(listOf(h), LocalDate.of(2026, 10, 20), { "HDFC card ${it.last4}" }, { "₹${it / 100}" }, { "5 Nov" }).single()
        assertEquals("HDFC card 4321 is at 34% of its limit", a.title)
        assertTrue(a.text, a.text.startsWith("Pay ₹4000 before 5 Nov"))
    }
}

class BudgetAndSubscriptionTest {

    private fun spend(day: String, rs: Long, to: String, kind: Kind = Kind.SPEND, cat: String = "Food") = Txn(
        ParsedSms(Template.HDFC_UPI_SENT, Direction.DEBIT, rs * 100, Source("HDFC", "1234", SourceType.ACCOUNT), to, LocalDate.parse(day), "r$day$rs$to", null),
        kind, PayeeType.MERCHANT, cat, at(day),
    )

    @Test fun budgetProjectsDailySpendButNotSips() {
        val start = LocalDate.of(2026, 9, 28)
        val txns = listOf(spend("2026-09-28", 10_000, "GROWW", Kind.INVESTMENT_SIP, "Investment")) +
            (0 until 10).map { spend(start.plusDays(it.toLong()).toString(), 1_000, "SWIGGY") }
        val total = Budgets.lines(txns, start, start.plusDays(9), 40_000_00L, mapOf("Food" to 20_000_00L))
        val all = total.first { it.category == null }
        assertEquals(20_000_00L, all.spentPaise)
        assertEquals(10_000_00L + 30 * 1_000_00L, all.projectedPaise) // SIP once, food 1k/day x 30 days
        val food = total.first { it.category == "Food" }
        assertTrue(food.overPace)
        assertEquals("pace|Food|$start", Alerts.budgets(listOf(food), start) { "$it" }.single().key)
    }

    @Test fun monthlyChargeIsASubscriptionButGroceriesAreNot() {
        val txns = listOf(
            spend("2026-08-05", 649, "NETFLIX", cat = "Subscriptions"), spend("2026-09-05", 649, "NETFLIX", cat = "Subscriptions"),
            spend("2026-10-05", 799, "NETFLIX", cat = "Subscriptions"),
            spend("2026-08-10", 1200, "CULT FIT", cat = "Medical"), spend("2026-09-10", 1200, "CULT FIT", cat = "Medical"),
            spend("2026-10-10", 1200, "CULT FIT", cat = "Medical"),
        ) + (1..20).map { spend("2026-10-${"%02d".format(it)}", 300 + it * 7L, "BLINKIT", cat = "Groceries") }
        val subs = Subscriptions.find(txns, LocalDate.of(2026, 10, 20))
        assertEquals(listOf("NETFLIX", "CULT FIT"), subs.map { it.name })
        assertTrue(subs.first().priceUp)
        assertEquals(LocalDate.of(2026, 11, 5), subs.first().nextDay)
        assertNotNull(subs.find { it.name == "CULT FIT" && !it.priceUp })
    }

    @Test fun cancelledSubscriptionDropsOff() {
        val txns = listOf(spend("2026-06-05", 199, "SPOTIFY", cat = "Subscriptions"), spend("2026-07-05", 199, "SPOTIFY", cat = "Subscriptions"))
        assertTrue(Subscriptions.find(txns, LocalDate.of(2026, 10, 20)).isEmpty())
    }
}
