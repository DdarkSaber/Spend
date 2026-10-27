package com.girish.expensetracker.ui

import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.girish.expensetracker.core.StatementCsv
import com.girish.expensetracker.data.AutoBackup
import com.girish.expensetracker.data.Backup
import java.time.LocalDate

/**
 * Android's own pickers (contacts, files). The app needs no storage or contacts permission: the owner picks one
 * file or contact and the app gets access to just that.
 */
internal class Pickers(
    val pickContact: () -> Unit,
    val contactName: () -> String?,
    val contactUsed: () -> Unit,
    /** Ask where to save, then write a backup encrypted with [CharArray]. */
    val saveBackup: (CharArray) -> Unit,
    val restoreBackup: (CharArray) -> Unit,
    /** Ask for a file once, remember it, and back up there every week. */
    val startAutoBackup: (CharArray) -> Unit,
    val openCsv: () -> Unit,
)

@Composable
internal fun rememberPickers(ctl: AppController): Pickers {
    var contact by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf<CharArray?>(null) }

    // The password waits here between the password sheet and the system picker, then is wiped.
    fun takePassword(): CharArray? = password.also { password = null }

    val contactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri: Uri? ->
        if (uri != null) ctl.load({
            runCatching {
                ctl.context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }) { contact = it }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = takePassword() ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult pw.fill(' ')
        ctl.act {
            val msg = runCatching { ctl.context.contentResolver.openOutputStream(uri)!!.use { Backup.write(store, pw, it) }; "Backup saved. Keep the password safe." }
                .getOrElse { "Couldn't save the backup: ${it.message}" }
            pw.fill(' ')
            msg
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pw = takePassword() ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult pw.fill(' ')
        ctl.act {
            val msg = runCatching { ctl.context.contentResolver.openInputStream(uri)!!.use { Backup.read(store, pw, it) }; "Backup restored" }
                .getOrElse { if (it is Backup.WrongPassword) "Wrong password, or not a Spend backup" else "Couldn't restore: ${it.message}" }
            pw.fill(' ')
            msg
        }
    }
    val autoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = takePassword() ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult pw.fill(' ')
        ctl.act {
            val msg = AutoBackup.enable(ctl.context, store, uri, pw).fold(
                { "Automatic backup on: saved now, then every week" },
                { "Couldn't set up automatic backup: ${it.message}" },
            )
            pw.fill(' ')
            msg
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ctl.load({
            runCatching { StatementCsv.parse(ctl.context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }) }.getOrNull()
        }) { r ->
            when {
                r == null -> ctl.say("Couldn't read that file")
                r.problem != null -> ctl.say(r.problem)
                else -> ctl.sheet = Sheet.ImportCsv(r)
            }
        }
    }

    return remember {
        Pickers(
            pickContact = { contactLauncher.launch(null) },
            contactName = { contact },
            contactUsed = { contact = null },
            saveBackup = { pw -> password = pw; saveLauncher.launch("spend-backup-${LocalDate.now()}.spendbackup") },
            restoreBackup = { pw -> password = pw; restoreLauncher.launch(arrayOf("*/*")) },
            startAutoBackup = { pw -> password = pw; autoLauncher.launch("spend-auto-backup.spendbackup") },
            openCsv = { csvLauncher.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) },
        )
    }
}
