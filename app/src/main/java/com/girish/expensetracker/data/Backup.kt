package com.girish.expensetracker.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backup of everything the app stores (bank SMS, retags, cash entries, settings), written to a
 * file the owner picks. AES-256-GCM with a key derived from the password (PBKDF2, 210k rounds): without the
 * password the file is unreadable. Nothing is sent anywhere; the app still has no internet permission.
 */
object Backup {

    class WrongPassword : Exception("Wrong password, or not a Spend backup")

    private val MAGIC = "SPENDBK1".toByteArray()
    private const val ROUNDS = 210_000

    fun write(store: Store, password: CharArray, out: OutputStream) {
        val plain = ByteArrayOutputStream().also { buf -> GZIPOutputStream(buf).use { it.write(store.dump().toString().toByteArray()) } }.toByteArray()
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        out.write(MAGIC)
        out.write(salt)
        out.write(iv)
        out.write(cipher.doFinal(plain))
        out.flush()
    }

    fun read(store: Store, password: CharArray, input: InputStream) {
        val all = input.readBytes()
        if (all.size < MAGIC.size + 28 || !all.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw WrongPassword()
        val salt = all.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = all.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        val plain = try {
            cipher.doFinal(all, MAGIC.size + 28, all.size - MAGIC.size - 28)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()
        }
        val json = GZIPInputStream(ByteArrayInputStream(plain)).use { String(it.readBytes()) }
        store.restore(org.json.JSONObject(json))
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ROUNDS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
