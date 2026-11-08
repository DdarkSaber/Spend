package com.girish.expensetracker.data

import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SourceMeta
import com.girish.expensetracker.core.SourceOwner
import com.girish.expensetracker.core.SourceStatus
import com.girish.expensetracker.core.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SourceSnapshotTest {

    private val today = LocalDate.of(2026, 11, 8)
    private fun card(d: String) = Source("SBI", d, SourceType.CARD)

    @Test fun cardHealthCoversActiveOwnCreditCardsOnly() {
        val hdfcDebit = Source("HDFC", "8265", SourceType.CARD)
        val hdfcAc = Source("HDFC", "5185", SourceType.ACCOUNT)
        val tracked = setOf(card("1633"), card("3652"), card("4415"), card("9000"), card("9001"), hdfcDebit, hdfcAc)
        val snap = Snapshot(
            rows = emptyList(), unparsed = emptyList(), newSources = emptyList(), today = today,
            owner = Owner("confirmed", "Me", 28, tracked),
            sourceMeta = mapOf(
                card("1633") to SourceMeta(card("1633"), owner = SourceOwner.ADDON_PAID_BY_OTHER, label = "father"),
                hdfcDebit to SourceMeta(hdfcDebit, linkedTo = hdfcAc),
                card("9001") to SourceMeta(card("9001"), status = SourceStatus.ACTIVE),
            ),
            lastActivity = mapOf(
                card("1633") to today, card("3652") to today, card("4415") to today.minusDays(120),
                card("9000") to today.minusDays(400), hdfcDebit to today,
            ),
        )
        // 1633 add-on, 8265 a linked debit card, 9000 past; 9001 kept active by the owner despite no activity.
        assertEquals(listOf("3652", "4415", "9001"), snap.trackedCards.map { it.last4 })
        assertEquals(SourceStatus.PAST, snap.statusOf(card("9000")))
        assertEquals(SourceStatus.ACTIVE, snap.statusOf(card("1633")))
    }
}
