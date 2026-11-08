package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarMonthsTest {

    private val ac = Source("HDFC", "1234", SourceType.ACCOUNT)

    private fun salary(day: String, rs: Long) = Txn(
        ParsedSms(Template.HDFC_DEPOSIT, Direction.CREDIT, rs * 100, ac, "CITI", LocalDate.parse(day), "s$day", "SALARY"),
        Kind.INCOME, null, "Income", LocalDate.parse(day).atTime(10, 0), isSalary = true,
    )

    private fun spend(day: String, rs: Long, cat: String = "Food") = Txn(
        ParsedSms(Template.HDFC_UPI_SENT, Direction.DEBIT, rs * 100, ac, "Shop", LocalDate.parse(day), "p$day$rs", null),
        Kind.SPEND, PayeeType.MERCHANT, cat, LocalDate.parse(day).atTime(12, 0),
    )

    @Test fun monthBoundariesAreFirstToLast() {
        val txns = listOf(spend("2026-09-30", 100), spend("2026-10-01", 200), spend("2026-10-31", 300), spend("2026-11-01", 400))
        assertEquals(50000L, Ledger.month(txns, LocalDate.of(2026, 10, 31)))
        assertEquals(40000L, Ledger.month(txns, LocalDate.of(2026, 11, 1)))
        val c = Ledger.cycles(LocalDate.of(2026, 11, 8), txns, 3)
        assertEquals(LocalDate.of(2026, 11, 1)..LocalDate.of(2026, 11, 8), c[0])
        assertEquals(LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 31), c[1])
        // Leap February is a whole month too.
        assertEquals(LocalDate.of(2028, 2, 1)..LocalDate.of(2028, 2, 29), Ledger.cycles(LocalDate.of(2028, 3, 5), emptyList(), 2)[1])
    }

    @Test fun cyclesSinceStopsAtTheFirstMonthWithData() {
        val all = Ledger.cyclesSince(LocalDate.of(2026, 11, 8), emptyList(), LocalDate.of(2026, 8, 31))
        assertEquals(listOf(11, 10, 9, 8), all.map { it.start.monthValue })
        assertEquals(1, Ledger.cyclesSince(LocalDate.of(2026, 11, 8), emptyList(), null).size)
    }

    @Test fun augustDoubleSalaryIsOnePeriodWithAnExtraPayout() {
        val txns = listOf(
            salary("2026-08-07", 384683), salary("2026-08-28", 157887), salary("2026-09-28", 115065), salary("2026-10-28", 115065),
            spend("2026-08-10", 1000), spend("2026-09-27", 500), spend("2026-09-28", 50),
        )
        val p = Ledger.salaryPeriods(txns, LocalDate.of(2026, 11, 8))
        assertEquals(3, p.size)
        assertEquals(LocalDate.of(2026, 8, 7)..LocalDate.of(2026, 9, 27), p[0].range)
        assertEquals(listOf(LocalDate.of(2026, 8, 28)), p[0].extras.map { it.day })
        assertEquals((384683L + 157887L) * 100, p[0].salaryPaise)
        assertEquals(LocalDate.of(2026, 9, 28)..LocalDate.of(2026, 10, 27), p[1].range)
        assertEquals(LocalDate.of(2026, 10, 28)..LocalDate.of(2026, 11, 8), p[2].range)

        val r = SalaryPeriodReports.of(txns, p[0], LocalDate.of(2026, 11, 8))
        assertEquals(150000L, r.spent)
        assertEquals(52, r.days)
        assertEquals(p[0].salaryPaise - 150000L, r.left)
        assertNull(r.ranOutOn)
        assertTrue(!r.running)
        assertTrue(SalaryPeriodReports.of(txns, p[2], LocalDate.of(2026, 11, 8)).running)

        // The calendar month of August still shows both credits.
        val aug = MonthReports.of(txns, LocalDate.of(2026, 8, 1)..LocalDate.of(2026, 8, 31), null, running = false)
        assertEquals((384683L + 157887L) * 100, aug.salaryPaise)
    }

    @Test fun monthsWithoutSalaryAndLongGapsAreHandled() {
        // Salary in Jun 2024, none Jul-Oct 2024, then Nov 2024: one long period, months in the gap have no salary.
        val txns = listOf(salary("2024-06-28", 60000), salary("2024-11-29", 65000), spend("2024-08-15", 2000), spend("2024-12-01", 70000))
        val p = Ledger.salaryPeriods(txns, LocalDate.of(2024, 12, 10))
        assertEquals(2, p.size)
        assertEquals(LocalDate.of(2024, 6, 28)..LocalDate.of(2024, 11, 28), p[0].range)
        val sep = MonthReports.of(txns, LocalDate.of(2024, 9, 1)..LocalDate.of(2024, 9, 30), null, running = false)
        assertEquals(0L, sep.salaryPaise)
        assertNull(sep.salaryDay)
        // Spending past the salary is reported with the day it ran out.
        val r = SalaryPeriodReports.of(txns, p[1], LocalDate.of(2024, 12, 10))
        assertEquals(LocalDate.of(2024, 12, 1), r.ranOutOn)
        assertTrue(r.left < 0)
    }

    @Test fun noSalaryMeansNoPeriods() {
        assertTrue(Ledger.salaryPeriods(listOf(spend("2026-10-01", 10)), LocalDate.of(2026, 11, 8)).isEmpty())
    }
}
