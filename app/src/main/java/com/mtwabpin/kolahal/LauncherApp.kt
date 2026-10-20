package com.mtwabpin.kolahal

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.notify.Notifier
import com.mtwabpin.kolahal.script.ScriptScheduler
import com.mtwabpin.kolahal.unlock.UnlockCounter
import com.mtwabpin.kolahal.status.DeviceStatus

class LauncherApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // Scheduling happens in the UI process; ScriptWorker itself runs in :script.
            .setDefaultProcessName(packageName)
            .build()

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        // The :script process only needs the database and notifications.
        if (getProcessName() != packageName) return

        AppRepository.get(this).start()
        UnlockCounter.get(this).start()
        // Runtime receivers + network callback for `@on` scripts (charger/headset need these on Android 8+).
        com.mtwabpin.kolahal.script.TriggerDispatcher.get(this).start()
        DeviceStatus.get(this).start()
        com.mtwabpin.kolahal.contacts.ContactsRepository.get(this).refresh()
        com.mtwabpin.kolahal.apps.PackageEvents.get(this).start()
        com.mtwabpin.kolahal.calls.CallEvents.get(this).start()

        // Re-anchor daily script times (periodic work drifts), restore schedules after updates, trim old log.
        Thread {
            runCatching { ScriptScheduler.syncAll(this) }
            runCatching { com.mtwabpin.kolahal.feeds.FeedScheduler.sync(this) }
            runCatching { com.mtwabpin.kolahal.productivity.ReminderScheduler.rescheduleAll(this) }
            runCatching { LogRepository.get(this).trimIfDue(Config.get(this)[Keys.LOG_KEEP_DAYS]) }
            runCatching { com.mtwabpin.kolahal.data.KvStore.repairOversized(this) }
        }.start()

        val logs = LogRepository.get(this)
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = logs.onExternalChange()
        }, IntentFilter(LogRepository.ACTION_LOGS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
