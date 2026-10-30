package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class HabitsTest {

    private val acct = Source("HDFC", "1234", SourceType.ACCOUNT)

    private fun t(day: String, rs: Long, to: String, cat: String, kind: Kind = Kind.SPEND, h: Int = 13, type: PayeeType = PayeeType.MERCHANT) = Txn(
        ParsedSms(Template.HDFC_UPI_SENT, Direction.DEBIT, rs * 100, acct, to, LocalDate.parse(day), null, null),
        kind, type, cat, LocalDate.parse(day).atTime(h, 0),
    )

    private val months = listOf(
        LocalDate.of(2026, 9, 28)..LocalDate.of(2026, 10, 27),
        LocalDate.of(2026, 8, 28)..LocalDate.of(2026, 9, 27),
        LocalDate.of(2026, 7, 28)..LocalDate.of(2026, 8, 27),
    )

    // Rent, maid, EB and a SIP every month; food at random.
    private val history = listOf("2026-08", "2026-09", "2026-10").flatMap { m ->
        listOf(
            t("$m-05", 21_000, "SURESH KUMAR", "Rent", type = PayeeType.PERSON),
            t("$m-01", 3_000, "LAKSHMI", "People", type = PayeeType.PERSON),
            t("$m-10", 1_200 + (m.takeLast(2).toLong()), "BESCOM", "Bills"),
            t("$m-02", 5_000, "GROWW", "Investment", Kind.INVESTMENT_SIP),
            t("$m-12", 400, "SWIGGY", "Food"), t("$m-20", 650, "SWIGGY", "Food"),
        )
    }

    @Test fun learnsRentMaidBillsAndSipButNotFood() {
        val fixed = FixedCosts.find(history, months)
        assertEquals(setOf("SURESHKUMAR", "LAKSHMI", "BESCOM", "GROWW"), fixed.map { it.key }.toSet())
    }

    @Test fun rentUnderTwoIdsWithASplitIsStillOneFixedCost() {
        val txns = listOf(
            t("2026-08-05", 21_550, "SURESH KUMAR RAMAN ANAND", "People", type = PayeeType.PERSON),
            t("2026-08-20", 545, "ksuresh20", "People", type = PayeeType.PERSON),
            t("2026-09-05", 21_550, "ksuresh2001@okaxis", "People", type = PayeeType.PERSON),
            t("2026-10-05", 22_366, "ksuresh2001@okaxis", "People", type = PayeeType.PERSON),
            // Three SIPs on the same day every month.
        ) + listOf("2026-08-02", "2026-09-02", "2026-10-02").flatMap { d -> List(3) { t(d, 10_000, "GROWW INVEST TECH PR", "Investment", Kind.INVESTMENT_SIP) } }
        val fixed = FixedCosts.find(txns, months)
        assertEquals(2, fixed.size)
        // Salary came early one month: a short month holding only the split must not hide the rent.
        val uneven = listOf(LocalDate.of(2026, 8, 28)..LocalDate.of(2026, 9, 27), LocalDate.of(2026, 8, 7)..LocalDate.of(2026, 8, 27), LocalDate.of(2026, 7, 28)..LocalDate.of(2026, 8, 6))
        assertEquals(21_550_00L, FixedCosts.find(txns, uneven).first { it.category == "People" }.typicalPaise)
        assertEquals(21_550_00L, fixed.first { it.category == "People" }.typicalPaise)
        assertEquals(30_000_00L, fixed.first { it.category == "Investment" }.typicalPaise)
        // This month: a ₹545 split to the same person is everyday spending; rent is still to come.
        val f = Forecasts.of(txns + t("2026-10-30", 545, "ksuresh20", "People", type = PayeeType.PERSON), LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 31), fixed)
        assertEquals(2, f.stillToCome.size)
        assertEquals(545_00L, f.everydaySoFar)
    }

    @Test fun forecastAddsFixedCostsStillToCome() {
        val fixed = FixedCosts.find(history, months)
        val start = LocalDate.of(2026, 10, 28)
        val now = history + listOf(t("2026-11-01", 3_000, "LAKSHMI", "People", type = PayeeType.PERSON), t("2026-10-30", 500, "SWIGGY", "Food"), t("2026-10-31", 500, "ZOMATO", "Food"))
        val f = Forecasts.of(now, start, LocalDate.of(2026, 11, 1), fixed)
        assertEquals(setOf("SURESHKUMAR", "BESCOM", "GROWW"), f.stillToCome.map { it.key }.toSet())
        assertEquals(1_000_00L, f.everydaySoFar)
        assertEquals(200_00L, f.perDay) // ₹1,000 over 5 days
        assertTrue(f.totalPaise > 30_000_00L)
    }

    @Test fun habitsSplitWeekendsAndTimeOfDay() {
        // 31 Oct 2026 is a Saturday.
        val txns = listOf(t("2026-10-31", 1_400, "PUB", "Food", h = 22), t("2026-10-29", 600, "CAFE", "Food", h = 9))
        val r = Habits.of(txns, emptyList(), LocalDate.of(2026, 11, 1), days = 7)
        assertEquals(700_00L, r.weekendAvg) // ₹1,400 over Sat + Sun
        assertEquals(DayOfWeek.SATURDAY, r.busiestWeekday)
        assertEquals(1_400_00L, r.byTime.first { it.first == "Night" }.second)
    }

    @Test fun streakCountsDaysUnderTheLimit() {
        val d = LocalDate.of(2026, 11, 10)
        val perDay = mapOf(d to 300_00L, d.minusDays(1) to 0L, d.minusDays(2) to 450_00L, d.minusDays(3) to 900_00L)
        val s = Streaks.of(perDay, 500_00, d, d.minusDays(10))
        assertEquals(3, s.current)
        assertEquals(7, s.best) // d-10 .. d-4, before the ₹900 day
        assertEquals(0, Streaks.of(perDay + (d to 800_00L), 500_00, d, d.minusDays(10)).current)
    }

    @Test fun tinyChargeFromANewMerchantIsATrial() {
        val txns = history + t("2026-10-25", 2, "AUDIBLE", "Uncategorised")
        val n = NewRecurrings.find(txns, LocalDate.of(2026, 10, 30))
        assertEquals(listOf("AUDIBLE"), n.map { it.name })
        assertTrue(n.single().trial)
    }
}
