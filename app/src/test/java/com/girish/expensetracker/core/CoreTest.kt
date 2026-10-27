package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

// Fixtures use the real bank layouts with made-up names, digits and references.
private val HDFC_AC = Source("HDFC", "1234", SourceType.ACCOUNT)
private val SBI_AC = Source("SBI", "5678", SourceType.ACCOUNT)
private val HDFC_CC = Source("HDFC", "4321", SourceType.CARD)
private val SBI_CC1 = Source("SBI", "1111", SourceType.CARD)
private val SBI_CC2 = Source("SBI", "2222", SourceType.CARD)

private val PROFILE = Profile(
    accounts = setOf(HDFC_AC, SBI_AC, Source("DCB", "7777", SourceType.ACCOUNT)),
    cards = setOf(HDFC_CC, SBI_CC1, SBI_CC2, Source("ICICI", "9999", SourceType.CARD)),
    selfNames = setOf("TEST USER M P"),
    selfVpas = setOf("testuser@oksbi"),
)

private val NOW = LocalDateTime.of(2026, 10, 25, 22, 0)

private fun parsed(sender: String, body: String): ParsedSms {
    val r = SmsReader.read(sender, body, PROFILE)
    assertTrue("expected Parsed but got $r for: $body", r is ReadResult.Parsed)
    return (r as ReadResult.Parsed).parsed
}

private fun txn(sender: String, body: String, at: LocalDateTime = NOW, rules: PayeeRules = PayeeRules.EMPTY) =
    Classifier.classify(parsed(sender, body), at, PROFILE, rules)

class SmsReaderTest {

    @Test fun hdfcUpiSent() {
        val p = parsed("VM-HDFCBK-T", "Sent Rs.135.34 From HDFC Bank A/C *1234 To RAVI KUMAR On 25/10/26 Ref 100000000001 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808")
        assertEquals(Template.HDFC_UPI_SENT, p.template)
        assertEquals(13534L, p.amountPaise)
        assertEquals(HDFC_AC, p.source)
        assertEquals("RAVI KUMAR", p.counterparty)
        assertEquals(LocalDate.of(2026, 10, 25), p.date)
        assertEquals("100000000001", p.ref)
    }

    @Test fun hdfcUpiMandate() {
        val p = parsed("JD-HDFCBK-S", "UPI Mandate: Sent Rs.170.00 from HDFC Bank A/c 1234 To Google Play 15/08/26 Ref 100000000002 Not You? Call 18002586161")
        assertEquals(Template.HDFC_UPI_MANDATE, p.template)
        assertEquals("Google Play", p.counterparty)
    }

    @Test fun hdfcAchUpdateWithIndianCommas() {
        val p = parsed("BP-HDFCBK-S", "UPDATE: INR 5,000.00 debited from HDFC Bank XX1234 on 02-OCT-26. Info: ACH D- GROWW INVEST TECH PR-ABCDEF123. Avl bal:INR 1,23,456.78")
        assertEquals(Template.HDFC_ACH_UPDATE, p.template)
        assertEquals(500000L, p.amountPaise)
        assertEquals("GROWW INVEST TECH PR", p.counterparty)
        assertEquals(LocalDate.of(2026, 10, 2), p.date)
    }

    @Test fun hdfcAchAlertHasNoDate() {
        val p = parsed("BP-HDFCBK-S", "PAYMENT ALERT! INR 9000.00 deducted from HDFC Bank A/C No 1234 towards Indian Clearing Corporation Lt UMRN: HDFC0000000000000001")
        assertEquals(Template.HDFC_ACH_ALERT, p.template)
        assertNull(p.date)
    }

    @Test fun hdfcCardSpentBothLayouts() {
        val a = parsed("JD-HDFCBK-S", "Spent Rs.585 On HDFC Bank Card 4321 At ..PVR LIMITED_ On 2026-08-19:00:16:16.Not You? To Block+Reissue Call 18002586161")
        assertEquals(HDFC_CC, a.source)
        assertEquals("PVR LIMITED", a.counterparty)
        val b = parsed("JD-HDFCBK-S", "Amount Spent: Rs.649 with HDFC Bank Credit Card 4321 at NETFLIX on 2026-10-25:23:17:51 This transaction was completed without any additional authentication factor mandated by RBI.")
        assertEquals(Template.HDFC_CARD_AMOUNT_SPENT, b.template)
        assertEquals(64900L, b.amountPaise)
    }

    @Test fun sbiLayouts() {
        val debit = parsed("JK-SBIUPI-S", "Dear UPI user A/C X5678 debited by 70.00 on date 24Oct26 trf to KRISHNA Juice Sho Refno 100000000003 If not u? call-1800111109")
        assertEquals(SBI_AC, debit.source)
        assertEquals(LocalDate.of(2026, 10, 24), debit.date)
        assertEquals("KRISHNA Juice Sho", debit.counterparty)
        val credit = parsed("JK-SBIUPI-S", "Dear SBI User, your A/c X5678-credited by Rs.1286 on 18Aug26 transfer from FRIENDONE Ref No 100000000004 -SBI")
        assertEquals(Direction.CREDIT, credit.direction)
        assertEquals("FRIENDONE", credit.counterparty)
        val card = parsed("VM-SBICRD-S", "Rs.723.08 spent on your SBI Credit Card ending 2222 at ZOMATO on 03/08/26. Trxn. not done by you? Report at")
        assertEquals(SBI_CC2, card.source)
    }

    @Test fun salaryDeposit() {
        val t = txn("VD-HDFCBK-S", "Update! INR 3,84,683.00 deposited in HDFC Bank A/c XX1234 on 28-SEP-26 for NEFT Cr-CITI0000004-EMPLOYER-1608-SALARY PAYMENT-Test User M P-CITIN000000.Avl bal INR 5,42,437.02. Cheque deposits in A/C are subject to clearing")
        assertEquals(Kind.INCOME, t.kind)
        assertTrue(t.isSalary)
        assertEquals(38468300L, t.amountPaise)
    }

    @Test fun usRemittanceIsIncomeNotSalary() {
        val t = txn("VD-HDFCBK-S", "Update! INR 4,82,702.37 deposited in HDFC Bank A/c XX1234 on 08-SEP-26 for INW 050926I049901766 USD5181.99@93.15.Avl bal INR 10,08,679.44. Cheque deposits in A/C are subject to clearing")
        assertEquals(Kind.INCOME, t.kind)
        assertTrue(!t.isSalary)
    }

    @Test fun dcbInterestCredit() {
        val t = txn("AD-DCBANK-S", "DCB Bank Alert - A/c no. *7777 is credited by INR 30. Avail Bal is INR 59821.19 on 17-oct-2026 02:15 PM. View on Mobile Banking at")
        assertEquals(Kind.INCOME, t.kind)
    }
}

class FilterTest {

    @Test fun promoSenderDropped() {
        val r = SmsReader.read("AX-HDFCBN-P", "Unlimited Cover: Say goodbye to claim limits. Rs.500 credited as bonus", PROFILE)
        assertEquals(ReadResult.Promo, r)
    }

    @Test fun phoneNumberSenderIsNotBank() {
        val r = SmsReader.read("+919876543210", "Rs.60,000 credited to your account. You are just 1 step away, click here", PROFILE)
        assertEquals(ReadResult.NotBank, r)
    }

    @Test fun scamWordingFromBankLikeHeaderIsPromo() {
        val r = SmsReader.read("VM-HDFCBK-S", "Congrats! Rs.60,000 credited to your pre-approved limit. You are just 1 step away, click here", PROFILE)
        assertEquals(ReadResult.Promo, r)
    }

    @Test fun nonBankServiceSenderIgnored() {
        val r = SmsReader.read("VM-rapido-S", "Paid Rs.120 for your ride", PROFILE)
        assertEquals(ReadResult.NotBank, r)
    }

    @Test fun preDebitNoticeIsInformational() {
        val r = SmsReader.read("AD-HDFCBK-S", "Alert: INR.649.00 will be debited on 24/10/2026 from HDFC Bank Card 4321 for NETFLIX ID:Xx1", PROFILE)
        assertEquals(ReadResult.Informational, r)
    }

    @Test fun unknownCardIsFlaggedNotCounted() {
        val r = SmsReader.read("JD-HDFCBK-S", "Spent Rs.100 On HDFC Bank Card 0000 At SHOP On 2026-08-19:00:16:16.", PROFILE)
        assertTrue(r is ReadResult.UnknownSource)
    }

    @Test fun unknownLayoutGoesToUnparsed() {
        // A foreign-currency charge can't be counted in rupees (the only INR figure is the limit): shown, never guessed.
        val r = SmsReader.read("VA-ICICIT-S", "USD 11.80 spent using ICICI Bank Card XX9999 on 03-Sep-26 on SOMEAPP. Avl Limit: INR 1,95,899.53.", PROFILE)
        assertTrue("must land in Unparsed: $r", r is ReadResult.Unparsed)
    }

    @Test fun noCardNumberMeansTheOnlyCardAtThatBank() {
        val t = txn("VA-ICICIT-S", "Refund of INR 499.00 credited to your ICICI Bank Card for SOMESHOP on 16-Oct-26.")
        assertEquals(Source("ICICI", "9999", SourceType.CARD), t.parsed.source)
        assertEquals(Kind.REFUND, t.kind)
    }

    @Test fun cardRefundWithNumberIsReadGenerically() {
        val t = txn("VA-ICICIT-S", "Refund of INR 499.00 credited to ICICI Bank Card XX9999 for SOMESHOP on 16-Oct-26.")
        assertEquals(Kind.REFUND, t.kind)
        assertEquals(Source("ICICI", "9999", SourceType.CARD), t.parsed.source)
        assertEquals(-49900L, Ledger.monthAmount(t))
    }
}

class ClassifierTest {

    @Test fun selfTransferExcluded() {
        val t = txn("AD-HDFCBK-T", "Sent Rs.60000.00 From HDFC Bank A/C *1234 To TEST USERM P On 24/10/26 Ref 100000000005 Not You?")
        assertEquals(Kind.TRANSFER, t.kind)
        assertEquals(0L, Ledger.monthAmount(t))
    }

    @Test fun sbiCardBillPaidByUpiIsTransfer() {
        val t = txn("JD-SBIUPI-S", "Dear UPI user A/C X5678 debited by 27951.76 on date 12Oct26 trf to SBI Cards and Pa Refno 100000000006 If not u?")
        assertEquals(Kind.TRANSFER, t.kind)
    }

    @Test fun sbiBillPaymentMatchesEitherSbiCard() {
        val r = SmsReader.read("VM-SBICRD-S", "Dear SBI Cardholder, payment of Rs. 27951.76 for your SBI Credit Card has been successfully processed. ref no : EKT00000000000.", PROFILE)
        assertTrue(r is ReadResult.Parsed)
        val t = Classifier.classify((r as ReadResult.Parsed).parsed, NOW, PROFILE)
        assertEquals(Kind.TRANSFER, t.kind)
    }

    @Test fun achGrowwIsSip() {
        val t = txn("BP-HDFCBK-S", "UPDATE: INR 5,000.00 debited from HDFC Bank XX1234 on 02-OCT-26. Info: ACH D- GROWW INVEST TECH PR-ABCDEF123. Avl bal:INR 1,000.00")
        assertEquals(Kind.INVESTMENT_SIP, t.kind)
    }

    @Test fun upiToGrowwIsLumpSum() {
        val t = txn("VM-HDFCBK-T", "Sent Rs.100000.00 From HDFC Bank A/C *1234 To GROWW INVEST TECH On 10/10/26 Ref 100000000007 Not You?")
        assertEquals(Kind.INVESTMENT_LUMPSUM, t.kind)
    }

    @Test fun friendPaidMeReducesTotals() {
        val t = txn("JK-SBIUPI-S", "Dear SBI User, your A/c X5678-credited by Rs.300 on 25Oct26 transfer from FRIEND TWO Ref No 100000000008 -SBI")
        assertEquals(Kind.PERSON_CREDIT, t.kind)
        assertEquals(-30000L, Ledger.todayAmount(t))
    }

    @Test fun ownVpaCreditIsTransfer() {
        val t = txn("VD-HDFCBK-S", "Credit Alert! Rs.100000.00 credited to HDFC Bank A/c XX1234 on 10-09-26 from VPA testuser@oksbi (UPI 100000000009)")
        assertEquals(Kind.TRANSFER, t.kind)
    }

    @Test fun merchantVsPersonFromName() {
        assertEquals(PayeeType.MERCHANT, PayeeGuess.type("SRI GAJANANA FRUIT JUICE"))
        assertEquals("Food", PayeeGuess.category("SRI GAJANANA FRUIT JUICE"))
        assertEquals(PayeeType.MERCHANT, PayeeGuess.type("Vriddhii Fuels"))
        assertEquals("Fuel", PayeeGuess.category("Vriddhii Fuels"))
        assertEquals(PayeeType.MERCHANT, PayeeGuess.type("MUNCHMART TECHNOLOGIES PR"))
        assertEquals(PayeeType.MERCHANT, PayeeGuess.type("Redbus India Private Limi"))
        assertEquals("Transport", PayeeGuess.category("Bangalore Metro Rail Corp"))
        assertEquals(PayeeType.PERSON, PayeeGuess.type("Mr Ramesh B"))
        assertEquals(PayeeType.PERSON, PayeeGuess.type("RAVI SHANKAR"))
        assertEquals(PayeeType.PERSON, PayeeGuess.type("Lakshmi B K"))
        // short keywords must not match inside other words
        assertNull(PayeeGuess.category("TEAMWORK LLP"))
    }

    @Test fun retagWins() {
        val rules = PayeeRules.of("Mr AARIF" to PayeeRules.Rule(PayeeType.MERCHANT, "Food"))
        val t = txn("JD-SBIUPI-S", "Dear UPI user A/C X5678 debited by 75.00 on date 20Sep26 trf to Mr AARIF Refno 100000000010 If not u?", rules = rules)
        assertEquals(PayeeType.MERCHANT, t.payeeType)
        assertEquals("Food", t.category)
    }
}

class DedupeAndLedgerTest {

    private val d = LocalDateTime.of(2026, 10, 2, 9, 0)

    @Test fun netflixTripleCountsOnceAsSubscription() {
        val list = listOf(
            txn("AD-HDFCBK-S", "AutoPay (E-mandate) Success! For NETFLIX Txn Amt:INR649.00 Dt:25/10/2026 Via:HDFC Bank CC 4321 Mandate ID: Xx1 Not You?"),
            txn("JD-HDFCBK-S", "Amount Spent: Rs.649 with HDFC Bank Credit Card 4321 at NETFLIX on 2026-10-25:23:17:51 This transaction"),
        )
        val out = Dedupe.apply(list)
        assertEquals(1, out.size)
        assertEquals(Kind.SUBSCRIPTION, out[0].kind)
        assertEquals(64900L, Ledger.today(out, LocalDate.of(2026, 10, 25)))
    }

    @Test fun achAlertAndUpdateCollapse_butTwoRealSipsBothCount() {
        val update = "UPDATE: INR 5,000.00 debited from HDFC Bank XX1234 on 02-OCT-26. Info: ACH D- Indian Clearing Corp-0000ABC. Avl bal:INR 1,000.00"
        val alert = "PAYMENT ALERT! INR 5000.00 deducted from HDFC Bank A/C No 1234 towards Indian Clearing Corporation Lt UMRN: HDFC0001"
        val list = listOf(
            txn("BP-HDFCBK-S", update, d), txn("BP-HDFCBK-S", alert, d),
            txn("BP-HDFCBK-S", update, d), txn("BP-HDFCBK-S", alert, d),
        )
        val out = Dedupe.apply(list)
        assertEquals(2, out.size)
        assertEquals(1000000L, Ledger.today(out, LocalDate.of(2026, 10, 2)))
    }

    @Test fun sameUpiSmsTwiceCountsOnce() {
        val body = "Sent Rs.281.00 From HDFC Bank A/C *1234 To Blinkit On 05/10/26 Ref 100000000011 Not You?"
        val out = Dedupe.apply(listOf(txn("VM-HDFCBK-T", body), txn("VM-HDFCBK-T", body)))
        assertEquals(1, out.size)
    }

    @Test fun lumpSumInMonthNotInToday() {
        val t = txn("VM-HDFCBK-T", "Sent Rs.100000.00 From HDFC Bank A/C *1234 To GROWW INVEST TECH On 10/10/26 Ref 100000000012 Not You?")
        val day = LocalDate.of(2026, 10, 10)
        assertEquals(0L, Ledger.today(listOf(t), day))
        assertEquals(10000000L, Ledger.month(listOf(t), day))
    }

    @Test fun cardBillPaymentDoesNotDoubleCount() {
        val spend = txn("VM-SBICRD-S", "Rs.1000.00 spent on your SBI Credit Card ending 1111 at ZOMATO on 01/10/26. Trxn.")
        val bill = txn("JD-SBIUPI-S", "Dear UPI user A/C X5678 debited by 1000.00 on date 12Oct26 trf to SBI Cards and Pa Refno 100000000013 If not u?")
        assertEquals(100000L, Ledger.month(listOf(spend, bill), LocalDate.of(2026, 10, 12)))
    }

    @Test fun sbiPrimaryAndAddOnCardsShownSeparately() {
        val a = txn("VM-SBICRD-S", "Rs.100.00 spent on your SBI Credit Card ending 1111 at ZOMATO on 01/10/26. Trxn.")
        val b = txn("VM-SBICRD-S", "Rs.200.00 spent on your SBI Credit Card ending 2222 at ZOMATO on 01/10/26. Trxn.")
        val bySource = Ledger.bySource(listOf(a, b), LocalDate.of(2026, 10, 5))
        assertEquals(10000L, bySource[SBI_CC1])
        assertEquals(20000L, bySource[SBI_CC2])
    }

    @Test fun cycleFallsBackToThe28th() {
        assertEquals(LocalDate.of(2026, 9, 28), Ledger.cycleStart(LocalDate.of(2026, 10, 26), emptyList()))
        assertEquals(LocalDate.of(2026, 10, 28), Ledger.cycleStart(LocalDate.of(2026, 10, 30), emptyList()))
        // February has no 28th problem but shorter months are clamped
        assertEquals(LocalDate.of(2027, 2, 28), Ledger.cycleStart(LocalDate.of(2027, 3, 1), emptyList()))
    }

    @Test fun earlySalaryStartsCycleEarly() {
        val salary = txn("VD-HDFCBK-S", "Update! INR 1,00,000.00 deposited in HDFC Bank A/c XX1234 on 26-SEP-26 for NEFT Cr-X-SALARY PAYMENT-Y.Avl bal INR 1.00")
        val spendBefore = txn("VM-HDFCBK-T", "Sent Rs.100.00 From HDFC Bank A/C *1234 To Blinkit On 27/09/26 Ref 100000000014 Not You?")
        val day = LocalDate.of(2026, 9, 29)
        assertEquals(LocalDate.of(2026, 9, 26), Ledger.cycleStart(day, listOf(salary)))
        assertEquals(10000L, Ledger.month(listOf(salary, spendBefore), day))
    }
}

class TrendTest {

    private fun spend(day: String, rs: Int) =
        Classifier.classify(
            ParsedSms(Template.HDFC_UPI_SENT, Direction.DEBIT, rs * 100L, HDFC_AC, "Blinkit", LocalDate.parse(day), "r$day$rs", null),
            LocalDate.parse(day).atTime(12, 0), PROFILE,
        )

    private fun sip(day: String, rs: Int) =
        Classifier.classify(
            ParsedSms(Template.HDFC_ACH_UPDATE, Direction.DEBIT, rs * 100L, HDFC_AC, "GROWW INVEST TECH PR", LocalDate.parse(day), null, "ACH D- GROWW"),
            LocalDate.parse(day).atTime(9, 0), PROFILE,
        )

    @Test fun cyclesWalkBackwardsFromToday() {
        val c = Ledger.cycles(LocalDate.of(2026, 10, 27), emptyList(), 3)
        assertEquals(LocalDate.of(2026, 9, 28)..LocalDate.of(2026, 10, 27), c[0])
        assertEquals(LocalDate.of(2026, 8, 28)..LocalDate.of(2026, 9, 27), c[1])
        assertEquals(LocalDate.of(2026, 7, 28)..LocalDate.of(2026, 8, 27), c[2])
    }

    @Test fun paceIsCumulativeAndSkipsRecurring() {
        val txns = listOf(spend("2026-09-28", 100), sip("2026-10-02", 5000), spend("2026-09-30", 50))
        val p = Ledger.pace(txns, LocalDate.of(2026, 9, 28), 5)
        assertEquals(listOf(10000L, 10000L, 15000L, 15000L, 15000L), p.toList())
    }

    @Test fun byCategoryInHonoursTheRange() {
        val txns = listOf(spend("2026-09-27", 100), spend("2026-09-28", 40))
        val m = Ledger.byCategoryIn(txns, LocalDate.of(2026, 9, 28)..LocalDate.of(2026, 10, 27))
        assertEquals(mapOf("Groceries" to 4000L), m)
    }
}

class RealLayoutsTest {
    // Layouts copied from real SMS (Oct 2026) with names, handles and digits changed.
    private val profile = PROFILE.copy(
        accounts = PROFILE.accounts + Source("DCB", "7777", SourceType.ACCOUNT),
        cards = PROFILE.cards + Source("ICICI", "9998", SourceType.CARD),
    )

    private fun read(sender: String, body: String): Txn {
        val r = SmsReader.read(sender, body, profile)
        assertTrue("expected Parsed but got $r for: $body", r is ReadResult.Parsed)
        return Classifier.classify((r as ReadResult.Parsed).parsed, NOW, profile)
    }

    @Test fun iciciCardSpend() {
        val t = read("JD-ICICIT-S", "INR 1,199.00 spent using ICICI Bank Card XX9999 on 16-Oct-26 on HOYOVERSE. Avl Limit: INR 2,88,676.00. If not you, call 1800 2662/SMS BLOCK 9999 to 92")
        assertEquals(Kind.SPEND, t.kind)
        assertEquals(Source("ICICI", "9999", SourceType.CARD), t.parsed.source)
        assertEquals("Entertainment", t.category)
    }

    @Test fun iciciCardBillPaidIsTransfer() {
        val t = read("JD-ICICIT-S", "Dear Customer, payment of INR 30,210.90 towards your ICICI Bank Credit Card XX9998 has been received through Click to Pay on 03-SEP-26. Thank you.")
        assertEquals(Kind.TRANSFER, t.kind)
    }

    @Test fun dcbUpiCreditFromFriendAndFromSelf() {
        val friend = read("AX-DCBANK-T", "INR 430.00 credited to your DCB Bank a/c 7777 from UPI ID friendthree@okaxis. UPI ref. 100000000031. Available balance INR 1000.00.")
        assertEquals(Kind.PERSON_CREDIT, friend.kind)
        val self = read("JM-DCBANK-T", "INR 20000.00 credited to your DCB Bank a/c 7777 from UPI ID testuser-1@okhdfcbank. UPI ref. 100000000032. Available balance INR 1.00.")
        assertEquals(Kind.TRANSFER, self.kind)
    }

    @Test fun dcbUpiDebitAndCardSpend() {
        val bill = read("AX-DCBANK-T", "INR 3799.00 debited DCB a/c 7777; to SBI Cards and Payment Services; UPI 100000000033. Not you? Call 04068157777")
        assertEquals(Kind.TRANSFER, bill.kind)
        val pos = read("BP-DCBANK-T", "INR 1917.63 debited DCB Bank a/c*7777 POS/Ecom txn to someshop on 23-09-2026 03:48 AM.Not you?Call 0226899777")
        assertEquals(Kind.SPEND, pos.kind)
    }

    @Test fun cashWithdrawalAndSmartPayBill() {
        val cash = read("JX-HDFCBK-S", "Rs.5000 withdrawn from HDFC Bank Card x4321 at +P H ROAD BRANCH on 2026-08-14:17:32:20.Not U? Block&Reissue")
        assertEquals("Cash", cash.category)
        val bill = read("VM-HDFCBK-S", "Bill Paid: VILPOSTPAID Bill 9000000000 of Rs. 532.19 paid on 07-Aug-2026 from HDFC Bank Credit Card xx4321 via SmartPay. Ref:HGAMP0000")
        assertEquals("Bills", bill.category)
        assertEquals(53219L, bill.amountPaise)
    }

    @Test fun duplicateConfirmationsAndStatementsAreNotMoney() {
        listOf(
            "VD-HDFCBK-S" to "Dear Smart Pay Customer,We have successfully debited your HDFC Bank Credit card ending 4321 to pay your SpayBBPS 99625 bill for the amount Rs 532.19",
            "VM-HDFCBK-S" to "SmartPay Scheduled: Rs. 532.19 for VILPOSTPAID Bill 9000000000 will auto-debit via HDFC Bank Credit Card xx4321 within 4 days by 11-Aug-2026",
            "BP-MYSBIC-S" to "E-statement of SBI Credit Card ending XX52 dated 01/09/2026 has been mailed. If not received, SMS ENRS to 5676791. Total Amt Due Rs 3799; Min Amt Due",
            "JM-HDFCBK-S" to "AutoPay Alert! Your HDFC Bank Credit Card 4321 bill Minimum Amt: Rs.31 Will be deducted from your A/C 1234 on 02/OCT/2026. Ignore if paid",
        ).forEach { (s, b) -> assertEquals(b, ReadResult.Informational, SmsReader.read(s, b, profile)) }
    }

    @Test fun merchantCreditsAreRefundsOrPayoutsNotPeople() {
        val groww = read("VM-HDFCBK-S", "Credit Alert! Rs.44265.72 credited to HDFC Bank A/c XX1234 on 10-09-26 from VPA groww.balance@hdfcbank (UPI 100000000020)")
        assertEquals(Kind.INCOME, groww.kind)
        val refund = read("VM-HDFCBK-S", "Credit Alert! Rs.1825.00 credited to HDFC Bank A/c XX1234 on 10-09-26 from VPA gpayrefund-online@axisbank (UPI 100000000021)")
        assertEquals(Kind.REFUND, refund.kind)
        assertEquals(-182500L, Ledger.monthAmount(refund))
    }

    @Test fun truncatedCompanyNamesAreMerchants() {
        assertEquals(PayeeType.MERCHANT, PayeeGuess.type("Google Asia Pacific Pte.L"))
        assertEquals(PayeeType.MERCHANT, PayeeGuess.type("UrbanClap Technologies In"))
        assertEquals(PayeeType.MERCHANT, PayeeGuess.type("THE HOUSE OF RARE"))
        assertEquals(PayeeType.MERCHANT, PayeeGuess.type("UNIQLO INDIA PRI"))
        assertEquals(PayeeType.PERSON, PayeeGuess.type("VIKRAM MALHOTRA"))
    }

    @Test fun hdfcCardBillConfirmedTwiceCountsOnce() {
        val a = read("AD-HDFCBK-S", "DEAR HDFCBANK CARDMEMBER, PAYMENT OF Rs. 6581.22 RECEIVED TOWARDS YOUR CREDIT CARD ENDING WITH 4321 ON 23-9-2026.YOUR AVAILABLE LIMIT IS RS. 1.00")
        val b = read("VM-HDFCBK-S", "HDFC Bank Cardmember, Payment of Rs 6581.22 was credited to your card ending 4321 on 23/SEP/2026.")
        assertEquals(1, Dedupe.apply(listOf(a, b)).size)
    }
}


class CategoryTest {
    @Test fun foodBrandsAndRenames() {
        listOf("SWIGGY", "ZOMATO", "ETERNALLIMITED", "Eternal Limited", "DOMINOS PIZZA", "Domino s Pizza", "MCDONALDS", "THE THICKSHAKE FACTORY",
            "SRI GAJANANA FRUIT JUICE", "NANDHANA PALACE", "SZECHUAN DRAGON").forEach { assertEquals(it, "Food", PayeeGuess.category(it)) }
    }
    @Test fun otherCommonMerchants() {
        assertEquals("Groceries", PayeeGuess.category("Blinkit"))
        assertEquals("Groceries", PayeeGuess.category("ZEPTO MARKETPLACE PRIVATE"))
        assertEquals("Groceries", PayeeGuess.category("Sembakkam Super"))
        assertEquals("Transport", PayeeGuess.category("Bangalore Metro Rail Corp"))
        assertEquals("Fuel", PayeeGuess.category("Vriddhii Fuels"))
        assertEquals("Entertainment", PayeeGuess.category("BOOK MY SHOW PA"))
        assertEquals("Entertainment", PayeeGuess.category("HOYOVERSE"))
        assertEquals("Subscriptions", PayeeGuess.category("NETFLIX"))
        assertEquals("Shopping", PayeeGuess.category("MIDAS DIGITAL"))
        assertEquals("Bills", PayeeGuess.category("VILPOSTPAID"))
        assertEquals("Household", PayeeGuess.category("UrbanClap Technologies In"))
        assertEquals("Medical", PayeeGuess.category("LENSKART SOLUTIONS PVT"))
        assertEquals("Medical", PayeeGuess.category("ASPIRE FERTILITY CENTE"))
        assertEquals("Medical", PayeeGuess.category("APOLLO PHARMACY"))
        assertEquals("Medical", PayeeGuess.category("TITAN EYE PLUS"))
    }
}

class RecurringCardChargeTest {
    @Test fun withoutOtpChargeIsASpendAndDedupesWithAutoPay() {
        val charge = SmsReader.read("AD-HDFCBK-S", "Rs.649 without OTP/PIN HDFC Bank Card x4321 At NETFLIX On 2026-08-25:21:56:18.Not U? Block&Reissue", PROFILE)
        assertTrue("$charge", charge is ReadResult.Parsed)
        val a = Classifier.classify((charge as ReadResult.Parsed).parsed, NOW, PROFILE)
        val auto = SmsReader.read("AD-HDFCBK-S", "AutoPay (E-mandate) Success! For NETFLIX Txn Amt:INR649.00 Dt:25/08/2026 Via:HDFC Bank CC 4321 Mandate ID: X", PROFILE)
        val b = Classifier.classify((auto as ReadResult.Parsed).parsed, NOW, PROFILE)
        val out = Dedupe.apply(listOf(a, b))
        assertEquals(1, out.size)
        assertEquals(Kind.SUBSCRIPTION, out[0].kind)
    }
}
