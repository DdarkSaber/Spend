package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ArchiveTest {

    private val header = """{"format":"spend-archive","v":1}"""

    @Test fun readsAlertsAndRowsAndRejectsUndated() {
        val p = Archive.parse(listOf(
            header,
            """{"t":"alert","channel":"archive:truecaller","sender":"TC-SBICRD-T","body":"Rs.1,250.00 spent on your SBI Credit Card ending 3652 at SHOP on 01/11/26.","at":1761982200000}""",
            "",
            """{"t":"row","channel":"archive:gmail","bank":"hdfc","last4":"5185","type":"ACCOUNT","day":"2026-04-16","amountPaise":120015,"debit":true,"description":"UPI-SHOP"}""",
            """{"t":"alert","channel":"archive:truecaller","sender":"TC-HDFCBK-T","body":"Sent Rs.10"}""",
            """{"t":"alert","channel":"archive:truecaller","sender":"TC-HDFCBK-T","body":"Sent Rs.10","at":null}""",
            """{"t":"alert","channel":"sms","sender":"TC-HDFCBK-T","body":"x","at":5}""",
            """{"t":"row","channel":"archive:gmail","bank":"HDFC","last4":"5185","type":"ACCOUNT","day":"16/04/2026","amountPaise":1,"debit":true}""",
            "not json",
        ))
        assertNull(p.problem)
        assertEquals(2, p.items.size)
        val alert = p.items[0] as Archive.Item.Alert
        assertEquals("archive:truecaller", alert.channel)
        assertEquals(1761982200000L, alert.atMillis)
        val row = p.items[1] as Archive.Item.Row
        assertEquals(Source("HDFC", "5185", SourceType.ACCOUNT), row.source)
        assertEquals(LocalDate.of(2026, 4, 16), row.day)
        assertEquals(120015L, row.amountPaise)
        assertEquals(listOf(5, 6, 7, 8, 9), p.rejected.map { it.line })
        assertEquals("alert has no date", p.rejected[0].reason)
        assertEquals("alert has no date", p.rejected[1].reason)
    }

    @Test fun refusesOtherFiles() {
        assertEquals("The file is empty", Archive.parse(listOf("", " ")).problem)
        assertEquals("Not a Spend history file", Archive.parse(listOf("""{"a":1}""")).problem)
        assertEquals("Not a Spend history file", Archive.parse(listOf("Date,Narration")).problem)
        assertEquals("This history file needs a newer version of Spend", Archive.parse(listOf("""{"format":"spend-archive","v":2}""")).problem)
    }

    @Test fun statusFollowsActivityUnlessTheOwnerChose() {
        val today = LocalDate.of(2026, 11, 8)
        val card = Source("SBI", "1633", SourceType.CARD)
        assertEquals(SourceStatus.ACTIVE, SourceMeta.effective(null, today.minusDays(120), today))
        assertEquals(SourceStatus.PAST, SourceMeta.effective(null, today.minusDays(121), today))
        assertEquals(SourceStatus.PAST, SourceMeta.effective(null, null, today))
        assertEquals(SourceStatus.ACTIVE, SourceMeta.effective(SourceMeta(card, SourceStatus.ACTIVE), null, today))
        assertEquals(SourceStatus.IGNORED, SourceMeta.effective(SourceMeta(card, SourceStatus.IGNORED), today, today))
        assertEquals(card, SourceMeta.parseKey(SourceMeta.keyOf(card)))
        assertEquals("add-on (paid by father)", SourceMeta.describe(SourceMeta(card, owner = SourceOwner.ADDON_PAID_BY_OTHER, label = "father")))
    }
}
