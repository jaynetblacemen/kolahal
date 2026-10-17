package io.github.rscipher001.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import io.github.rscipher001.launcher.apps.AppRepository
import io.github.rscipher001.launcher.data.LogRepository
import io.github.rscipher001.launcher.data.Config
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.notify.Notifier
import io.github.rscipher001.launcher.script.ScriptScheduler
import io.github.rscipher001.launcher.unlock.UnlockCounter
import io.github.rscipher001.launcher.status.DeviceStatus

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
        io.github.rscipher001.launcher.script.TriggerDispatcher.get(this).start()
        DeviceStatus.get(this).start()
        io.github.rscipher001.launcher.contacts.ContactsRepository.get(this).refresh()

        // Re-anchor daily script times (periodic work drifts), restore schedules after updates, trim old log.
        Thread {
            runCatching { ScriptScheduler.syncAll(this) }
            runCatching { io.github.rscipher001.launcher.feeds.FeedScheduler.sync(this) }
            runCatching { io.github.rscipher001.launcher.productivity.ReminderScheduler.rescheduleAll(this) }
            runCatching { LogRepository.get(this).trim(maxAgeDays = Config.get(this)[Keys.LOG_KEEP_DAYS]) }
        }.start()

        val logs = LogRepository.get(this)
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = logs.onExternalChange()
        }, IntentFilter(LogRepository.ACTION_LOGS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
