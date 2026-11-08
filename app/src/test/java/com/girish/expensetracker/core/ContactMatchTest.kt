package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Made-up contacts and payee spellings in the shapes banks print. */
class ContactMatchTest {

    private val contacts = listOf(
        Contact("Rakshak Office", listOf("+91 98765 43210")),
        Contact("Suresh Kumar", listOf("09876500001")),
        Contact("Suresh Babu", listOf("9876500002")),
        Contact("Anitha Venkatraman", emptyList()),
        Contact("Dad", listOf("98450 12345")),
        Contact("Dad (old)", listOf("9845012345")),
    )

    private fun m(label: String) = ContactMatch.match(label, contacts)?.name

    @Test fun mainWordMatchesIgnoringInitialsAndHonorifics() {
        assertEquals("Rakshak Office", m("RAKSHAK S"))
        assertEquals("Rakshak Office", m("Mr. M RAKSHAK"))
        assertEquals("Rakshak Office", m("SHRI RAKSHAK K V"))
        assertEquals(listOf("RAKSHAK"), ContactMatch.tokens("Mr. R RAKSHAK 2"))
    }

    @Test fun sharedFirstNamesAreLeftAlone() {
        // SURESH (the main word) is in two contacts: left alone, even with KUMAR alongside.
        assertNull(m("SURESH"))
        assertNull(m("SURESH KUMAR"))
    }

    @Test fun bankCutLastWordMatchesByPrefix() {
        assertEquals("Anitha Venkatraman", m("ANITHA VENKATRA"))  // 15 characters, last word cut
        assertNull(m("VENKATRA")) // short name: not cut, no whole-word match
    }

    @Test fun phoneUpiIdsMatchByNumber() {
        assertEquals("Rakshak Office", m("9876543210@ybl"))
        assertEquals("Rakshak Office", m("919876543210@paytm"))
        assertEquals("Suresh Kumar", m("9876500001@axl"))
        // Same number saved twice under different names: not one clear contact.
        assertNull(m("9845012345@ybl"))
        assertNull(m("1111111111@ybl"))
    }

    @Test fun shopsAreNeverMatched() {
        val withShopLikeContact = contacts + Contact("Zomato Support", emptyList()) + Contact("Sakthi Agencies", emptyList())
        assertNull(ContactMatch.match("ZOMATO", withShopLikeContact))
        assertNull(ContactMatch.match("SRI SAKTHI AGENCIES", withShopLikeContact))
        assertFalse(ContactMatch.isCandidate("ABC TRADERS"))
        assertTrue(ContactMatch.isCandidate("RAKSHAK S"))
    }

    @Test fun matchesRoundTripThroughSettings() {
        val all = ContactMatch.matchAll(listOf("RAKSHAK S", "9876543210@ybl", "SURESH", "ZOMATO"), contacts)
        assertEquals(mapOf("RAKSHAK S" to "Rakshak Office", "9876543210@ybl" to "Rakshak Office"), all)
        assertEquals(all, ContactMatch.decode(ContactMatch.encode(all)))
    }

    @Test fun matchedPayeesBecomePeopleButOwnRulesWin() {
        val rules = PayeeRules.of("ERIVUM PULIYUM" to PayeeRules.Rule(PayeeType.MERCHANT, "Food"))
            .withDefaults(mapOf("ERIVUM PULIYUM" to PayeeRules.Rule(PayeeType.PERSON, "People"), "Mr X" to PayeeRules.Rule(PayeeType.PERSON, "People")))
        assertEquals(PayeeType.MERCHANT, rules.lookup("ERIVUM PULIYUM")!!.type)
        assertEquals(PayeeType.PERSON, rules.lookup("MR X")!!.type)
    }

    @Test fun reviewListsDefaultPeopleAndPreTicksLikelyShops() {
        val ac = Source("HDFC", "1234", SourceType.ACCOUNT)
        var n = 0
        fun t(name: String, rs: Long, dir: Direction = Direction.DEBIT) = Txn(
            ParsedSms(Template.HDFC_UPI_SENT, dir, rs, ac, name, LocalDate.of(2026, 10, 1), "r${n++}", null),
            if (dir == Direction.DEBIT) Kind.SPEND else Kind.PERSON_CREDIT, PayeeType.PERSON, "People", LocalDate.of(2026, 10, 1).atTime(9, 0),
        )
        val txns = listOf(
            t("ERIVUM PULIYUM", 18_950), t("ERIVUM PULIYUM", 24_000 + 37), t("ERIVUM PULIYUM", 31_210),  // uneven, 3x, never paid back
            t("RAVI K", 50_000), t("RAVI K", 20_000, Direction.CREDIT),                                      // a friend
            t("Mr ANIL", 10_000), t("RAKSHAK S", 1_000), t("OWNED", 100),
        )
        val r = PayeeReviews.of(txns, { it == "OWNED" }, setOf("RAKSHAK S"))
        assertEquals(listOf("ERIVUM PULIYUM", "RAVI K"), r.map { it.label })
        assertTrue(r[0].likelyShop)
        assertFalse(r[1].likelyShop)
    }
}
