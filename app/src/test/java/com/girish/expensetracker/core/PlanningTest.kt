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

    @Test fun billPaidByUpiFromAnAccountCounts() {
        val me = ME.copy(accounts = setOf(Source("SBI", "5678", SourceType.ACCOUNT)))
        val upi = Classifier.classify(
            (SmsReader.read("JD-SBIUPI-S", "Dear UPI user A/C X5678 debited by 12450.00 on date 12Oct26 trf to SBI Cards and Pa Refno 600000000009", me) as ReadResult.Parsed).parsed,
            at("2026-10-12"), me,
        )
        val stmt = CardStatement("SBI", "52", LocalDate.of(2026, 10, 1), 12_450_00L, 970_00L, LocalDate.of(2026, 10, 21))
        assertEquals(0L, Credit.health(SBI_CC, listOf(upi), listOf(stmt), emptyList(), null, null, LocalDate.of(2026, 10, 19)).billLeftPaise)
    }

    @Test fun markedPaidClearsTheBillWithoutCountingALateSmsTwice() {
        val stmt = CardStatement("SBI", "52", LocalDate.of(2026, 10, 1), 12_450_00L, 970_00L, LocalDate.of(2026, 10, 21))
        val mark = MarkedPaid(LocalDate.of(2026, 10, 1), 12_450_00L, at("2026-10-16"))
        val h = Credit.health(SBI_CC, emptyList(), listOf(stmt), emptyList(), null, null, LocalDate.of(2026, 10, 19), marked = mark)
        assertEquals(0L, h.billLeftPaise)
        assertEquals(0L, h.outstandingPaise)
        assertTrue(h.markedPaid)
        assertTrue(Alerts.credit(listOf(h), LocalDate.of(2026, 10, 19), { it.bank }, { "₹${it / 100}" }, { it.toString() }).isEmpty())
        // The payment SMS turns up after all: still paid once.
        val pay = sms("VM-SBICRD-S", "Dear SBI Cardholder, payment of Rs. 12450.00 for your SBI Credit Card has been successfully processed. ref no : ABC123XYZ.", at("2026-10-15"))
        val late = Credit.health(SBI_CC, listOf(pay), listOf(stmt), emptyList(), null, null, LocalDate.of(2026, 10, 19), marked = mark)
        assertEquals(0L, late.billLeftPaise)
        assertEquals(0L, late.outstandingPaise)
        // A mark for an older statement does nothing for a new one.
        val next = stmt.copy(statementDate = LocalDate.of(2026, 11, 1), dueDate = LocalDate.of(2026, 11, 21))
        assertEquals(12_450_00L, Credit.health(SBI_CC, emptyList(), listOf(next), emptyList(), null, null, LocalDate.of(2026, 11, 5), marked = mark).billLeftPaise)
    }

    @Test fun prepaymentFromABankAccountLowersTheBalance() {
        val me = ME.copy(accounts = setOf(Source("SBI", "5678", SourceType.ACCOUNT)))
        val upi = Classifier.classify(
            (SmsReader.read("JD-SBIUPI-S", "Dear UPI user A/C X5678 debited by 5000.00 on date 25Oct26 trf to SBI Cards and Pa Refno 600000000010", me) as ReadResult.Parsed).parsed,
            at("2026-10-25"), me,
        )
        val stmt = CardStatement("SBI", "52", LocalDate.of(2026, 10, 1), 12_450_00L, 970_00L, LocalDate.of(2026, 10, 21))
        val seen = listOf(LimitSeen("SBI", "1152", 60_000_00L, at("2026-10-20")))
        // Before the next statement (1 Nov) the owner prepays 5,000 by UPI: the balance the bank will report drops.
        val h = Credit.health(SBI_CC, listOf(upi), listOf(stmt), seen, 1_00_000_00L, null, LocalDate.of(2026, 10, 26), accountPaysHere = true)
        assertEquals(35_000_00L, h.outstandingPaise) // 1,00,000 - 60,000 - 5,000
        // With two SBI cards the app can't tell which one it paid, so the balance waits for the next limit SMS.
        assertEquals(40_000_00L, Credit.health(SBI_CC, listOf(upi), listOf(stmt), seen, 1_00_000_00L, null, LocalDate.of(2026, 10, 26)).outstandingPaise)
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

class CardGroupTest {

    private val icici2 = Source("ICICI", "1111", SourceType.CARD)
    private val me = ME.copy(cards = ME.cards + icici2)

    private fun spend(card: String, rs: Int, avl: Int, day: String): Txn {
        val r = SmsReader.read("AD-ICICIT-S", "INR $rs.00 spent using ICICI Bank Card XX$card on $day on AMAZON. Avl Limit: INR $avl.00.", me)
        assertTrue("expected Parsed but got $r", r is ReadResult.Parsed)
        // Received before the limit SMS below, so only the "available limit" decides the balance.
        return Classifier.classify((r as ReadResult.Parsed).parsed, at("2026-10-01"), me)
    }

    @Test fun sharedLimitCountsOnceAndCardsSplitTheBalance() {
        // One ₹1,00,000 limit for both cards; the latest SMS (from card 1111) says ₹70,000 is left.
        val txns = listOf(spend("9999", 20000, 80000, "05-Oct-26"), spend("1111", 10000, 70000, "08-Oct-26"))
        val seen = listOf(
            LimitSeen("ICICI", "9999", 80_000_00L, at("2026-10-05")),
            LimitSeen("ICICI", "1111", 70_000_00L, at("2026-10-08")),
        )
        val h = Credit.groupHealth(CardGroup("ICICI", listOf(ICICI_CC, icici2)), txns, emptyList(), seen, 100_000_00L, 3, emptyMap(), LocalDate.of(2026, 10, 15))
        assertTrue(h.isGroup)
        assertEquals(100_000_00L, h.limitPaise) // one limit, not two added up
        assertEquals(30_000_00L, h.outstandingPaise)
        assertEquals(0.30, h.utilization!!, 1e-9)
        assertEquals(listOf(20_000_00L, 10_000_00L), h.members.map { it.outstandingPaise })
        assertEquals(0.20, h.members[0].utilization!!, 1e-9)
        assertEquals(LocalDate.of(2026, 11, 3), h.nextStatement)
        assertEquals(CardGroup.DIGITS, h.card.last4)
    }

    @Test fun separateLimitsAddUp() {
        val txns = listOf(spend("9999", 20000, 80000, "05-Oct-26"), spend("1111", 10000, 40000, "08-Oct-26"))
        val seen = listOf(
            LimitSeen("ICICI", "9999", 80_000_00L, at("2026-10-05")),
            LimitSeen("ICICI", "1111", 40_000_00L, at("2026-10-08")),
        )
        val limits = mapOf(ICICI_CC to 100_000_00L, icici2 to 50_000_00L)
        val h = Credit.groupHealth(CardGroup("ICICI", listOf(ICICI_CC, icici2), sharedLimit = false), txns, emptyList(), seen, null, null, limits, LocalDate.of(2026, 10, 15))
        assertEquals(150_000_00L, h.limitPaise)
        assertEquals(30_000_00L, h.outstandingPaise) // 20,000 + 10,000
        assertEquals(0.20, h.members[1].utilization!!, 1e-9)
        assertFalse(h.sharedLimit)
    }

    @Test fun cardSmsWithoutNumberIsKnownAndCountsForTheWholeBankGroup() {
        val r = SmsReader.read("VA-ICICIT-S", "INR 2,000.00 spent on ICICI Bank Credit Card at AMAZON on 12-Oct-26. Avl Limit: INR 68,000.00", me)
        // With two ICICI cards the reader can't pick one: the spend is the owner's, on card "?".
        assertTrue("expected Parsed but got $r", r is ReadResult.Parsed)
        run {
            r as ReadResult.Parsed
            assertEquals(UNASSIGNED, r.parsed.source.last4)
            val t = Classifier.classify(r.parsed, at("2026-10-12", 13), me)
            val seen = listOf(LimitSeen("ICICI", "9999", 70_000_00L, at("2026-10-10")))
            val g = CardGroup("ICICI", listOf(ICICI_CC, icici2))
            val whole = Credit.groupHealth(g, listOf(t), emptyList(), seen, 100_000_00L, null, emptyMap(), LocalDate.of(2026, 10, 15), unassigned = true)
            assertEquals(32_000_00L, whole.outstandingPaise) // 1,00,000 - 70,000 + 2,000
            val notMine = Credit.groupHealth(g, listOf(t), emptyList(), seen, 100_000_00L, null, emptyMap(), LocalDate.of(2026, 10, 15))
            assertEquals(30_000_00L, notMine.outstandingPaise)
        }
        assertTrue(me.isKnown(Source("ICICI", UNASSIGNED, SourceType.CARD)))
        assertFalse(me.isKnown(Source("AXIS", UNASSIGNED, SourceType.CARD)))
    }

    @Test fun twoGroupsAtOneBankKeepTheirOwnLimits() {
        val c3 = Source("ICICI", "3333", SourceType.CARD)
        val c4 = Source("ICICI", "4444", SourceType.CARD)
        val seen = listOf(
            LimitSeen("ICICI", "9999", 80_000_00L, at("2026-10-05")),
            LimitSeen("ICICI", "4444", 45_000_00L, at("2026-10-06")),
        )
        val a = Credit.groupHealth(CardGroup("ICICI", listOf(ICICI_CC, icici2)), emptyList(), emptyList(), seen, 100_000_00L, null, emptyMap(), LocalDate.of(2026, 10, 15))
        val b = Credit.groupHealth(CardGroup("ICICI", listOf(c3, c4), id = "group1"), emptyList(), emptyList(), seen, 50_000_00L, null, emptyMap(), LocalDate.of(2026, 10, 15))
        assertEquals(20_000_00L, a.outstandingPaise)
        assertEquals(5_000_00L, b.outstandingPaise)
        assertEquals("group1", b.card.last4)
        assertTrue(CardGroup.isGroup(b.card))
    }

    @Test fun limitSmsRunningAsOneBalanceLookShared() {
        // Card 9999 leaves 80,000; then 1111 spends 10,000 and its SMS says 70,000 left: one balance.
        val spend = Classifier.classify(
            (SmsReader.read("AD-ICICIT-S", "INR 10000.00 spent using ICICI Bank Card XX1111 on 08-Oct-26 on AMAZON. Avl Limit: INR 70000.00.", me) as ReadResult.Parsed).parsed,
            at("2026-10-08"), me,
        )
        val shared = listOf(LimitSeen("ICICI", "9999", 80_000_00L, at("2026-10-05")), LimitSeen("ICICI", "1111", 70_000_00L, at("2026-10-08")))
        assertTrue(Credit.looksShared(ICICI_CC, icici2, shared, listOf(spend)))
        // Own limits: 1111's SMS has nothing to do with 9999's balance.
        val separate = listOf(LimitSeen("ICICI", "9999", 80_000_00L, at("2026-10-05")), LimitSeen("ICICI", "1111", 40_000_00L, at("2026-10-08")))
        assertFalse(Credit.looksShared(ICICI_CC, icici2, separate, listOf(spend)))
    }

    @Test fun noSpendSplitsEvenly() {
        val seen = listOf(LimitSeen("ICICI", "9999", 90_000_00L, at("2026-10-05")))
        val h = Credit.groupHealth(CardGroup("ICICI", listOf(ICICI_CC, icici2)), emptyList(), emptyList(), seen, 100_000_00L, null, emptyMap(), LocalDate.of(2026, 10, 15))
        assertEquals(listOf(5_000_00L, 5_000_00L), h.members.map { it.outstandingPaise })
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
            // A favourite restaurant visited about monthly for a similar bill is not a subscription.
            spend("2026-08-12", 1210, "SPICE GARDEN", cat = "Food"), spend("2026-09-11", 1260, "SPICE GARDEN", cat = "Food"),
            spend("2026-10-12", 1260, "SPICE GARDEN", cat = "Food"),
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
