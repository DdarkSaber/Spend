package com.girish.expensetracker.data

import androidx.test.core.app.ApplicationProvider
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationIngestTest {

    private lateinit var repo: Repo
    private val tc = "com.truecaller"
    private val spend = "Rs.1000.00 spent on your SBI Credit Card ending 1111 at ZOMATO on 01/10/26. Trxn."
    private val t0 = 1_759_300_000_000L

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("spend.db")
        repo = Repo(Store(context))
        repo.store.track(Source("SBI", "1111", SourceType.CARD))
    }

    @Test fun storedWithItsChannelAndRepostsAreDropped() {
        val first = repo.ingestNotification(tc, "SBI Card", spend, t0)
        assertEquals("stored", first.decision)
        assertEquals("NT-SBICRD-T", first.sender)
        assertEquals("notif:$tc", repo.store.smsSince(0).single().channel)
        assertEquals("repost", repo.ingestNotification(tc, "SBI Card", spend, t0 + 5_000).decision)
    }

    @Test fun sameTextAlreadyBySmsStoresNothing() {
        repo.store.insertSmsId("VM-SBICRD-S", spend, t0)
        val o = repo.ingestNotification(tc, "SBI Card", "  $spend ", t0 + 60_000)
        assertEquals("dup-text", o.decision)
        assertNull(o.smsId)
        assertEquals(1, repo.store.smsSince(0).size)
    }

    @Test fun nonBankAndOtpNeverStored() {
        assertEquals("no-bank", repo.ingestNotification(tc, "Swiggy", "Your order is on the way", t0).decision)
        assertEquals("gate", repo.ingestNotification(tc, "HDFC Bank", "123456 is your OTP for Rs.500 on HDFC Bank card 4321", t0).decision)
        assertEquals(0, repo.store.smsSince(0).size)
    }

    private val gm = "com.google.android.apps.messaging"

    @Test fun anSmsNotificationKeepsItsRealSender() {
        val offer = "Convert your SBI Credit Card transactions of the combined value of Rs. 12,345 into Flexipay EMIs. Offer Valid till 15 Nov. Book Now"
        // Titled with the promotional sender: an offer, as the SMS itself is.
        val o = repo.ingestNotification(gm, "AX-SBICRD-P", offer, t0, from = "AX-SBICRD-P")
        assertEquals("gate", o.decision)
        assertEquals("offer", o.gate)
        assertEquals("AX-SBICRD-P", o.sender)
        // A registered sender that is not a bank, even when its text names one.
        assertEquals("no-bank", repo.ingestNotification(gm, "VM-SHOPPY-S", "Rs.500 paid with your HDFC Bank card 4321 at Shoppy", t0, from = "VM-SHOPPY-S").decision)
        // A real bank sender is stored under that sender.
        val real = repo.ingestNotification(gm, "VM-SBICRD-S", spend, t0, from = "vm-sbicrd-s")
        assertEquals("stored", real.decision)
        assertEquals("VM-SBICRD-S", repo.store.smsSince(0).single().sender)
    }

    @Test fun aTextFromAPhoneNumberIsNeverABankAlert() {
        repo.store.track(Source("SBI", "5678", SourceType.ACCOUNT))
        val scam = "Rs 5000 debited from your SBI account towards loan repayment. If not you call 9876543210"
        val o = repo.ingestNotification(gm, "+91 98765 43210", scam, t0, from = "+91 98765 43210")
        assertEquals("phone-sender", o.gate)
        assertEquals(0, repo.store.smsSince(0).size)
    }

    @Test fun anSmsTheReaderTurnedAwayIsNotLetInAsANotification() {
        repo.store.track(Source("HDFC", "5185", SourceType.ACCOUNT))
        val friend = "bro I sent Rs 500 to your HDFC Bank a/c 5185 check once"
        // SMS first (from a saved contact, so the title is a name): turned away, then its notification is too.
        assertNull(repo.ingestId("+919876543210", friend, t0))
        val o = repo.ingestNotification(gm, "Arun", friend, t0 + 2_000, from = "Arun")
        assertEquals("sms-rejected", o.gate)
        assertEquals(0, repo.store.smsSince(0).size)
    }

    @Test fun aNotificationCopyStoredFirstIsRemovedWhenItsSmsIsTurnedAway() {
        repo.store.track(Source("HDFC", "5185", SourceType.ACCOUNT))
        val friend = "bro I sent Rs 700 to your HDFC Bank a/c 5185 check once"
        assertEquals("stored", repo.ingestNotification(gm, "Arun", friend, t0, from = "Arun").decision)
        val before = repo.removedCopies
        assertNull(repo.ingestId("+919876543210", friend, t0 + 3_000))
        assertEquals(0, repo.store.smsSince(0).size)
        assertEquals(before + 1, repo.removedCopies)
        // A bank alert stored from a notification is untouched by an unrelated turned-away SMS.
        repo.ingestNotification(tc, "SBI Card", spend, t0)
        assertNull(repo.ingestId("+919876543210", "see you at 8", t0 + 1_000))
        assertEquals(1, repo.store.smsSince(0).size)
    }

    @Test fun allowlistDefaultsAndSaves() {
        assertEquals(listOf("com.google.android.apps.messaging", "com.truecaller", "com.google.android.gm"), repo.alertApps().map { it.pkg })
        repo.setAlertApps(repo.alertApps().map { if (it.pkg == "com.google.android.gm") it.copy(enabled = false) else it } + Repo.AlertApp("in.amazon.mShop", "Amazon", true))
        assertEquals(listOf(true, true, false, true), repo.alertApps().map { it.enabled })
    }
}
