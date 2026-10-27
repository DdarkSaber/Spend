package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Banks read by the generic rules. Shapes follow each bank's SMS; names, digits and references are made up. */
class GenericBanksTest {

    private val open = Profile(emptySet(), emptySet(), emptySet(), emptySet(), acceptAll = true)

    private fun read(sender: String, body: String): ParsedSms {
        val r = SmsReader.read(sender, body, open)
        assertTrue("expected Parsed but got $r for: $body", r is ReadResult.Parsed)
        return (r as ReadResult.Parsed).parsed
    }

    private fun kind(sender: String, body: String) = Classifier.classify(read(sender, body), LocalDateTime.of(2026, 10, 20, 10, 0), open)

    @Test fun axisCardSpendAcrossLines() {
        val p = read("AX-AXISBK-S", "Spent INR 623 \nusing Axis Bank Credit Card no. XX4455 on 24-10-26 17:31:29 IST at \nSWIGGY FOOD. \nAvl limit: INR 50376.49. Not you? SMS BLOCK 4455 to 9100000000.".replace("\n", " "))
        assertEquals(Template.GENERIC_CARD_SPENT, p.template)
        assertEquals(62300L, p.amountPaise)
        assertEquals(Source("AXIS", "4455", SourceType.CARD), p.source)
        assertEquals("SWIGGY FOOD", p.counterparty)
        assertEquals(LocalDate.of(2026, 10, 24), p.date)
    }

    @Test fun axisAccountDebitWithMerchantBeforeDate() {
        val p = read("VM-AXISBK-S", "INR 209.00 debited from A/c no. XX123456 on BURGER BARN 04-10-2026 13:13:27 IST. Avl bal: INR 9999.00. Not you? SMS BLOCKCARD XX0023 to 9100000000 - Axis Bank")
        assertEquals(Template.GENERIC_DEBIT, p.template)
        assertEquals(Source("AXIS", "3456", SourceType.ACCOUNT), p.source)
        assertEquals("BURGER BARN", p.counterparty)
        assertEquals(20900L, p.amountPaise)
    }

    @Test fun kotakUpiSentAndReceived() {
        val sent = read("JD-KOTAKB-S", "Sent Rs.15.00 from Kotak Bank AC X1234 to shopqr12345@paytm on 14-10-26.UPI Ref 600000000001. Not you, https://kotak.com/KBANKT/Fraud")
        assertEquals(Template.GENERIC_DEBIT, sent.template)
        assertEquals("shopqr12345@paytm", sent.counterparty)
        assertEquals(Source("KOTAK", "1234", SourceType.ACCOUNT), sent.source)
        val got = read("JD-KOTAKB-S", "Received Rs.250.00 in your Kotak Bank AC X3333 from asha.k@oksbi on 14-10-26.UPI Ref 600000000002. Not you, https://kotak.com/KBANKT/Fraud")
        assertEquals(Direction.CREDIT, got.direction)
        assertEquals("asha.k@oksbi", got.counterparty)
        assertEquals(Kind.PERSON_CREDIT, kind("JD-KOTAKB-S", "Received Rs.250.00 in your Kotak Bank AC X3333 from asha.k@oksbi on 14-10-26.UPI Ref 600000000002.").kind)
    }

    @Test fun idfcCardSpendAndDebitWithMerchantCredited() {
        val card = read("BP-IDFCFB-S", "Transaction Successful! INR 500.00 spent on your IDFC FIRST Bank Credit Card ending XX1234 at AMAZON on 08-OCT-2026 at 01:28 PM Avbl Limit: INR 4074.10")
        assertEquals(Template.GENERIC_CARD_SPENT, card.template)
        assertEquals("AMAZON", card.counterparty)
        val debit = read("BP-IDFCFB-S", "Your A/c XX4614 debited by Rs. 1,172.06 on 15/10/26; REDBUS credited. RRN 600000000003. Available balance Rs. 9,134.15. Team IDFC FIRST Bank")
        assertEquals(117206L, debit.amountPaise)
        assertEquals("REDBUS", debit.counterparty)
        assertEquals(Direction.DEBIT, debit.direction)
    }

    @Test fun foreignCurrencyIsNotGuessed() {
        val r = SmsReader.read("BP-IDFCFB-S", "Transaction Successful! EUR 500.00 spent on your IDFC FIRST Bank Credit Card ending XX1234 at SHOP EU on 08-OCT-2026 Avbl Limit: INR 4074.10", open)
        assertTrue("the INR figure here is the limit, not the spend: $r", r !is ReadResult.Parsed)
    }

    @Test fun yesCardUpiMerchant() {
        val p = read("AD-YESBNK-S", "INR 404.36 spent on YES BANK Card X3349 @UPI_C N S FUEL PORT 24-10-2026 06:17:25 pm. Avl Lmt INR 211,476.24. SMS BLKCC 3349 to 9800000000 if not you")
        assertEquals(Template.GENERIC_CARD_SPENT, p.template)
        assertEquals("C N S FUEL PORT", p.counterparty)
        assertEquals(40436L, p.amountPaise)
        assertEquals("Fuel", kind("AD-YESBNK-S", "INR 404.36 spent on YES BANK Card X3349 @UPI_C N S FUEL PORT 24-10-2026 06:17:25 pm. Avl Lmt INR 211,476.24.").category)
    }

    @Test fun auCardAndAccount() {
        val card = read("VM-AUBANK-S", "INR 259.90 spent at TELEGRAM PREMIUM on AU Bank Credit Card x1234 21-10-2026 05:49:40 PM. Not you? Call 1800000000")
        assertEquals("TELEGRAM PREMIUM", card.counterparty)
        assertEquals(SourceType.CARD, card.source.type)
        val acct = read("VM-AUBANK-S", "Debited INR 500.00 from A/c X7013 on 15-OCT-2026. Bal INR 10000.00. -AU Bank")
        assertEquals(50000L, acct.amountPaise)
        assertEquals(Source("AU", "7013", SourceType.ACCOUNT), acct.source)
    }

    @Test fun indusIndUpiBothWays() {
        val out = read("JM-INDUSB-S", "A/c *XX1234 debited by Rs 1234.00 towards ravi.s@icici. RRN: 600000000004. Not You? call 18600000000- IndusInd Bank.")
        assertEquals("ravi.s@icici", out.counterparty)
        val inn = read("JM-INDUSB-S", "A/C *XX1234 credited by Rs 25000.00 from meera@ybl. RRN:600000000005. Avl Bal:105502.12. Not you? Call 18600000000 - IndusInd bank.")
        assertEquals(Direction.CREDIT, inn.direction)
        assertEquals(2500000L, inn.amountPaise)
    }

    @Test fun bankOfBarodaTransferAndIndianBankVpaCredit() {
        val bob = read("AD-BOBTXN-S", "Rs.1500.00 transferred from A/c ...1234 to:Arun Kumar. Total Bal:Rs.15000.00CR. Avlbl Amt:Rs.15000.00(10-10-2026 10:30:00) - Bank of Baroda")
        assertEquals("Arun Kumar", bob.counterparty)
        assertEquals(150000L, bob.amountPaise)
        val ind = read("AD-INDBNK-S", "Rs.15000.00 credited to a/c *1234 on 11/10/2026 by a/c linked to VPA priya@paytm (UPI Ref no 600000000006).Indian Bank")
        assertEquals("priya@paytm", ind.counterparty)
        assertEquals(LocalDate.of(2026, 10, 11), ind.date)
    }

    @Test fun cardBillPaymentIsATransfer() {
        val k = kind("VM-AXISBK-S", "Payment of INR 12,000.00 has been received towards your Axis Bank Credit Card XX4455 on 05-10-2026. Thank you.")
        assertEquals(Kind.TRANSFER, k.kind)
        assertEquals("Card bill", k.category)
    }

    @Test fun salaryAndInterestAreIncome() {
        assertTrue(kind("VM-KOTAKB-S", "Rs.85000.00 credited to your Kotak Bank AC X1234 on 28-10-2026 by NEFT SALARY ACME CORP. Avl Bal Rs.90000").isSalary)
        assertEquals(Kind.INCOME, kind("VM-INDUSB-S", "Net interest INR 248.07 credited to your IndusInd A/c XX1234 on 17/09/26. Call 18600000000").kind)
    }

    @Test fun atmIsCash() {
        assertEquals("Cash", kind("AD-INDBNK-S", "Rs. 2000 withdrawn from ATM at MAIN STREET on 09/10/2026 from a/c *8175.Indian Bank").category)
    }

    @Test fun noiseIsNotATransaction() {
        listOf(
            "123456 is your OTP for a transaction of Rs.5000 at Amazon on Axis Bank card XX1234. Do not share.",
            "Payment request of INR 500.00 from merchant@upi for a/c XX1234. Ignore if already paid.",
            "Your Axis Bank Credit Card XX4455 statement: Total amount due INR 4,500.00, due by 21-10-2026.",
            "Avl Bal in A/c XX1234 is Rs.22,300.55 as on 12-10-26",
        ).forEach { body ->
            val r = SmsReader.read("VM-AXISBK-S", body, open)
            assertTrue("should not be a transaction: $r for $body", r !is ReadResult.Parsed)
        }
    }

    @Test fun knownBanksStillUseExactLayouts() {
        assertEquals(Template.HDFC_UPI_SENT, read("VM-HDFCBK-T", "Sent Rs.135.34 From HDFC Bank A/C *1234 To RAVI KUMAR On 25/10/26 Ref 100000000001 Not You?").template)
        assertEquals("AXIS", SmsReader.bankOf("VM-AXISBK-S"))
        assertEquals(null, SmsReader.bankOf("VM-AMAZON-S"))
    }
}
