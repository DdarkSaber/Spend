package com.girish.expensetracker.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Brings the notification back after a reboot or an app update, and re-aims the midnight reset when the clock changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> SpendService.start(context)
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                MidnightReceiver.schedule(context)
                val pending = goAsync()
                thread(name = "clock-changed") {
                    try {
                        Notifier.refresh(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
