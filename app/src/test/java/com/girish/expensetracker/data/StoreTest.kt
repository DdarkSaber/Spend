package com.girish.expensetracker.data

import androidx.test.core.app.ApplicationProvider
import com.girish.expensetracker.core.Archive
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SourceMeta
import com.girish.expensetracker.core.SourceOwner
import com.girish.expensetracker.core.SourceStatus
import com.girish.expensetracker.core.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun upgradeFromV5KeepsEverySmsAsChannelSms() {
        context.deleteDatabase("spend.db")
        // A v5 database as v1.0 left it.
        val path = context.getDatabasePath("spend.db").apply { parentFile?.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE sms (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, body TEXT NOT NULL, received_at INTEGER NOT NULL, UNIQUE(sender, body, received_at))")
            db.execSQL("CREATE INDEX sms_time ON sms(received_at)")
            db.execSQL("CREATE TABLE tracked (bank TEXT NOT NULL, last4 TEXT NOT NULL, type TEXT NOT NULL, PRIMARY KEY(bank, last4, type))")
            db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE stmt (id INTEGER PRIMARY KEY AUTOINCREMENT, bank TEXT NOT NULL, last4 TEXT NOT NULL, type TEXT NOT NULL, day TEXT NOT NULL, amount INTEGER NOT NULL, debit INTEGER NOT NULL, description TEXT NOT NULL, UNIQUE(bank, last4, type, day, amount, debit, description))")
            db.execSQL("INSERT INTO sms (sender, body, received_at) VALUES ('VM-HDFCBK-S', 'Sent Rs.10', 1000)")
            db.version = 5
        }
        val store = Store(context)
        val all = store.smsSince(0)
        assertEquals(1, all.size)
        assertEquals("sms", all.single().channel)
        store.setSourceMeta(SourceMeta(Source("SBI", "1633", SourceType.CARD), owner = SourceOwner.ADDON_PAID_BY_OTHER, label = "father"))
        assertEquals("father", store.sourceMetas().values.single().label)
        store.close()
    }

    @Test fun historyFileImportsOnceAndReimportAddsNothing() {
        context.deleteDatabase("spend.db")
        val repo = Repo(Store(context))
        val file = Archive.parse(listOf(
            """{"format":"spend-archive","v":1}""",
            """{"t":"alert","channel":"archive:truecaller","sender":"TC-SBICRD-T","body":"Rs.1,250.00 spent on your SBI Credit Card ending 3652 at SHOP on 01/11/26.","at":1761982200000}""",
            """{"t":"alert","channel":"archive:truecaller","sender":"TC-HDFCBK-T","body":"Sent Rs.250.00 From HDFC Bank A/C *5185 To SHOP On 19/08/26 Ref 123456789012 Not You?","at":1755600000000}""",
            """{"t":"row","channel":"archive:gmail","bank":"HDFC","last4":"5185","type":"ACCOUNT","day":"2026-04-16","amountPaise":120015,"debit":true,"description":"UPI-SHOP"}""",
            """{"t":"alert","channel":"archive:truecaller","sender":"TC-HDFCBK-T","body":"no date"}""",
        ))
        val first = repo.importArchive(file)
        assertEquals(listOf(2, 0), first.counts.map { it.alertsNew })
        assertEquals(listOf(0, 1), first.counts.map { it.rowsNew })
        assertEquals(1, first.rejected.size)
        assertTrue(repo.store.smsSince(0).all { it.channel == "archive:truecaller" })

        val again = repo.importArchive(file)
        assertEquals(0, again.counts.sumOf { it.alertsNew + it.rowsNew })
        assertEquals(2, repo.store.smsSince(0).size)
    }

    @Test fun ignoringStopsTrackingAndUnignoringBringsItBack() {
        context.deleteDatabase("spend.db")
        val repo = Repo(Store(context))
        val card = Source("HDFC", "8164", SourceType.CARD)
        repo.store.track(card)
        repo.saveSourceMeta(SourceMeta(card, SourceStatus.IGNORED))
        assertTrue(card !in repo.store.tracked())
        repo.saveSourceMeta(SourceMeta(card, SourceStatus.PAST))
        assertTrue(card in repo.store.tracked())
    }
}
