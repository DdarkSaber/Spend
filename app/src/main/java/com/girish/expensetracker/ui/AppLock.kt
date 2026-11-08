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

    fun onStop() {
        hiddenAt = SystemClock.elapsedRealtime()
        // A prompt left open in the background is cancelled by the system; forget it so the next start asks again.
        showing?.cancel()
        showing = null
    }

    fun onStart() {
        if (enabled.value && hiddenAt > 0 && SystemClock.elapsedRealtime() - hiddenAt > GRACE_MS) locked.value = true
        if (locked.value) unlock()
        hideFromRecents(enabled.value)
    }

    fun unlock() {
        // With no screen lock there is nothing to unlock with: open, and turn the lock off.
        if (!activity.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            locked.value = false
            if (enabled.value) save(false)
            return
        }
        prompt("Unlock Spend") { locked.value = false }
    }

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

    /** Never crash or strand the owner: if the system prompt can't open, the app opens unlocked. */
    private fun prompt(title: String, onSuccess: () -> Unit) {
        runCatching { show(title, onSuccess) }.onFailure {
            locked.value = false
            Toast.makeText(activity, "Couldn't show the unlock prompt", Toast.LENGTH_SHORT).show()
        }
    }

    // The prompt on screen now, if any: a second one (app start + an Unlock tap) would cancel the first silently.
    private var showing: CancellationSignal? = null

    private fun show(title: String, onSuccess: () -> Unit) {
        if (showing != null) return
        val signal = CancellationSignal()
        showing = signal
        BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
            .authenticate(signal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    showing = null
                    onSuccess()
                }

                // Closed without success (cancelled, another window took over, too many tries): say why, so the
                // Unlock button never looks dead; tapping it again opens a fresh prompt.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    showing = null
                    // The phone can't ask at all (screen lock removed, sensor unavailable): never strand the owner.
                    if (errorCode in cannotAsk) {
                        locked.value = false
                        save(false)
                        Toast.makeText(activity, "App lock turned off: this phone has no screen lock to unlock with", Toast.LENGTH_LONG).show()
                        return
                    }
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                        Toast.makeText(activity, errString ?: "Couldn't unlock", Toast.LENGTH_SHORT).show()
                    }
                }
            })
    }

    private companion object {
        val cannotAsk = setOf(
            BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
            BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
            BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        )
        const val KEY = "lock"
        const val GRACE_MS = 30_000L
    }
}
