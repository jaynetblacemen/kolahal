package io.github.rscipher001.launcher.script

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.data.LogRepository
import io.github.rscipher001.launcher.data.ScriptLang
import io.github.rscipher001.launcher.data.ScriptRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Runs one script (by id) or an inline REPL snippet. Executes inside the `:script` process via
 * WorkManager's RemoteWorkerService, isolating native engine crashes from the launcher UI.
 */
class ScriptWorker(context: Context, params: WorkerParameters) : RemoteCoroutineWorker(context, params) {

    override suspend fun doRemoteWork(): Result {
        val ctx = applicationContext
        val logs = LogRepository.get(ctx)
        val scripts = ScriptRepository.get(ctx)

        val scriptId = inputData.getLong(KEY_SCRIPT_ID, -1L)
        val script = if (scriptId >= 0) scripts.byId(scriptId) ?: return Result.success() else null
        if (script != null && !script.enabled && inputData.getBoolean(KEY_SCHEDULED, false)) {
            return Result.success()
        }
        val lang = script?.lang ?: ScriptLang.of(inputData.getString(KEY_LANG).orEmpty())
            ?: return Result.failure()
        val code = script?.code ?: inputData.getString(KEY_CODE) ?: return Result.failure()
        val name = script?.name ?: lang.ext
        val repl = script == null
        val args = inputData.getStringArray(KEY_ARGS)?.toList().orEmpty()

        val host = HostApi(ctx, name, script?.id, notifyEnabled = script?.notify ?: true)
        val engine = ScriptEngine.forLang(lang)
        val started = System.currentTimeMillis()
        val errorCtx = LogContext(
            if (script != null) mapOf(LogContext.SCRIPT to script.name) else emptyMap()
        )
        val status = try {
            val result = withTimeout(TIMEOUT_MS + 2_000) {
                engine.run(code, name, host, TIMEOUT_MS, repl, args)
            }
            if (result != null) {
                logs.add(LogKind.OUTPUT, result, name, LogContext.of(LogContext.COPY to result) + errorCtx.values)
            } else if (repl && host.linesLogged == 0) {
                logs.add(LogKind.OUTPUT, "nil", name)
            }
            host.commit()
            "ok in ${System.currentTimeMillis() - started}ms"
        } catch (e: CancellationException) {
            // Only our own timeout is a script failure; WorkManager stopping the worker must propagate.
            if (e !is TimeoutCancellationException) throw e
            logs.add(LogKind.ERROR, "$name: timed out after ${TIMEOUT_MS / 1000}s", name, errorCtx)
            "timeout"
        } catch (e: Throwable) {
            val msg = e.message?.lines()?.firstOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
            logs.add(LogKind.ERROR, "$name: $msg", name, errorCtx + mapOf(LogContext.COPY to (e.message ?: msg)))
            "error: $msg"
        }
        script?.let { scripts.recordRun(it.id, status) }
        logs.trim()
        return Result.success()
    }

    companion object {
        const val KEY_SCRIPT_ID = "script_id"
        const val KEY_LANG = "lang"
        const val KEY_CODE = "code"
        const val KEY_SCHEDULED = "scheduled"
        const val KEY_ARGS = "args"
        const val TIMEOUT_MS = 60_000L
    }
}
