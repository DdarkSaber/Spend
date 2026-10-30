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

    private val sep = LocalDate.of(2026, 8, 28)..LocalDate.of(2026, 9, 27)
    private val aug = LocalDate.of(2026, 7, 28)..LocalDate.of(2026, 8, 27)

    private val txns = listOf(
        t("2026-07-30", 500, "SWIGGY", "Food"), t("2026-08-20", 900, "SWIGGY", "Food"),
        t("2026-08-28", 100_000, "EMPLOYER", "Salary", Kind.INCOME, salary = true),
        t("2026-08-30", 400, "SWIGGY", "Food"), t("2026-08-30", 600, "ZOMATO", "Food"),
        t("2026-09-10", 2_000, "BLINKIT", "Groceries"), t("2026-09-11", 300, "SWIGGY", "Food"),
    )

    @Test fun pastMonthAgainstTheWholeMonthBefore() {
        val r = MonthReports.of(txns, sep, aug, running = false)
        assertEquals(mapOf("Food" to 1_300_00L, "Groceries" to 2_000_00L), r.byCategory)
        assertEquals(1_400_00L, r.totalBefore)
        assertEquals(4, r.payments)
        assertEquals(0.967, r.savingsRate!!, 0.001)
        assertEquals(LocalDate.of(2026, 9, 10) to 2_000_00L, r.biggestDay)
        assertEquals(listOf("BLINKIT" to 2_000_00L, "SWIGGY" to 700_00L, "ZOMATO" to 600_00L), r.topPlaces)
    }

    @Test fun runningMonthComparesTheSameDays() {
        val soFar = LocalDate.of(2026, 8, 28)..LocalDate.of(2026, 9, 1)
        // Only the first 5 days of August's month (28 Jul - 1 Aug) count as "before": the ₹500 on 30 Jul, not the ₹900 on 20 Aug.
        assertEquals(500_00L, MonthReports.of(txns, soFar, aug, running = true).totalBefore)
    }
}
