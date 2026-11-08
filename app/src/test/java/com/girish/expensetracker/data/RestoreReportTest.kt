package com.girish.expensetracker.data

import androidx.test.core.app.ApplicationProvider
import com.girish.expensetracker.core.Credit
import com.girish.expensetracker.core.Direction
import com.girish.expensetracker.core.Kind
import com.girish.expensetracker.core.Ledger
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Restores a real backup (-Dbackup=<json>) into the actual Store and prints what the app's snapshot() computes.
 *  Pure report, skipped without the file, so CI and other machines are unaffected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreReportTest {

    private fun rs(p: Long) = "%,d".format(p / 100)

    @Test fun report() {
        val path = System.getProperty("backup").orEmpty()
        assumeTrue(path.isNotEmpty() && File(path).exists())
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("spend.db")
        val store = Store(ctx)
        store.restore(JSONObject(File(path).readText()))
        val snap = Repo(store).snapshot()
        val txns = snap.rows.map { it.txn }

        println("REPORT === counts ===")
        println("REPORT storedSms=${store.smsCount()} rows=${snap.rows.size} unparsed=${snap.unparsed.size} newSources=${snap.newSources.size}")
        println("REPORT today=${snap.today} cycleStart=${snap.cycleStart} todayTotal=${rs(snap.todayTotal)} monthTotal(salaryMonth)=${rs(snap.monthTotal)}")

        println("REPORT === by kind (count, sum of amounts) ===")
        txns.groupBy { it.kind }.toList().sortedBy { it.first.name }.forEach { (k, l) ->
            println("REPORT   $k count=${l.size} amount=${rs(l.sumOf { it.amountPaise })}")
        }

        println("REPORT === by source (debit / credit / count) ===")
        txns.groupBy { it.parsed.source }.toList().sortedBy { it.first.toString() }.forEach { (s, l) ->
            val d = l.filter { it.parsed.direction == Direction.DEBIT }.sumOf { it.amountPaise }
            val c = l.filter { it.parsed.direction == Direction.CREDIT }.sumOf { it.amountPaise }
            println("REPORT   ${s.bank}-${s.last4}-${s.type} debit=${rs(d)} credit=${rs(c)} n=${l.size}")
        }

        println("REPORT === spend by category, this salary month ===")
        txns.filter { it.day >= snap.cycleStart && Ledger.monthAmount(it) > 0 }
            .groupBy { it.category }.mapValues { (_, l) -> l.sumOf { Ledger.monthAmount(it) } }
            .toList().sortedByDescending { it.second }.forEach { (cat, amt) -> println("REPORT   $cat = ${rs(amt)}") }

        println("REPORT === card health ===")
        snap.cards.forEach { h ->
            println("REPORT   ${h.title ?: (h.card.bank + " " + h.card.last4)} outstanding=${rs(h.outstandingPaise)} limit=${h.limitPaise?.let(::rs) ?: "-"} util=${h.utilization?.let { (it*100).toInt().toString()+"%" } ?: "-"} estimated=${h.balanceEstimated} stale=${h.statementStale}")
        }

        println("REPORT === transfers & dedupe ===")
        println("REPORT transfers=${txns.count { it.kind == Kind.TRANSFER }} l2collapses=${snap.collapses.size}")
        println("REPORT === unparsed senders (count) ===")
        snap.unparsed.groupingBy { it.sender }.eachCount().toList().sortedByDescending { it.second }.forEach { (s, n) -> println("REPORT   $s = $n") }

        // independent arithmetic cross-check: month total must equal the sum of monthAmount over this cycle
        val recomputed = txns.filter { it.day >= snap.cycleStart }.sumOf { Ledger.monthAmount(it) }
        println("REPORT crosscheck monthTotal=${snap.monthTotal} recomputed=${recomputed} match=${snap.monthTotal == recomputed}")
    }
}
