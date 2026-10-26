package com.girish.expensetracker.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread

/** Resets "Today" just after midnight (and rolls the month on salary day) without any background polling. */
class MidnightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "midnight") {
            try {
                Notifier.refresh(context)
                schedule(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun schedule(context: Context) {
            val zone = ZoneId.systemDefault()
            // One minute past midnight; inexact is fine (a few minutes late at most) and needs no exact-alarm permission.
            val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusMinutes(1).toInstant().toEpochMilli()
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(context, MidnightReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
        }
    }
}
