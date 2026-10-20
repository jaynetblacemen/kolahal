package com.mtwabpin.kolahal.unlock

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId

/**
 * Counts device unlocks.
 *
 * Primary source: UsageStats KEYGUARD_HIDDEN events (accurate, includes unlocks while the
 * launcher process was dead; needs the user to grant Usage Access).
 * Fallback: an ACTION_USER_PRESENT receiver registered while the launcher process is alive.
 */
class UnlockCounter private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("unlocks", Context.MODE_PRIVATE)
    private val _today = MutableStateFlow(0)
    val today: StateFlow<Int> = _today.asStateFlow()

    private var registered = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun start() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val key = dayKey(LocalDate.now())
                prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
                com.mtwabpin.kolahal.script.TriggerDispatcher.get(context).fire("unlock")
                refresh()
            }
        }, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    /** Recounts off the main thread; a day of UsageStats events can be thousands of entries. */
    fun refresh() {
        scope.launch { _today.value = countFor(LocalDate.now()) }
    }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION") // deprecated in API 37, replacement not available on older releases
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val source: String get() = if (hasUsageAccess()) "usage-stats" else "receiver"

    fun countFor(day: LocalDate): Int {
        if (hasUsageAccess()) {
            val zone = ZoneId.systemDefault()
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
            if (end > start) {
                val usm = context.getSystemService(UsageStatsManager::class.java)
                val events = usm.queryEvents(start, end)
                val event = UsageEvents.Event()
                var count = 0
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) count++
                }
                return count
            }
        }
        return prefs.getInt(dayKey(day), 0)
    }

    /** Counts for the last [days] days, oldest first. */
    fun history(days: Int): List<Pair<LocalDate, Int>> {
        val today = LocalDate.now()
        return (days - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { it to countFor(it) }
    }

    private fun dayKey(day: LocalDate) = "d:$day"

    companion object {
        @Volatile
        private var instance: UnlockCounter? = null

        fun get(context: Context): UnlockCounter = instance ?: synchronized(this) {
            instance ?: UnlockCounter(context.applicationContext).also { instance = it }
        }
    }
}
