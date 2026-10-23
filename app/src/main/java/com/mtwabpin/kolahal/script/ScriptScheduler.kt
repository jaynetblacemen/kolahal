package com.mtwabpin.kolahal.script

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkerService
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.ScriptRepository
import java.time.Duration
import java.time.ZonedDateTime
import androidx.work.WorkInfo
import kotlin.math.abs
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object ScriptScheduler {
    /** WorkManager's floor for periodic work. */
    const val MIN_INTERVAL_MIN = 15
    private const val MAX_INLINE_CODE = 8_000
    private const val MAX_DRIFT_MS = 10 * 60_000L
    private const val DAY_MS = 24 * 3_600_000L
    /** A daily job this late after its time is still that day's run. */
    private const val LATE_RUN_MS = 6 * 3_600_000L
    private const val RECHECK_MS = 6 * 3_600_000L
    private const val KEY_SYNCED_AT = "synced_at"

    private fun uniqueName(id: Long) = "script-$id"

    /** Schedules or cancels the interval and daily runs for [script] according to its settings. */
    fun sync(context: Context, script: Script) {
        val wm = WorkManager.getInstance(context)
        syncDaily(context, wm, script, reanchor = false)
        syncInterval(context, wm, script)
    }

    private fun syncInterval(context: Context, wm: WorkManager, script: Script) {
        if (!script.enabled || script.intervalMin <= 0) {
            wm.cancelUniqueWork(uniqueName(script.id))
            return
        }
        val minutes = script.intervalMin.coerceAtLeast(MIN_INTERVAL_MIN).toLong()
        val request = PeriodicWorkRequestBuilder<ScriptWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(remoteData(context) {
                putLong(ScriptWorker.KEY_SCRIPT_ID, script.id)
                putBoolean(ScriptWorker.KEY_SCHEDULED, true)
            })
            .addTag("script")
            .build()
        wm.enqueueUniquePeriodicWork(uniqueName(script.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * One 24h periodic job per daily time, first run delayed to the next occurrence.
     *
     * Jobs are only replaced when the set of times changes, or (with [reanchor], on launcher start)
     * when a waiting job has drifted from its clock time. A job that is running, or overdue and
     * waiting for network/Doze, is never touched, so syncing can't swallow today's run.
     */
    private fun syncDaily(context: Context, wm: WorkManager, script: Script, reanchor: Boolean) {
        val prefs = context.getSharedPreferences("daily_schedule", Context.MODE_PRIVATE)
        val key = "script:${script.id}"
        val wanted = if (script.enabled) script.atTimes.mapNotNull(::normalizeTime).distinct().sorted() else emptyList()
        val scheduled = prefs.getString(key, null).orEmpty()
        if (wanted.joinToString(",") != scheduled) {
            wm.cancelAllWorkByTag(dailyTag(script.id))
            wanted.forEach { enqueueDaily(context, wm, script.id, it) }
            prefs.edit { putString(key, wanted.joinToString(",")) }
            return
        }
        if (!reanchor) return
        val now = System.currentTimeMillis()
        for (time in wanted) {
            val info = runCatching { wm.getWorkInfosForUniqueWork(dailyName(script.id, time)).get() }
                .getOrNull()?.firstOrNull()
            val next = now + millisUntil(time)
            val due = info?.nextScheduleTimeMillis ?: 0L
            val drifted = info != null && info.state == WorkInfo.State.ENQUEUED &&
                due > now && // overdue jobs are about to run: leave them
                abs(due - next) > MAX_DRIFT_MS &&
                // Today's run, late (it waited for the network or Doze): moved to tomorrow, today
                // would have none. It runs; a later check brings the next one back to its time.
                due - (next - DAY_MS) !in 0..LATE_RUN_MS
            if (info == null || info.state.isFinished || drifted) enqueueDaily(context, wm, script.id, time)
        }
    }

    private fun enqueueDaily(context: Context, wm: WorkManager, scriptId: Long, time: String) {
        val request = PeriodicWorkRequestBuilder<ScriptWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(millisUntil(time), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(remoteData(context) {
                putLong(ScriptWorker.KEY_SCRIPT_ID, scriptId)
                putBoolean(ScriptWorker.KEY_SCHEDULED, true)
            })
            .addTag("script")
            .addTag(dailyTag(scriptId))
            .build()
        wm.enqueueUniquePeriodicWork(dailyName(scriptId, time), ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    private fun dailyTag(id: Long) = "script-$id-daily"
    private fun dailyName(id: Long, time: String) = "${uniqueName(id)}-at-$time"

    /** "8:00" / "08:00" -> "08:00"; null for anything that isn't a valid clock time. */
    fun normalizeTime(raw: String): String? = runCatching {
        val (h, m) = raw.trim().split(':').map { it.toInt() }
        LocalTime.of(h, m).toString()
    }.getOrNull()

    /** Milliseconds from now until the next local [hhmm] ("HH:mm"), DST-aware. */
    fun millisUntil(hhmm: String, now: ZonedDateTime = ZonedDateTime.now()): Long {
        val time = LocalTime.parse(normalizeTime(hhmm) ?: hhmm)
        var next = now.toLocalDate().atTime(time).atZone(now.zone)
        if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
        return Duration.between(now, next).toMillis()
    }

    /**
     * [syncAll] if the last was over [RECHECK_MS] ago: a launcher that runs for days would
     * otherwise leave daily times drifting, and an hour off after a daylight-saving change.
     */
    fun syncAllIfDue(context: Context) {
        val last = context.getSharedPreferences("daily_schedule", Context.MODE_PRIVATE).getLong(KEY_SYNCED_AT, 0L)
        if (System.currentTimeMillis() - last !in 0 until RECHECK_MS) syncAll(context)
    }

    /** Re-syncs every script; one bad script can't stop the rest. */
    fun syncAll(context: Context) {
        context.getSharedPreferences("daily_schedule", Context.MODE_PRIVATE).edit { putLong(KEY_SYNCED_AT, System.currentTimeMillis()) }
        val wm = WorkManager.getInstance(context)
        ScriptRepository.get(context).all().forEach { s ->
            runCatching {
                syncDaily(context, wm, s, reanchor = true)
                syncInterval(context, wm, s)
            }
        }
    }

    fun cancel(context: Context, scriptId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(scriptId))
        WorkManager.getInstance(context).cancelAllWorkByTag(dailyTag(scriptId))
        context.getSharedPreferences("daily_schedule", Context.MODE_PRIVATE).edit { remove("script:$scriptId") }
    }

    fun runNow(context: Context, script: Script, args: List<String> = emptyList(), interactive: Boolean = true) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ScriptWorker>()
                .setInputData(remoteData(context) {
                    putLong(ScriptWorker.KEY_SCRIPT_ID, script.id)
                    putBoolean(ScriptWorker.KEY_INTERACTIVE, interactive)
                    if (args.isNotEmpty()) putStringArray(ScriptWorker.KEY_ARGS, args.toTypedArray())
                    fromCommand(context)
                })
                .build()
        )
    }

    /**
     * One non-interactive run triggered by a device event (`@on`). [payload] becomes the script's
     * `event` global; it is skipped in the worker if the script was disabled meanwhile.
     */
    fun runForEvent(context: Context, scriptId: Long, payload: Map<String, Any?>) {
        // Too big for WorkManager's data (a long notification in a script with many bytes per
        // character): its texts are cut shorter until it fits, rather than the run dropped.
        var trimmed = payload
        var json = Json.stringify(trimmed)
        var cap = 2000
        while (json.toByteArray().size > MAX_INLINE_CODE && cap >= 125) {
            cap /= 2
            trimmed = trimmed.mapValues { (_, v) -> if (v is String && v.length > cap) v.take(cap) + "…" else v }
            json = Json.stringify(trimmed)
        }
        if (json.toByteArray().size > MAX_INLINE_CODE) return
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ScriptWorker>()
                .setInputData(remoteData(context) {
                    putLong(ScriptWorker.KEY_SCRIPT_ID, scriptId)
                    putBoolean(ScriptWorker.KEY_SCHEDULED, true)
                    putString(ScriptWorker.KEY_EVENT, json)
                })
                .addTag("script")
                .build()
        )
    }

    /** Evaluates a one-liner typed at the prompt (`lua ...` / `js ...`). */
    fun eval(context: Context, lang: ScriptLang, code: String, name: String? = null): Boolean {
        // WorkManager Data is capped at 10 KB of serialized bytes, not characters.
        if (code.toByteArray().size > MAX_INLINE_CODE) return false
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ScriptWorker>()
                .setInputData(remoteData(context) {
                    putString(ScriptWorker.KEY_LANG, lang.ext)
                    putString(ScriptWorker.KEY_CODE, code)
                    name?.let { putString(ScriptWorker.KEY_NAME, it) }
                    fromCommand(context)
                })
                .build()
        )
        return true
    }

    /** Set on the thread running a command a script sent with launcher.run ([sentByScript]). */
    private val byScript = ThreadLocal<Boolean>()

    /**
     * Runs [block], a command a script sent with launcher.run: the runs it starts are marked
     * ([ScriptWorker.KEY_BY_SCRIPT]) and may not send commands themselves.
     */
    fun <T> sentByScript(block: () -> T): T {
        val previous = byScript.get()
        byScript.set(true)
        try {
            return block()
        } finally {
            byScript.set(previous)
        }
    }

    /** Whether this thread is running a command a script sent ([sentByScript]). */
    internal val runningSentCommand: Boolean get() = byScript.get() == true

    /** A run a typed command starts: its lines are that command's output ([LogContext.FROM]). */
    private fun Data.Builder.fromCommand(context: Context) {
        LogRepository.get(context).currentCause?.let { putLong(ScriptWorker.KEY_FROM, it) }
    }

    private fun remoteData(context: Context, block: Data.Builder.() -> Unit): Data =
        Data.Builder()
            .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, context.packageName)
            .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
            .apply { if (runningSentCommand) putBoolean(ScriptWorker.KEY_BY_SCRIPT, true) }
            .apply(block)
            .build()
}
