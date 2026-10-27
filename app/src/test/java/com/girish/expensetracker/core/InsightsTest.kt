package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

private val ACCT = Source("HDFC", "1234", SourceType.ACCOUNT)
private val OPEN = Profile(emptySet(), emptySet(), emptySet(), emptySet(), acceptAll = true)

private fun t(day: String, rs: Long, to: String?, kind: Kind = Kind.SPEND, cat: String = "Food", h: Int = 12, m: Int = 0, salary: Boolean = false, narration: String? = null) = Txn(
    ParsedSms(Template.HDFC_UPI_SENT, if (kind == Kind.INCOME) Direction.CREDIT else Direction.DEBIT, rs * 100, ACCT, to, LocalDate.parse(day), null, narration),
    kind, PayeeType.MERCHANT, cat, LocalDate.parse(day).atTime(h, m), salary,
)

class TeachTest {

    // A layout none of the rules know: amount after "of", payee after "at", account as "acct ending".
    private val example = "Txn of Rs 1,234.50 at FRESH MART BLR done via acct ending 4455 on 03-10-2026. Bal Rs 9,999.00. Help: https://x.y/abc"

    @Test fun learnFromOneExampleAndReadTheNext() {
        val text = Teach.normalize(example)
        val amount = text.indexOf("1,234.50").let { it until it + "1,234.50".length }
        val payee = text.indexOf("FRESH MART BLR").let { it until it + "FRESH MART BLR".length }
        val layout = Teach.learn("FEDERAL", text, amount, payee, debit = true, card = false)!!
        assertEquals("ANDP".toSet(), layout.roles.toSet())

        val next = "Txn of Rs 89.00 at CHAI POINT done via acct ending 4455 on 05-10-2026. Bal Rs 9,910.00. Help: https://x.y/zzz"
        val p = Teach.parse(layout, Teach.normalize(next))!!
        assertEquals(8900L, p.amountPaise)
        assertEquals("CHAI POINT", p.counterparty)
        assertEquals(Source("FEDERAL", "4455", SourceType.ACCOUNT), p.source)
        assertEquals(LocalDate.of(2026, 10, 5), p.date)
        assertNull(Teach.parse(layout, "Your OTP is 123456"))
    }

    @Test fun readerUsesTaughtLayoutForThatBank() {
        val text = Teach.normalize(example)
        val amount = text.indexOf("1,234.50").let { it until it + 8 }
        val layout = Teach.learn("FEDERAL", text, amount, null, debit = true, card = false)!!
        val r = SmsReader.read("VM-FEDBNK-S", example.replace("1,234.50", "300.00"), OPEN, listOf(layout))
        assertTrue("$r", r is ReadResult.Parsed)
        assertEquals(30000L, (r as ReadResult.Parsed).parsed.amountPaise)
    }

    @Test fun noDigitsMeansTheOnlyCardAtThatBank() {
        val sms = "Refund of INR 499.00 credited to your ICICI Bank Card for DEMO STORE on 24-Oct-26."
        val text = Teach.normalize(sms)
        val layout = Teach.learn("ICICI", text, text.indexOf("499.00").let { it until it + 6 }, null, debit = false, card = true)!!
        val me = Profile(emptySet(), setOf(Source("ICICI", "9999", SourceType.CARD)), emptySet(), emptySet())
        val r = SmsReader.read("VA-ICICIT-S", sms.replace("499.00", "120.00"), me, listOf(layout))
        assertEquals(Source("ICICI", "9999", SourceType.CARD), (r as ReadResult.Parsed).parsed.source)
    }

    @Test fun refusesARangeWithNoNumber() {
        assertNull(Teach.learn("X", "hello world 12", 0..4, null, true, false))
    }
}

class InsightsTest {

    private fun money(p: Long) = "₹${p / 100}"

    @Test fun doubleChargeWithinMinutes() {
        val u = UnusualSpend.find(listOf(t("2026-10-20", 499, "ZOMATO", h = 20, m = 1), t("2026-10-20", 499, "ZOMATO", h = 20, m = 4)), LocalDate.of(2026, 10, 21), ::money)
        assertTrue(u.single().title.startsWith("Charged twice?"))
    }

    @Test fun unusuallyLargeAgainstHistory() {
        val history = (1..30).map { t(LocalDate.of(2026, 8, 1).plusDays(it.toLong()).toString(), 300 + it.toLong(), "SHOP$it") }
        val big = t("2026-10-19", 9_000, "CROMA", cat = "Shopping")
        val u = UnusualSpend.find(history + big, LocalDate.of(2026, 10, 20), ::money)
        assertEquals("Unusually large: ₹9000 at CROMA", u.single().title)
        assertTrue(UnusualSpend.find(history + t("2026-10-19", 25_000, "LANDLORD", cat = "Rent"), LocalDate.of(2026, 10, 20), ::money).isEmpty())
    }

    @Test fun foreignCurrencyCharge() {
        assertNotNull(UnusualSpend.foreignCharge("IDFC", "Transaction Successful! USD 9.99 spent on your IDFC FIRST Bank Credit Card ending XX1234 at NETFLIX", LocalDateTime.of(2026, 10, 1, 9, 0)))
        assertNull(UnusualSpend.foreignCharge("IDFC", "Get 5% back on USD 100 spends! Apply now", LocalDateTime.of(2026, 10, 1, 9, 0)))
    }

    @Test fun savingsRatePerSalaryMonth() {
        val txns = listOf(
            t("2026-09-28", 100_000, null, Kind.INCOME, "Salary", salary = true), t("2026-10-05", 30_000, "RENT", cat = "Rent"), t("2026-10-10", 10_000, "AMAZON"),
        )
        val m = Savings.months(txns, listOf(LocalDate.of(2026, 9, 28)..LocalDate.of(2026, 10, 27), LocalDate.of(2026, 8, 28)..LocalDate.of(2026, 9, 27))).single()
        assertEquals(0.6, m.rate, 1e-9)
    }

    @Test fun emiFoundFromMonthlyLenderDebit() {
        val txns = listOf("2026-07-05", "2026-08-05", "2026-09-05", "2026-10-05").map { t(it, 4_250, "BAJAJ FINANCE LTD", cat = "Bills & EMI") } +
            listOf(t("2026-10-06", 4_250, "SWIGGY"))
        val e = Emis.find(txns, LocalDate.of(2026, 10, 20)).single()
        assertEquals(4, e.paid)
        assertEquals(LocalDate.of(2026, 11, 5), e.next)
        assertTrue(Emis.find(txns.take(1), LocalDate.of(2026, 10, 20)).isEmpty())
    }

    @Test fun investmentsPerPlatform() {
        val txns = listOf(
            t("2026-09-02", 5_000, "GROWW INVEST TECH PR", Kind.INVESTMENT_SIP, "Investment"), t("2026-10-02", 5_000, "GROWW INVEST TECH PR", Kind.INVESTMENT_SIP, "Investment"),
            t("2026-10-15", 20_000, "Indian Clearing Corporation Lt", Kind.INVESTMENT_LUMPSUM, "Investment"),
        )
        val h = Investments.holdings(txns, LocalDate.of(2026, 9, 28))
        assertEquals(listOf("Stocks (ICCL)", "Groww"), h.map { it.platform })
        assertEquals(10_000_00L, h[1].sipPaise)
        assertEquals(5_000_00L, h[1].thisMonthPaise)
    }
}
