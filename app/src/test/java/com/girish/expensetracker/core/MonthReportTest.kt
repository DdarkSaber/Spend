package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MonthReportTest {

    private val acct = Source("HDFC", "1234", SourceType.ACCOUNT)

    private fun t(day: String, rs: Long, to: String, cat: String, kind: Kind = Kind.SPEND, salary: Boolean = false) = Txn(
        ParsedSms(Template.HDFC_UPI_SENT, if (kind == Kind.INCOME) Direction.CREDIT else Direction.DEBIT, rs * 100, acct, to, LocalDate.parse(day), null, null),
        kind, PayeeType.MERCHANT, cat, LocalDate.parse(day).atTime(12, 0), salary,
    )

    private val sep = LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 9, 30)
    private val aug = LocalDate.of(2026, 8, 1)..LocalDate.of(2026, 8, 31)
    private val jul = LocalDate.of(2026, 7, 1)..LocalDate.of(2026, 7, 31)

    private val txns = listOf(
        t("2026-07-30", 500, "SWIGGY", "Food"), t("2026-08-20", 900, "SWIGGY", "Food"),
        t("2026-08-28", 100_000, "EMPLOYER", "Salary", Kind.INCOME, salary = true),
        t("2026-08-30", 400, "SWIGGY", "Food"), t("2026-08-30", 600, "ZOMATO", "Food"),
        t("2026-09-10", 2_000, "BLINKIT", "Groceries"), t("2026-09-11", 300, "SWIGGY", "Food"),
    )

    @Test fun pastMonthAgainstTheWholeMonthBefore() {
        val r = MonthReports.of(txns, sep, aug, running = false)
        assertEquals(mapOf("Food" to 300_00L, "Groceries" to 2_000_00L), r.byCategory)
        assertEquals(1_900_00L, r.totalBefore)
        assertEquals(2, r.payments)
        // The 28 Aug salary is September's (credited from the 20th: the next month is lived on it).
        assertEquals(100_000_00L, r.salaryPaise)
        assertEquals(0.977, r.savingsRate!!, 0.001)
        assertEquals(0L, MonthReports.of(txns, aug, jul, running = false).salaryPaise)
        assertEquals(LocalDate.of(2026, 9, 10) to 2_000_00L, r.biggestDay)
        assertEquals(listOf("Blinkit" to 2_000_00L, "Swiggy" to 300_00L), r.topPlaces)
        assertEquals(listOf("Swiggy" to 1_300_00L, "Zomato" to 600_00L), MonthReports.of(txns, aug, jul, running = false).topPlaces)
    }

    @Test fun runningMonthComparesTheSameDays() {
        // On 20 Aug, "before" is 1-20 Jul (nothing), not the whole of July (the ₹500 on 30 Jul).
        val soFar = LocalDate.of(2026, 8, 1)..LocalDate.of(2026, 8, 20)
        assertEquals(0L, MonthReports.of(txns, soFar, jul, running = true).totalBefore)
        assertEquals(500_00L, MonthReports.of(txns, aug, jul, running = false).totalBefore)
    }
}
