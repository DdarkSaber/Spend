package com.girish.expensetracker.ui

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf

/**
 * Optional lock: after the app has been in the background for 30 seconds (or on a fresh start) it asks for the
 * fingerprint, face or phone PIN. Uses Android's own prompt, so no extra library and nothing is stored by the app.
 */
internal class AppLock(private val activity: ComponentActivity) {

    private val prefs = activity.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    private var hiddenAt = 0L

    val enabled = mutableStateOf(prefs.getBoolean(KEY, false))
    val locked = mutableStateOf(enabled.value)

    fun onStop() { hiddenAt = SystemClock.elapsedRealtime() }

    fun onStart() {
        if (enabled.value && hiddenAt > 0 && SystemClock.elapsedRealtime() - hiddenAt > GRACE_MS) locked.value = true
        if (locked.value) unlock()
        hideFromRecents(enabled.value)
    }

    fun unlock() = prompt("Unlock Spend") { locked.value = false }

    /** Turning it on asks once, so the owner knows their fingerprint/PIN works before being locked out. */
    fun setEnabled(on: Boolean) {
        if (!on) return save(false)
        if (!activity.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            Toast.makeText(activity, "Set a screen lock (PIN, pattern or fingerprint) in phone settings first", Toast.LENGTH_LONG).show()
            return
        }
        prompt("Turn on app lock") { save(true) }
    }

    private fun save(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        enabled.value = on
        hideFromRecents(on)
    }

    /** With the lock on, the recent-apps preview doesn't show your spending either. */
    private fun hideFromRecents(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!on)
    }

    private fun prompt(title: String, onSuccess: () -> Unit) {
        BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
            .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            })
    }

    private companion object {
        const val KEY = "lock"
        const val GRACE_MS = 30_000L
    }
}
