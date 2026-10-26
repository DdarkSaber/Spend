package com.girish.expensetracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DemoDataTest {
    @Test fun demoSnapshotBuildsWithEveryState() {
        val s = DemoData.snapshot(LocalDate.of(2026, 10, 27))
        assertTrue("rows: ${s.rows.size}", s.rows.size > 60)
        assertEquals(1, s.unparsed.size)
        assertEquals(1, s.newSources.size)
        assertTrue(s.todayTotal > 0)
        assertTrue(s.monthTotal > s.todayTotal)
        assertEquals(LocalDate.of(2026, 9, 28), s.cycleStart)
        println("demo: rows=${s.rows.size} today=${rupees(s.todayTotal)} month=${rupees(s.monthTotal)}")
    }
}
