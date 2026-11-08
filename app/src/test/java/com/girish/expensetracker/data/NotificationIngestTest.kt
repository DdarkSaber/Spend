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

    @Test fun allowlistDefaultsAndSaves() {
        assertEquals(listOf("com.google.android.apps.messaging", "com.truecaller", "com.google.android.gm"), repo.alertApps().map { it.pkg })
        repo.setAlertApps(repo.alertApps().map { if (it.pkg == "com.google.android.gm") it.copy(enabled = false) else it } + Repo.AlertApp("in.amazon.mShop", "Amazon", true))
        assertEquals(listOf(true, true, false, true), repo.alertApps().map { it.enabled })
    }
}
