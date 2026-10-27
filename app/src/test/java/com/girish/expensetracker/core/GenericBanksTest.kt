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

    // Shapes found by running a few hundred real SMS from other open-source parsers' tests (values made up here).

    @Test fun cashbackSentToYourAccountIsMoneyIn() {
        val t = kind("VM-KOTAKB-S", "Cashback of Rs.50.00 has been sent to your Kotak Bank A/c x5555. Credited on 14-10-26. UPI Ref 600000000010")
        assertEquals(Direction.CREDIT, t.parsed.direction)
        assertEquals(Kind.INCOME, t.kind)
    }

    @Test fun someoneHasReceivedFromYourAccountIsMoneyOut() {
        val p = read("VM-FEDBNK-S", "Digital Gold India Private Limited has received Rs 21.00 from your A/c 7990 via NEFT on 10-Jan-2026 12:06:17. Ref no. FBBT26010001 - Federal Bank")
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals("Digital Gold India Private Limited", p.counterparty)
    }

    @Test fun balanceIsNeverTheAmount() {
        val r = SmsReader.read("VM-UCOBNK-S", "Your UCO Bank A/c XX1234 has been Debited with Rs..50 by Transfer.Avl Bal in your A/c is Rs.2,992.54.", open)
        assertTrue("$r", r !is ReadResult.Parsed || (r as ReadResult.Parsed).parsed.amountPaise != 299254L)
    }

    @Test fun setUpsRequestsAndSpamAreNotTransactions() {
        listOf(
            "VM-PNBSMS-S" to "Dear Customer, auto pay facility has been successfully activated on your Punjab National Bank Card XX4356 for Rs. 75000.00, from Google Clouds.",
            "VM-HDFCBK-S" to "Congrats! Rs.60,000 credited to your pre-approved loan account. Click here to claim.",
            "VM-FEDBNK-S" to "Hi, payment of INR 199.00 via e-mandate declined for ID: NX789 on Federal Bank Debit Card 3456.",
            "VM-SLCBNK-S" to "You have received a collect request of Rs. 500 from someone@slc on slice. Approve or decline in the app.",
            "VM-HDFCBK-S" to "Auto Pay HDFC Bank NACH Mandate : Rs. 100000.00 UMRN:HDFC000000001 To:Clearing Freq ADHO received today for processing.",
        ).forEach { (sender, body) ->
            val r = SmsReader.read(sender, body, open)
            assertTrue("must not count: $r for $body", r !is ReadResult.Parsed)
        }
    }

    @Test fun realWorldPhrasingsAreRead() {
        val cases = listOf(
            Triple("VM-FEDBNK-S", "Hi, payment of INR 199.00 for Netflix via e-mandate ID: NX789 on Federal Bank Debit Card 3456 is processed successfully.", 19900L),
            Triple("VM-FEDBNK-S", "Hi! Your txn of ₹882.00 at Carnatic Cafe on your Scapia Federal Visa credit card was successful.", 88200L),
            Triple("VM-HSBCIN-S", "Thank you for using HSBC Debit Card XXXXX7123 at IKEA INDIA . for INR 49.00 on 12-04-26.", 4900L),
            Triple("VM-SIBSMS-S", "UPI debit:Rs.599.00 A/c X7477, 16-10-26 16:25:29 RRN: 565526068910 Bal:Rs.12345.89", 59900L),
            Triple("VM-ICICIB-S", "ICICI Bank Acc XX921 debited Rs. 10,000.00 on 20-Jan-26 NFSCASH WDL. Avb Bal Rs. 3,943.84.", 1_000_000L),
            Triple("VM-KOTAKB-S", "Sent Rs.51.00 from XXXXXX9722 to DINESHBHAI RAMJIBHAI on 30/04/2026. UPI ref no. 648604626824.", 5100L),
        )
        cases.forEach { (sender, body, paise) -> assertEquals(body, paise, read(sender, body).amountPaise) }
        assertEquals(Direction.CREDIT, read("VM-SCBANK-S", "Dear Customer, there is an NEFT credit of INR 48,796.00 in your account 123xxxx7655 on 1/11/2025.").direction)
        assertEquals("9722", read("VM-KOTAKB-S", cases[5].second).source.last4)
        assertEquals("DINESHBHAI RAMJIBHAI", read("VM-KOTAKB-S", cases[5].second).counterparty)
    }

    @Test fun payeeNamesComeOutClean() {
        assertEquals("Amazon Pa", read("VM-BANDHN-S", "INR 180.00 debited from A/c XXXXXXXXXX1234 towards UPI/DR/D123013240123/Amazon Pa Value 16-NOV-2025 . Clear Bal is INR 9999.99.").counterparty)
        assertEquals("Swiggy", read("VM-PNBSMS-S", "A/c X0000 debited INR 125.00 Dt 01-01-26 12:00:00 to Swiggy thru UPI:000000000001..Bal INR 9000.00-PNB").counterparty)
        assertEquals("Swiggy Limi", read("VM-AXISBK-S", "Spent INR 131 Axis Bank Card no. XX0818 05-10-25 09:43:27 IST Swiggy Limi Avl Limit: INR 217162.72").counterparty)
        assertEquals("Swiggy Limited Banga", read("VM-INDUSB-S", "Dear Customer, refund of INR 250 from Swiggy Limited Banga has been credited to your IndusInd Bank Credit Card XX1234 on 11-Jun-26").counterparty)
        assertEquals(null, read("VM-ICICIB-S", "ICICI Bank Account XX123 debited Rs. 5,000.00 on 19-May-26 InfoACH*BD-ACHKFL.Avl Bal Rs. 3,25,000.04.To dispute call 1800 1234").counterparty)
        assertEquals(Kind.TRANSFER, kind("VM-PNBSMS-S", "Thank you Rs.668.79/- has been received as payment towards your PNB credit card XX1234 via Online Payment.").kind)
        assertEquals("Netflix", read("VM-FEDBNK-S", "Hi, payment of INR 199.00 for Netflix via e-mandate ID: NX789 on Federal Bank Debit Card 3456 is processed successfully. To manage, visit: https://x.y").counterparty)
        assertEquals("john.doe123@okbank", read("VM-FEDBNK-S", "Rs 150.00 debited via UPI on 15-08-2026 10:30:25 to VPA john.doe123@okbank.Ref No 987654321098.Small txns?Use UPI Lite!-Federal Bank").counterparty)
        assertEquals("Netflix Entertainment Ser", read("VM-ICICIB-S", "Your account has been debited with Rs 649.00 towards Netflix Entertainment Ser for AutoPay MERCHANTMANDATE. RRN 421723106963.").counterparty)
        assertEquals("CORN", read("VM-KOTAKB-S", "INR 20 spent on Kotak Credit Card x5236 on 23-JAN-2026 at UPI-638903921672-CORN. Avl limit INR 73733.02").counterparty)
        assertEquals(null, read("VM-UCOBNK-S", "A/c XX1234 Credited with Rs.2,000.00 on 21-09-2025 by UCO-UPI.Avl Bal Rs.11111.11.").counterparty)
    }

    @Test fun knownBanksStillUseExactLayouts() {
        assertEquals(Template.HDFC_UPI_SENT, read("VM-HDFCBK-T", "Sent Rs.135.34 From HDFC Bank A/C *1234 To RAVI KUMAR On 25/10/26 Ref 100000000001 Not You?").template)
        assertEquals("AXIS", SmsReader.bankOf("VM-AXISBK-S"))
        assertEquals(null, SmsReader.bankOf("VM-AMAZON-S"))
    }
}
