package com.mtwabpin.kolahal.productivity

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.notify.Notifier

/** Schedules `remind` alarms with AlarmManager (exact when allowed, otherwise inexact while idle). */
object ReminderScheduler {
    const val EXTRA_ID = "reminder_id"

    /** Exact alarms need SCHEDULE_EXACT_ALARM on Android 12+, which the user may have turned off. */
    fun canBeExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pending(context: Context, id: Long, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or flags,
        )

    fun schedule(context: Context, r: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, r.id) ?: return
        // A time that passed while the phone was off fires right away.
        val at = r.at.coerceAtLeast(System.currentTimeMillis() + 1_000)
        // Android refuses an app's 500th alarm: a failure here must not stop the others.
        runCatching {
            if (canBeExact(context)) {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                    .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    fun cancel(context: Context, id: Long) {
        val pi = pending(context, id, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    /** App start and boot: alarms don't survive a reboot (or a force stop). */
    /** Alarms for the earliest [MAX_ALARMS] reminders; each one fired makes room for the next. */
    fun rescheduleAll(context: Context) {
        ReminderStore.get(context).all().sortedBy { it.at }.take(MAX_ALARMS).forEach { schedule(context, it) }
    }

    /** Well under Android's limit of 500 alarms per app. */
    const val MAX_ALARMS = 400
}

/** Fires one reminder: notification plus a log line, then the reminder is gone. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1)
        if (id < 0) return
        val result = goAsync()
        Thread {
            try {
                val store = ReminderStore.get(context)
                val r = store.byId(id) ?: return@Thread // cancelled meanwhile
                store.remove(id)
                // More reminders than alarms: the next one waiting gets its alarm now.
                if (store.all().size >= ReminderScheduler.MAX_ALARMS) ReminderScheduler.rescheduleAll(context)
                Notifier.postReminder(context, id, r.text)
                LogRepository.get(context).add(
                    LogKind.SCRIPT, "reminder: ${r.text}", "remind",
                    // Tap puts a 10 minute snooze in the prompt.
                    LogContext.of(LogContext.COMMAND to "remind 10m ${r.text}", LogContext.COPY to r.text),
                )
            } finally {
                result.finish()
            }
        }.start()
    }
}

/** Puts reminders back after a reboot or an app update. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val result = goAsync()
                Thread {
                    try {
                        runCatching { ReminderScheduler.rescheduleAll(context) }
                    } finally {
                        result.finish()
                    }
                }.start()
            }
        }
    }
}
