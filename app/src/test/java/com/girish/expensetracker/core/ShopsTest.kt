package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ShopsTest {

    private val ac = Source("HDFC", "1234", SourceType.ACCOUNT)
    private var n = 0

    private fun pay(name: String, day: String, rs: Long, kind: Kind = Kind.SPEND, type: PayeeType? = PayeeType.MERCHANT, cat: String = "Food") = Txn(
        ParsedSms(Template.HDFC_UPI_SENT, if (kind == Kind.REFUND) Direction.CREDIT else Direction.DEBIT, rs * 100, ac, name, LocalDate.parse(day), "r${n++}", null),
        kind, type, cat, LocalDate.parse(day).atTime(12, 0),
    )

    @Test fun brandVariantsMerge() {
        // Counterparty spellings as banks print them.
        listOf("ZOMATO", "Zomato Ltd", "ZOMATO LIMITED", "ETERNAL LIMITED", "zomato.payu@axisbank", "ETERNALLIMITED").forEach { assertEquals(it, "Zomato", Brands.canonical(it)) }
        listOf("SWIGGY", "Swiggy Instamart", "BUNDL TECHNOLOGIES PVT LTD", "swiggy.rzp@icici").forEach { assertEquals(it, "Swiggy", Brands.canonical(it)) }
        listOf("BLINKIT", "Grofers India Pvt Ltd", "blinkit.payu@hdfcbank").forEach { assertEquals(it, "Blinkit", Brands.canonical(it)) }
        assertEquals("Amazon", Brands.canonical("AMAZON PAY INDIA PRIVATE LIMITED"))
        assertEquals("Ola", Brands.canonical("OLA"))
        assertNull(Brands.brandOf("COCA COLA"))
        assertNull(Brands.brandOf("JIOMART SUPPLIES"))
        assertEquals("Sri Sakthi Agencies", Brands.canonical("SRI SAKTHI AGENCIES PVT LTD"))
        assertEquals(Brands.key("Sri Sakthi Agencies"), Brands.key("SRI SAKTHI AGENCIES"))
    }

    @Test fun bankCutNameJoinsItsFullSpelling() {
        val shops = Shops.all(listOf(pay("ZEESHAN RESTAURANT", "2026-10-01", 300), pay("ZEESHAN RESTAURA", "2026-10-05", 200), pay("Erivum Puliyum", "2026-10-05", 100, type = PayeeType.PERSON)))
        assertEquals(1, shops.size)
        assertEquals("Zeeshan Restaurant", shops[0].name)
        assertEquals(2, shops[0].txns.size)
        assertEquals(shops[0].key, Shops.keyOf(shops[0].txns, "ZEESHAN RESTAURA"))
    }

    @Test fun totalsPerRange() {
        val txns = listOf(
            pay("ZOMATO", "2026-09-10", 400), pay("ETERNAL LIMITED", "2026-10-02", 250), pay("Zomato", "2026-10-20", 750),
            pay("ZOMATO", "2026-10-21", 100, kind = Kind.REFUND), pay("ZOMATO", "2026-11-03", 300), pay("SWIGGY", "2026-11-04", 999),
        )
        val z = Shops.all(txns).first { it.name == "Zomato" }
        val today = LocalDate.of(2026, 11, 8)
        val thisMonth = LocalDate.of(2026, 11, 1)..today
        val last = LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 31)
        assertEquals(30000L, z.spentIn(thisMonth))
        assertEquals(1, z.ordersIn(thisMonth))
        assertEquals(90000L, z.spentIn(last)) // 250 + 750 - 100 refund
        assertEquals(2, z.ordersIn(last))
        assertEquals(50000L, z.averageIn(last))
        assertEquals(75000L, z.biggestIn(last)!!.ownPaise)
        assertEquals(160000L, z.spentIn(z.first..today))
        assertEquals(listOf(YearMonth.of(2026, 9) to 40000L, YearMonth.of(2026, 10) to 90000L, YearMonth.of(2026, 11) to 30000L), z.monthly(today))
    }

    @Test fun peopleAndTransfersAreNotShops() {
        val txns = listOf(pay("RAVI KUMAR", "2026-10-01", 500, type = PayeeType.PERSON), pay("HDFC CARD", "2026-10-01", 500, kind = Kind.TRANSFER))
        assertEquals(0, Shops.all(txns).size)
    }
}
