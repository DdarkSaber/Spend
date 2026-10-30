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
    data class CardSettings(val card: CardHealth) : Sheet
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
}

/** Full-screen pages that sit above the tabs (back returns to the tab). */
internal sealed interface Page {
    data object Search : Page
    data object Trips : Page
    data object Habits : Page
    data object Year : Page
    data class Person(val key: String) : Page
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

    fun say(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    /** Do [work] on a background thread; show the message it returns (if any), close the sheet and reload. */
    fun act(closeSheet: Boolean = true, work: suspend Repo.() -> String?) {
        scope.launch {
            val message = runCatching { withContext(Dispatchers.IO) { repo.work() } }.getOrElse { "Something went wrong: ${it.message}" }
            if (closeSheet) sheet = null
            message?.let(::say)
            reload()
        }
    }

    /** Background work whose result feeds the UI (e.g. a picked file), without a reload. */
    fun <T> load(work: suspend () -> T, then: (T) -> Unit) {
        scope.launch { then(withContext(Dispatchers.IO) { work() }) }
    }
}
