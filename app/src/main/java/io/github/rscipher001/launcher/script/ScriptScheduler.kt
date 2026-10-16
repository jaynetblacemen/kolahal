package io.github.rscipher001.launcher.script

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkerService
import io.github.rscipher001.launcher.data.Script
import io.github.rscipher001.launcher.data.ScriptLang
import io.github.rscipher001.launcher.data.ScriptRepository
import java.util.concurrent.TimeUnit

object ScriptScheduler {
    /** WorkManager's floor for periodic work. */
    const val MIN_INTERVAL_MIN = 15
    private const val MAX_INLINE_CODE = 8_000

    private fun uniqueName(id: Long) = "script-$id"

    /** Schedules or cancels the periodic run for [script] according to its settings. */
    fun sync(context: Context, script: Script) {
        val wm = WorkManager.getInstance(context)
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

    fun syncAll(context: Context) {
        ScriptRepository.get(context).all().forEach { sync(context, it) }
    }

    fun cancel(context: Context, scriptId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(scriptId))
    }

    fun runNow(context: Context, script: Script) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ScriptWorker>()
                .setInputData(remoteData(context) { putLong(ScriptWorker.KEY_SCRIPT_ID, script.id) })
                .build()
        )
    }

    /** Evaluates a one-liner typed at the prompt (`lua ...` / `js ...`). */
    fun eval(context: Context, lang: ScriptLang, code: String): Boolean {
        if (code.length > MAX_INLINE_CODE) return false
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ScriptWorker>()
                .setInputData(remoteData(context) {
                    putString(ScriptWorker.KEY_LANG, lang.ext)
                    putString(ScriptWorker.KEY_CODE, code)
                })
                .build()
        )
        return true
    }

    private fun remoteData(context: Context, block: Data.Builder.() -> Unit): Data =
        Data.Builder()
            .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, context.packageName)
            .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
            .apply(block)
            .build()
}
