package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatementCsvTest {

    @Test fun withdrawalAndDepositColumns() {
        val csv = """
            Statement of account
            Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/08/26,UPI-SWIGGY-swiggy@icici-ICIC0000001-600000000001-ORDER,0000600000000001,01/08/26,"1,250.00",,"98,750.00"
            02/08/26,NEFT CR-ACME CORP-SALARY AUG,N000000000001,02/08/26,,"85,000.00","1,83,750.00"
            ,,,,,,
        """.trimIndent()
        val r = StatementCsv.parse(csv)
        assertEquals(2, r.rows.size)
        assertEquals(CsvRow(LocalDate.of(2026, 8, 1), 125000L, true, "UPI-SWIGGY-swiggy@icici-ICIC0000001-600000000001-ORDER"), r.rows[0])
        assertEquals(false, r.rows[1].debit)
        assertEquals(8500000L, r.rows[1].amountPaise)
    }

    @Test fun singleAmountWithDrCrColumnSemicolons() {
        val csv = "Txn Date;Description;Amount;Dr/Cr\n05-Aug-2026;POS ZOMATO;499.00;DR\n06-Aug-2026;REFUND ZOMATO;499.00;CR\n"
        val r = StatementCsv.parse(csv)
        assertEquals(listOf(true, false), r.rows.map { it.debit })
        assertEquals(LocalDate.of(2026, 8, 5), r.rows[0].day)
    }

    @Test fun signedAmounts() {
        val r = StatementCsv.parse("Date,Description,Amount\n2026-08-07,AMAZON,-1299.00\n2026-08-08,INTEREST,12.40\n")
        assertEquals(listOf(true, false), r.rows.map { it.debit })
    }

    @Test fun counterpartyFromStatementNarration() {
        assertEquals("RAVI K", Generic.counterparty("UPI/DR/600000000001/RAVI K/SBIN/ravi@oksbi/Payment", Direction.DEBIT))
        assertNotNull(Generic.counterparty("UPI/DR/600000000001/SWIGGY/YESB/payment", Direction.DEBIT))
    }

    @Test fun notAStatement() {
        val r = StatementCsv.parse("name,age\nasha,30\n")
        assertTrue(r.rows.isEmpty())
        assertNotNull(r.problem)
    }
}
