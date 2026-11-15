package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Spend's own Export CSV read back (made-up rows in the exact export layout). */
class SpendExportTest {

    private val csv = """
        date,time,kind,category,payee,source,amount_rupees,counts_today,counts_month,layout
        2026-11-01,13:05:22,SPEND,Food,ZOMATO,SBI card 4415,450.00,450.00,450.00,SBI_CARD_SPENT
        2026-11-02,09:00:00,INCOME,Income,ACME LTD,HDFC a/c 1234,100000.00,0.00,0.00,HDFC_DEPOSIT
        2026-11-03,20:10:00,SPEND,Food,Tea,Cash,30.00,30.00,30.00,MANUAL
        2026-11-04,18:00:00,PERSON_CREDIT,Paid back,"friend, the one",HDFC a/c 1234,200.00,-200.00,-200.00,HDFC_UPI_CREDIT
        2026-11-05,11:00:00,SPEND,Shopping,'=CMD,SBI card · not assigned,99.50,99.50,99.50,GENERIC_CARD_SPENT
        bad,row
    """.trimIndent()

    @Test fun recognisesItsOwnExportOnly() {
        assertTrue(SpendExport.isExport(csv))
        assertFalse(SpendExport.isExport("Date,Narration,Withdrawal Amt.,Deposit Amt.\n01/11/26,UPI-X,10,"))
    }

    @Test fun readsEveryRowBack() {
        val r = SpendExport.parse(csv)
        assertEquals(5, r.rows.size)
        assertEquals(1, r.skipped)
        val (spend, income, cash, back, unassigned) = r.rows
        assertEquals(Source("SBI", "4415", SourceType.CARD), spend.source)
        assertEquals(450_00L, spend.amountPaise)
        assertTrue(spend.debit)
        assertFalse(income.debit)
        assertNull(cash.source)
        assertEquals("Tea", cash.payee)
        assertEquals("friend, the one", back.payee)
        assertFalse(back.debit)
        assertEquals(Source("SBI", UNASSIGNED, SourceType.CARD), unassigned.source)
        assertEquals("=CMD", unassigned.payee)
        assertEquals(99_50L, unassigned.amountPaise)
        assertEquals(LocalDate.parse("2026-11-05"), unassigned.day)
    }
}
