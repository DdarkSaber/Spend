package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class TransfersAndFactsTest {

    private val sbi = Source("SBI", "5678", SourceType.ACCOUNT)
    private val hdfc = Source("HDFC", "1234", SourceType.ACCOUNT)
    private val hdfcCard = Source("HDFC", "4321", SourceType.CARD)
    private val sbiCard = Source("SBI", "1111", SourceType.CARD)
    private val profile = Profile(setOf(sbi, hdfc), setOf(hdfcCard, sbiCard), setOf("TEST USER M P"), setOf("testuser@oksbi"))
    private val at = LocalDateTime.of(2026, 10, 12, 10, 0)

    private fun read(sender: String, body: String, at: LocalDateTime = this.at): Txn {
        val r = SmsReader.read(sender, body, profile)
        check(r is ReadResult.Parsed) { "not parsed: $body -> $r" }
        return Classifier.classify(r.parsed, at, profile)
    }

    private fun own(s: Source) = profile.isKnown(s)

    @Test fun accountToAccountWithoutSelfNamesIsATransfer() {
        // NEFT-style move with no self name or VPA in either SMS: the classifier alone calls it spend + money in.
        val out = read("JD-SBIUPI-S", "Dear UPI user A/C X5678 debited by 5000.00 on date 12Oct26 trf to MR SOMEONE Refno 100000000020 If not u?")
        val inn = read("VD-HDFCBK-S", "Credit Alert! Rs.5000.00 credited to HDFC Bank A/c XX1234 on 13-10-26 from VPA someone99@okicici (UPI 100000000021)", at.plusDays(1))
        val paired = Transfers.pair(listOf(out, inn), ::own)
        assertEquals(listOf(Kind.TRANSFER, Kind.TRANSFER), paired.map { it.kind })
        assertEquals("Transfer", paired[0].category)
    }

    @Test fun billPaidFromAnAccountIsACardBill() {
        val debit = Txn(ParsedSms(Template.GENERIC_DEBIT, Direction.DEBIT, 2_795_176, hdfc, "BILLDESK", at.toLocalDate(), null, null), Kind.SPEND, PayeeType.MERCHANT, "Bills", at)
        val credit = Txn(ParsedSms(Template.GENERIC_CARD_PAYMENT, Direction.CREDIT, 2_795_176, sbiCard, null, at.toLocalDate().plusDays(2), null, null), Kind.INCOME, null, "Income", at.plusDays(2))
        val paired = Transfers.pair(listOf(debit, credit), ::own)
        assertEquals(listOf(Kind.TRANSFER, Kind.TRANSFER), paired.map { it.kind })
        assertEquals(listOf("Card bill", "Card bill"), paired.map { it.category })
    }

    @Test fun notPairedWhenTooFarApartSameSourceOrLockedOrSalary() {
        val debit = Txn(ParsedSms(Template.GENERIC_DEBIT, Direction.DEBIT, 10_000, sbi, "X", at.toLocalDate(), null, null), Kind.SPEND, PayeeType.PERSON, "Other", at)
        val late = Txn(ParsedSms(Template.GENERIC_CREDIT, Direction.CREDIT, 10_000, hdfc, "X", at.toLocalDate().plusDays(3), null, null), Kind.PERSON_CREDIT, PayeeType.PERSON, "Money in", at.plusDays(3))
        val same = late.copy(parsed = late.parsed.copy(source = sbi, date = at.toLocalDate()))
        val salary = late.copy(parsed = late.parsed.copy(date = at.toLocalDate()), isSalary = true, kind = Kind.INCOME)
        assertEquals(listOf(Kind.SPEND, Kind.PERSON_CREDIT), Transfers.pair(listOf(debit, late), ::own).map { it.kind })
        assertEquals(listOf(Kind.SPEND, Kind.PERSON_CREDIT), Transfers.pair(listOf(debit, same), ::own).map { it.kind })
        assertEquals(listOf(Kind.SPEND, Kind.INCOME), Transfers.pair(listOf(debit, salary), ::own).map { it.kind })
        val ok = late.copy(parsed = late.parsed.copy(date = at.toLocalDate()))
        assertEquals(listOf(Kind.SPEND, Kind.PERSON_CREDIT), Transfers.pair(listOf(debit, ok), ::own, locked = { it == 0 }).map { it.kind })
        // A payment to someone else's account is never a transfer, however the amounts line up.
        val stranger = Source("AXIS", "0001", SourceType.ACCOUNT)
        assertEquals(listOf(Kind.SPEND, Kind.PERSON_CREDIT), Transfers.pair(listOf(debit, ok.copy(parsed = ok.parsed.copy(source = stranger))), ::own).map { it.kind })
    }

    @Test fun eachDebitPairsOnceWithTheNearestCredit() {
        val d = Txn(ParsedSms(Template.GENERIC_DEBIT, Direction.DEBIT, 500, sbi, "X", at.toLocalDate(), null, null), Kind.SPEND, null, "Other", at)
        val c1 = Txn(ParsedSms(Template.GENERIC_CREDIT, Direction.CREDIT, 500, hdfc, "X", at.toLocalDate().plusDays(2), null, null), Kind.PERSON_CREDIT, null, "Money in", at.plusDays(2))
        val c2 = c1.copy(parsed = c1.parsed.copy(date = at.toLocalDate()))
        assertEquals(listOf(Kind.TRANSFER, Kind.PERSON_CREDIT, Kind.TRANSFER), Transfers.pair(listOf(d, c1, c2), ::own).map { it.kind })
    }

    @Test fun cardSpendAndAFriendsPaybackOfTheSameAmountAreNotATransfer() {
        // Rs 500 at a shop on the credit card, and a friend paying back Rs 500 into the account the next day.
        val spend = read("VM-SBICRD-S", "Rs.500.00 spent on your SBI Credit Card ending 1111 at ZOMATO on 12/10/26. Trxn.")
        val payback = read("VD-HDFCBK-S", "Credit Alert! Rs.500.00 credited to HDFC Bank A/c XX1234 on 13-10-26 from VPA friend77@okicici (UPI 100000000031)", at.plusDays(1))
        assertEquals(listOf(Kind.SPEND, Kind.PERSON_CREDIT), Transfers.pair(listOf(spend, payback), ::own).map { it.kind })
    }

    @Test fun payingOneFriendAndBeingPaidByAnotherIsNotATransfer() {
        val out = read("JD-SBIUPI-S", "Dear UPI user A/C X5678 debited by 1000.00 on date 12Oct26 trf to RAHUL VERMA Refno 100000000040 If not u?")
        val inn = read("VD-HDFCBK-S", "Credit Alert! Rs.1000.00 credited to HDFC Bank A/c XX1234 on 13-10-26 from VPA priya.nair@okaxis (UPI 100000000041)", at.plusDays(1))
        assertEquals(listOf(Kind.SPEND, Kind.PERSON_CREDIT), Transfers.pair(listOf(out, inn), ::own).map { it.kind })
        // A credit that names nobody (an NEFT deposit) still pairs with it.
        val deposit = Txn(ParsedSms(Template.GENERIC_CREDIT, Direction.CREDIT, 100_000, hdfc, null, at.toLocalDate().plusDays(1), null, null), Kind.INCOME, null, "Money in", at.plusDays(1))
        assertEquals(listOf(Kind.TRANSFER, Kind.TRANSFER), Transfers.pair(listOf(out, deposit), ::own).map { it.kind })
    }

    // ------------------------------------------------------------------ level 2

    private data class R(val t: Txn, val ch: String)
    private fun l2(vararg r: R) = FactDedupe.apply(r.toList(), { it.t }, { it.ch })
    private val metro = Txn(ParsedSms(Template.GENERIC_CARD_SPENT, Direction.DEBIT, 10_000, sbiCard, "METRO", at.toLocalDate(), null, null), Kind.SPEND, PayeeType.MERCHANT, "Travel", at)

    @Test fun twoEqualPaymentsOnOneChannelBothCount() {
        assertEquals(2, l2(R(metro, "sms"), R(metro.copy(receivedAt = at.plusHours(1)), "sms")).kept.size)
    }

    @Test fun smsAndArchiveCopyOfOneDayAreOneAndSmsWins() {
        val archive = R(metro.copy(parsed = metro.parsed.copy(template = Template.SBI_CARD_SPENT, date = at.toLocalDate().plusDays(1))), "archive:truecaller")
        val sms = R(metro, "sms")
        val r = l2(archive, sms)
        assertEquals(listOf(sms), r.kept)
        assertEquals(listOf("archive:truecaller"), r.alsoVia[0])
        assertEquals(1, r.collapses.size)
        assertEquals(sms, r.collapses.single().kept)
    }

    @Test fun refundAndSpendOfTheSameAmountStaySeparate() {
        val refund = R(metro.copy(parsed = metro.parsed.copy(direction = Direction.CREDIT), kind = Kind.REFUND), "notif:com.truecaller")
        assertEquals(2, l2(R(metro, "sms"), refund).kept.size)
    }

    @Test fun twoDaysApartOrOtherCardStaySeparate() {
        val later = R(metro.copy(parsed = metro.parsed.copy(date = at.toLocalDate().plusDays(2))), "notif:com.truecaller")
        val otherCard = R(metro.copy(parsed = metro.parsed.copy(source = Source("SBI", "2222", SourceType.CARD))), "notif:com.truecaller")
        assertEquals(3, l2(R(metro, "sms"), later, otherCard).kept.size)
    }

    @Test fun twoEqualSpendsADayApartOnTwoChannelsBothCount() {
        // Rs 100 by SMS on one day and another Rs 100 by Truecaller the next: two payments, not two copies of one.
        val next = R(metro.copy(parsed = metro.parsed.copy(date = at.toLocalDate().plusDays(1)), receivedAt = at.plusDays(1)), "notif:com.truecaller")
        val r = l2(R(metro, "sms"), next)
        assertEquals(2, r.kept.size)
        assertEquals(0, r.collapses.size)
        // The same spend told twice within the hour is still one.
        val copy = R(metro.copy(receivedAt = at.plusMinutes(20)), "notif:com.google.android.gm")
        assertEquals(1, l2(R(metro, "sms"), copy).kept.size)
        // An archive alert or statement row only carries a day, so a day's slack stays.
        val archived = R(metro.copy(parsed = metro.parsed.copy(date = at.toLocalDate().plusDays(1)), receivedAt = at.plusDays(1)), "archive:truecaller")
        assertEquals(1, l2(R(metro, "sms"), archived).kept.size)
    }

    @Test fun cardAlertWithoutDigitsIsTheSamePaymentAndTheCopyWithDigitsIsKept() {
        val noDigits = R(metro.copy(parsed = metro.parsed.copy(source = Source("SBI", UNASSIGNED, SourceType.CARD))), "notif:com.truecaller")
        val archived = R(metro.copy(receivedAt = at.plusMinutes(1)), "archive:truecaller")
        // The notification is the better channel, but the archive copy knows the card.
        val r = l2(noDigits, archived)
        assertEquals(listOf(archived), r.kept)
        assertEquals(listOf("notif:com.truecaller"), r.alsoVia[0])
        assertEquals(listOf(R(metro, "sms")), l2(noDigits, R(metro, "sms")).kept)
        // Another bank's card, or an account with no digits, never matches it.
        val otherBank = R(metro.copy(parsed = metro.parsed.copy(source = Source("HDFC", "4321", SourceType.CARD))), "sms")
        assertEquals(2, l2(noDigits, otherBank).kept.size)
    }

    @Test fun threeChannelsCollapseToOneAndStatementRowLoses() {
        val r = l2(R(metro, "stmt"), R(metro, "archive:gmail"), R(metro, "notif:com.google.android.gm"), R(metro, "sms"))
        assertEquals(1, r.kept.size)
        assertEquals("sms", r.kept.single().ch)
        assertEquals(setOf("stmt", "archive:gmail", "notif:com.google.android.gm"), r.alsoVia[0]!!.toSet())
        assertEquals(3, r.collapses.size)
    }
}
