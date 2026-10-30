package com.mtwabpin.kolahal.script

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.workDataOf
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptRepository
import com.mtwabpin.kolahal.notify.Notifier
import com.mtwabpin.kolahal.productivity.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * A script's daily times (`@at`), as alarms: exact when Kolahal may set exact alarms (Alarms &
 * reminders, as `remind exact` asks), otherwise at most an hour late. Each one runs the script
 * ([ScriptAlarmReceiver]) and sets the next day's.
 */
object ScriptAlarms {
    const val ACTION = "com.mtwabpin.kolahal.SCRIPT_AT"
    const val EXTRA_ID = "script_id"
    const val EXTRA_TIME = "time"

    private fun pending(context: Context, id: Long, time: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ScriptAlarmReceiver::class.java)
                // One alarm per script and time: the action tells them apart (and names it in dumpsys alarm).
                .setAction("$ACTION/$id/$time")
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TIME, time)
                .setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or flags,
        )

    /** The alarm for [time] ("HH:mm") after [from]; it replaces one already set. */
    fun set(context: Context, id: Long, time: String, from: ZonedDateTime = ZonedDateTime.now()) {
        val pi = pending(context, id, time, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val am = context.getSystemService(AlarmManager::class.java)
        val at = from.toInstant().toEpochMilli() + ScriptScheduler.millisUntil(time, from)
        // Android refuses an app's 500th alarm: a failure here must not stop the others.
        runCatching {
            if (ReminderScheduler.canBeExact(context)) {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                    .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    fun cancel(context: Context, id: Long, time: String) {
        val pi = pending(context, id, time, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }
}

/**
 * A script's daily time: sets tomorrow's, then runs it, in the foreground ([ScriptRunService]) when
 * Android allows that (after an exact alarm), otherwise in the queue as other runs go.
 */
class ScriptAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ScriptAlarms.EXTRA_ID, -1L)
        val time = intent.getStringExtra(ScriptAlarms.EXTRA_TIME) ?: return
        if (id < 0) return
        val result = goAsync()
        Thread {
            try {
                val script = ScriptRepository.get(context).byId(id) ?: return@Thread
                // Switched off or the time taken out since: no run, and no alarm tomorrow.
                if (!script.enabled || script.atTimes.mapNotNull(ScriptScheduler::normalizeTime).none { it == time }) return@Thread
                // Tomorrow's first, so nothing below can lose it; from a minute on, in case this one came early.
                ScriptAlarms.set(context, id, time, ZonedDateTime.now().plusMinutes(1))
                if (!ScriptRunService.start(context, script, time)) ScriptScheduler.runScheduled(context, script, time)
            } finally {
                result.finish()
            }
        }.start()
    }
}

/**
 * A script's run at its daily time, in the foreground for as long as it takes. Android then lets
 * it do what Kolahal may only do in front (pause or resume the work profile) and reach the network
 * while the phone dozes. It runs in the `:script` process, as the queue's runs do. Its
 * notification shows only if a run takes more than about ten seconds.
 */
class ScriptRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runs = 0
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        // At once: Android stops an app that starts a foreground service and doesn't go in front.
        goForeground(intent?.getStringExtra(EXTRA_NAME).orEmpty())
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        if (id < 0) {
            if (runs == 0) stop()
            return START_NOT_STICKY
        }
        runs++
        // The screen may be off: the run keeps the phone awake for as long as a run may take.
        val wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kolahal:script-at")
            .apply { acquire(ScriptWorker.TIMEOUT_MS + 15_000) }
        scope.launch {
            try {
                runCatching {
                    ScriptRun.run(
                        applicationContext,
                        workDataOf(
                            ScriptWorker.KEY_SCRIPT_ID to id,
                            ScriptWorker.KEY_SCHEDULED to true,
                            ScriptWorker.KEY_EVENT to intent?.getStringExtra(EXTRA_EVENT),
                        ),
                    )
                }
            } finally {
                if (wake.isHeld) wake.release()
                withContext(NonCancellable + Dispatchers.Main) { if (--runs == 0) stop() }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(name: String) {
        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(if (name.isEmpty()) "Running a script" else "Running $name")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Stops unless another start is on its way, which a plain stopSelf() would take with it: that
     * start then never goes in front, and Android stops the app for it (scripts sharing a daily time).
     */
    private fun stop() {
        stopSelf(lastStart)
    }

    // A short service gets about three minutes; a run never takes that long, but if one does it goes.
    override fun onTimeout(startId: Int) = stop()

    override fun onTimeout(startId: Int, fgsType: Int) = stop()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "script_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_EVENT = "event"
        private const val NOTIFICATION_ID = 7_301

        /**
         * Starts [script]'s run at its daily [time] in the foreground; false when Android won't let Kolahal start a
         * foreground service now (from the background without an exact alarm), or when the script
         * goes online and there's no network: the queue waits for one.
         */
        fun start(context: Context, script: Script, time: String): Boolean {
            if (ScriptMeta.goesOnline(script.code) && !online(context)) return false
            val intent = Intent(context, ScriptRunService::class.java)
                .putExtra(EXTRA_ID, script.id)
                .putExtra(EXTRA_NAME, script.name)
                .putExtra(EXTRA_EVENT, ScriptScheduler.atEvent(time))
            return runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
        }

        private fun online(context: Context): Boolean {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            return cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }
}
