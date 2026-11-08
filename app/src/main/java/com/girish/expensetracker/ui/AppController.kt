package com.girish.expensetracker.ui

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.girish.expensetracker.core.CardHealth
import com.girish.expensetracker.core.StatementCsv
import com.girish.expensetracker.data.Repo
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.UnparsedSms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one bottom sheet that can be open at a time. Adding a sheet means adding a case here and in [SheetHost]. */
internal sealed interface Sheet {
    data class Payment(val row: Row) : Sheet
    data class Split(val row: Row) : Sheet
    /** [preselect]: cards to start with ticked, from the "group your cards?" suggestion. */
    data class CardSettings(val card: CardHealth, val preselect: List<com.girish.expensetracker.core.Source>? = null) : Sheet
    data object Budgets : Sheet
    data class Password(val purpose: Purpose) : Sheet { enum class Purpose { SAVE, RESTORE, AUTO } }
    data class ImportCsv(val result: StatementCsv.Result) : Sheet
    data object Owner : Sheet
    data object Cash : Sheet
    data object NewTrip : Sheet
    data object AutoBackup : Sheet
    data class Teach(val sms: UnparsedSms) : Sheet
    data object Streak : Sheet
    data class Group(val group: com.girish.expensetracker.data.FriendGroup?) : Sheet
    data class SourceSettings(val source: com.girish.expensetracker.core.Source) : Sheet
    data class OlderSources(val result: Repo.ArchiveResult) : Sheet
    data object AlertApps : Sheet
}

/** Full-screen pages that sit above the tabs (back returns to the tab). */
internal sealed interface Page {
    data object Search : Page
    data object Trips : Page
    data object Habits : Page
    data object Year : Page
    data object Sources : Page
    data class Person(val key: String) : Page
    data class SalaryPeriod(val start: java.time.LocalDate) : Page
}

/**
 * Runs the app's actions: database work off the main thread, then a short message and a reload. Screens and sheets
 * only describe what to do ([act]); how it's done and reported lives here.
 */
internal class AppController(val context: Context, val repo: Repo, private val scope: CoroutineScope, val snackbar: SnackbarHostState) {

    var sheet by mutableStateOf<Sheet?>(null)
    var page by mutableStateOf<Page?>(null)
    var reloads by mutableIntStateOf(0)
        private set

    fun reload() { reloads++ }

    /** What is being imported, items done and total, while an import runs; null otherwise. */
    var importing by mutableStateOf<Triple<String, Int, Int>?>(null)
        private set

    /** Called from the import thread after each chunk. */
    fun importProgress(done: Int, total: Int) = importProgress("Importing SMS history", done, total)

    fun importProgress(what: String, done: Int, total: Int) { scope.launch { importing = Triple(what, done, total) } }

    fun importDone() { scope.launch { importing = null } }

    fun say(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    /**
     * Do [work] on a background thread; show the message it returns (if any), close the sheet and reload. With [undo],
     * the message offers "Undo", which runs it (also off the main thread) and reloads again.
     */
    fun act(closeSheet: Boolean = true, undo: (suspend Repo.() -> Unit)? = null, work: suspend Repo.() -> String?) {
        scope.launch {
            var failed = false
            val message = runCatching { withContext(Dispatchers.IO) { repo.work() } }.getOrElse { failed = true; "Something went wrong: ${it.message}" }
            if (closeSheet) sheet = null
            reload()
            if (message == null) return@launch
            if (undo == null || failed) { say(message); return@launch }
            val r = snackbar.showSnackbar(message, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                runCatching { withContext(Dispatchers.IO) { repo.undo() } }
                reload()
            }
        }
    }

    /** Background work whose result feeds the UI (e.g. a picked file), without a reload. */
    fun <T> load(work: suspend () -> T, then: (T) -> Unit) {
        scope.launch { then(withContext(Dispatchers.IO) { work() }) }
    }
}
