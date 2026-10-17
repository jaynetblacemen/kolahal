package io.github.rscipher001.launcher.productivity

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.data.LogRepository
import io.github.rscipher001.launcher.notify.Notifier

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
        if (canBeExact(context)) {
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context, id: Long) {
        val pi = pending(context, id, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    /** App start and boot: alarms don't survive a reboot (or a force stop). */
    fun rescheduleAll(context: Context) {
        ReminderStore.get(context).all().forEach { schedule(context, it) }
    }
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
