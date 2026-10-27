package com.girish.expensetracker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Weekly encrypted backup to a file the owner picked once (a Google Drive folder, OneDrive, or the phone). The app
 * still has no internet permission: it writes through Android's document provider and the Drive app uploads.
 * The backup password is kept encrypted by a key in the Android Keystore, which never leaves the phone's hardware.
 */
object AutoBackup {

    private const val PREFS = "autobackup"
    private const val ALIAS = "spend_auto_backup"
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    data class State(val on: Boolean, val lastMillis: Long, val lastError: String?)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(c: Context) = prefs(c).let { State(it.getString("uri", null) != null, it.getLong("last", 0L), it.getString("error", null)) }

    /** Remember the file and password, then back up right away. */
    fun enable(c: Context, store: Store, uri: Uri, password: CharArray): Result<Unit> = runCatching {
        c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs(c).edit().putString("uri", uri.toString()).putString("pw", seal(password)).apply()
        write(c, store, uri, password)
    }

    fun disable(c: Context) {
        prefs(c).getString("uri", null)?.let { u ->
            runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs(c).edit().clear().apply()
    }

    /** Called from the midnight alarm: backs up if the last one is a week old. Failures are kept for the More screen. */
    fun runIfDue(c: Context, store: Store) {
        val p = prefs(c)
        val uri = p.getString("uri", null) ?: return
        if (System.currentTimeMillis() - p.getLong("last", 0L) < WEEK) return
        val pw = p.getString("pw", null)?.let(::open) ?: return
        runCatching { write(c, store, Uri.parse(uri), pw) }.onFailure { p.edit().putString("error", it.message ?: it.javaClass.simpleName).apply() }
        pw.fill(' ')
    }

    /** "Back up now" from the status sheet: same as the weekly run, ignoring the week. */
    fun runNow(c: Context, store: Store) {
        prefs(c).edit().putLong("last", 0L).apply()
        runIfDue(c, store)
    }

    private fun write(c: Context, store: Store, uri: Uri, password: CharArray) {
        // "wt": truncate, so the file always holds exactly the latest backup.
        c.contentResolver.openOutputStream(uri, "wt")!!.use { Backup.write(store, password, it) }
        prefs(c).edit().putLong("last", System.currentTimeMillis()).remove("error").apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
    }

    private fun seal(pw: CharArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(String(pw).toByteArray())
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun open(sealed: String): CharArray? = runCatching {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12)) }
        String(cipher.doFinal(all, 12, all.size - 12)).toCharArray()
    }.getOrNull()
}
