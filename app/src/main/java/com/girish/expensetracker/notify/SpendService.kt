package com.girish.expensetracker.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlin.concurrent.thread

/**
 * Idle foreground service whose only job is to keep the summary notification pinned.
 * No polling, no wakelock, no network: new SMS wake the app through SmsReceiver, and one inexact alarm resets
 * "Today" after midnight.
 */
class SpendService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be immediate (Android gives a foreground service a few seconds): the cached text needs no database.
        startForeground(Notifier.ID, Notifier.cached(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        MidnightReceiver.schedule(this)
        Digest.schedule(this)
        thread(name = "notify-refresh") { Notifier.refresh(applicationContext) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val ok = runCatching { context.startForegroundService(Intent(context, SpendService::class.java)) }.isSuccess
            if (!ok) {
                // Starting from the background can be refused; keep the numbers and the midnight reset working anyway.
                MidnightReceiver.schedule(context)
                thread(name = "notify-refresh") { Notifier.refresh(context.applicationContext) }
            }
        }
    }
}
