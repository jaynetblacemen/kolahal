package com.mtwabpin.kolahal.script

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import com.mtwabpin.kolahal.data.KvStore
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.ScriptRepository
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
        val name = script?.name ?: inputData.getString(KEY_NAME) ?: lang.ext
        val repl = script == null
        val args = inputData.getNullableStringArray(KEY_ARGS)?.filterNotNull().orEmpty()
        val event = inputData.getString(KEY_EVENT)?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }

        val host = HostApi(
            ctx, name, script?.id,
            notifyEnabled = script?.notify ?: true,
            interactive = repl || inputData.getBoolean(KEY_INTERACTIVE, false),
            uses = script?.let { ScriptMeta.uses(it.code) },
        )
        val engine = ScriptEngine.forLang(lang)
        val started = System.currentTimeMillis()
        val errorCtx = LogContext(
            if (script != null) mapOf(LogContext.SCRIPT to script.name) else emptyMap()
        )
        val status = try {
            val result = withTimeout(TIMEOUT_MS + 2_000) {
                engine.run(code, name, host, TIMEOUT_MS, repl, args, event)
            }
            host.flush()
            if (result != null) {
                logs.add(LogKind.OUTPUT, result, name, LogContext.of(LogContext.COPY to result) + errorCtx.values)
            } else if (repl && host.effects == 0) {
                logs.add(LogKind.OUTPUT, "nil", name)
            }
            host.commit()
            "ok in ${System.currentTimeMillis() - started}ms"
        } catch (e: CancellationException) {
            host.flush()
            // Only our own timeout is a script failure; WorkManager stopping the worker must propagate.
            if (e !is TimeoutCancellationException) throw e
            logs.add(LogKind.ERROR, "$name: timed out after ${TIMEOUT_MS / 1000}s", name, errorCtx)
            "timeout"
        } catch (e: Throwable) {
            host.flush()
            val msg = e.message?.lines()?.firstOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
            val line = if (repl) null else errorLine(e, lang, name)
            val extra = buildMap {
                put(LogContext.COPY, e.message ?: msg)
                line?.let { put(LogContext.LINE, it.toString()) }
            }
            logs.add(LogKind.ERROR, "$name: $msg" + (line?.let { " (tap to edit line $it)" } ?: ""), name, errorCtx + extra)
            "error: $msg" + (line?.let { " (line $it)" } ?: "")
        }
        script?.let {
            scripts.recordRun(it.id, status)
            // The run's lines went out before its result was stored; without this, home could show the previous run.
            logs.touch()
        }
        if (host.notifyBlocked) reportBlocked(ctx, logs, name, "notify-blocked", "notifications are blocked", "notifications")
        if (host.lanBlocked) reportBlocked(ctx, logs, name, "lan-blocked", "Kolahal may not reach your local network yet", "local-network")
        logs.trim()
        return Result.success()
    }

    /** Source line of a script error: Lua messages carry `name:LINE:`, QuickJS exceptions a line number. */
    private fun errorLine(e: Throwable, lang: ScriptLang, name: String): Int? = when (lang) {
        ScriptLang.LUA -> Regex("(?:^|\\s)${Regex.escape(name)}:(\\d+):").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        // Saved JS scripts are wrapped in one extra line (the async function header).
        ScriptLang.JS -> (e as? com.dokar.quickjs.QuickJsException)?.lineNumber?.minus(1)?.takeIf { it > 0 }
    }

    /** One "tap to allow" line per day ([key] tells kinds apart) instead of one per blocked call. */
    private fun reportBlocked(context: Context, logs: LogRepository, name: String, key: String, what: String, fix: String) {
        val store = KvStore(context, "worker")
        val today = java.time.LocalDate.now().toString()
        if (store.get(key) == today) return
        store.set(key, today)
        logs.add(LogKind.ERROR, "$name: $what — tap to allow", name, LogContext.of(LogContext.RUN to fix))
    }

    companion object {
        const val KEY_SCRIPT_ID = "script_id"
        const val KEY_LANG = "lang"
        const val KEY_CODE = "code"
        const val KEY_SCHEDULED = "scheduled"
        const val KEY_ARGS = "args"
        const val KEY_NAME = "name"
        const val KEY_INTERACTIVE = "interactive"
        /** JSON object for the script's `event` global (event-triggered runs only). */
        const val KEY_EVENT = "event"
        const val TIMEOUT_MS = 60_000L
    }
}
