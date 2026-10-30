package com.girish.expensetracker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.girish.expensetracker.notify.SpendService

/** What the app still needs from the user before it can work in the background. */
data class Setup(val sms: Boolean, val notifications: Boolean, val batteryUnrestricted: Boolean) {
    val ready: Boolean get() = sms && notifications
}

class MainActivity : ComponentActivity() {

    // Bumped on every resume so the screens reload (new SMS may have arrived while away).
    private val tick = mutableIntStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            tick.intValue++
            startServiceIfReady()
        }

    // "system", "dark" or "light"; dark is the default look, light follows the phone when set to system.
    private val lock by lazy { AppLock(this) }

    private val theme by lazy { mutableStateOf(getSharedPreferences("ui", MODE_PRIVATE).getString("theme", "dark") ?: "dark") }

    /** A payment to open, from the quick-tag notification's "More". */
    private val openSms = mutableStateOf<Long?>(null)

    private fun takeSms(intent: Intent?) {
        intent?.getLongExtra(com.girish.expensetracker.notify.QuickTag.EXTRA_SMS, -1L)?.takeIf { it >= 0 }?.let {
            openSms.value = it
            com.girish.expensetracker.notify.QuickTag.dismiss(this, it)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSms(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Masking.load(this)
        takeSms(intent)
        setContent {
            val dark = when (theme.value) {
                "light" -> false
                "system" -> isSystemInDarkTheme()
                else -> true
            }
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            SpendTheme(dark = dark) {
                if (lock.locked.value) {
                    LockScreen(onUnlock = lock::unlock)
                    return@SpendTheme
                }
                AppRoot(
                    tick = tick.intValue,
                    setup = setup(),
                    onRequestPermissions = ::requestPermissions,
                    onOpenBatterySettings = ::openBatterySettings,
                    onOpenAppSettings = ::openAppSettings,
                    theme = theme.value,
                    onTheme = { v ->
                        theme.value = v
                        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("theme", v).apply()
                    },
                    openSms = openSms.value,
                    onSmsOpened = { openSms.value = null },
                    lockOn = lock.enabled.value,
                    onLock = lock::setEnabled,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lock.onStart()
    }

    override fun onStop() {
        super.onStop()
        lock.onStop()
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
        startServiceIfReady()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun setup() = Setup(
        sms = granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.READ_SMS),
        notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
    )

    private fun startServiceIfReady() {
        if (setup().ready) SpendService.start(this)
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    private fun openBatterySettings() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /** App info page: where "Allow restricted settings" lives for sideloaded apps. */
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
