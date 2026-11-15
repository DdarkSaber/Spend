package com.girish.expensetracker.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.girish.expensetracker.BuildConfig
import com.girish.expensetracker.core.ParseCache
import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * More → Export debug bundle: the audit log (bank alerts only, see [Audit.redact]), the payments the owner reported
 * ([Issues]), a copy of the database and the app version in one zip, handed to the share sheet. Only the owner sends it anywhere (for the validation report); it is never uploaded by the app.
 */
object DebugBundle {

    fun build(context: Context, store: Store): File {
        // Fold the write-ahead log into the main file so the copy is complete.
        runCatching { store.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } }
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("spend-debug-") }?.forEach { it.delete() }
        val stamp = LocalDateTime.now().toString().take(16).replace(':', '-')
        val zip = File(dir, "spend-debug-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun add(name: String, file: File) {
                if (!file.exists()) return
                out.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
            // Lines logged by older versions held every SMS and notification in full: they are redacted on the way out.
            Audit.files(context).forEach { f ->
                out.putNextEntry(ZipEntry("audit/${f.name}"))
                f.useLines { lines -> lines.filter { it.isNotBlank() }.forEach { out.write((Audit.redactLine(it) + "\n").toByteArray()) } }
                out.closeEntry()
            }
            Issues.file(context)?.let { add("issues.jsonl", it) }
            add("spend.db", context.getDatabasePath("spend.db"))
            out.putNextEntry(ZipEntry("version.txt"))
            out.write(buildString {
                appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
                appendLine("store schema $STORE_VERSION")
                appendLine("parser ${ParseCache.PARSER_VERSION}")
                appendLine("android ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("created ${LocalDateTime.now()}")
            }.toByteArray())
            out.closeEntry()
        }
        return zip
    }

    fun share(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", zip)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share debug bundle").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
