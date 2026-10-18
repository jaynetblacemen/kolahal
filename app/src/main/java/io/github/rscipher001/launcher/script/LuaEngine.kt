package io.github.rscipher001.launcher.script

import android.app.Application
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.Lua
import party.iroiro.luajava.lua54.Lua54
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/**
 * Native Lua 5.4 through LuaJava. Each run gets a fresh, sandboxed state:
 * no `java` bridge, `io`, `package`/`require`, `debug`, or process-level `os` functions.
 * An instruction-count hook enforces the timeout and coroutine cancellation.
 */
class LuaEngine : ScriptEngine {

    override suspend fun run(
        code: String,
        chunkName: String,
        host: HostApi,
        timeoutMs: Long,
        repl: Boolean,
        args: List<String>,
        event: Map<*, *>?,
    ): String? = withContext(Dispatchers.IO) {
        val ctx = coroutineContext
        val deadline = System.currentTimeMillis() + timeoutMs
        val lua = Lua54()
        // The hook cannot interrupt long-running C functions (e.g. pathological string.find patterns), and
        // withContext cannot cancel blocking JNI. As a last resort, kill the isolated :script process.
        val watchdog = thread(isDaemon = true, name = "lua-watchdog") {
            try {
                Thread.sleep(timeoutMs + HARD_KILL_GRACE_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            runCatching { host.logError("${host.scriptName}: still running after timeout, killed script process") }
            if (Application.getProcessName().endsWith(":script")) Process.killProcess(Process.myPid())
        }
        try {
            lua.openLibraries()
            lua.push(JFunction { l ->
                if (!ctx.isActive || System.currentTimeMillis() > deadline) {
                    l.push("script timed out after ${timeoutMs / 1000}s")
                    -1
                } else 0
            })
            lua.setGlobal("__kolahal_check")
            bindHost(lua, host)
            lua.push(code)
            lua.setGlobal("__kolahal_src")
            lua.push(chunkName)
            lua.setGlobal("__kolahal_name")
            lua.push(args)
            lua.setGlobal("args")
            lua.push(args.joinToString(" "))
            lua.setGlobal("argline")
            lua.pushValue(event)
            lua.setGlobal("event")
            lua.run(PRELUDE)

            lua.getGlobal("__kolahal_main")
            lua.push(repl)
            lua.pCall(1, 1)
            lua.toString(-1)
        } finally {
            // Close while the watchdog still runs: lua_close runs pending __gc finalizers, where the
            // instruction hook is off, so a looping finalizer is only stopped by the watchdog.
            try {
                lua.close()
            } finally {
                watchdog.interrupt()
            }
        }
    }

    private fun bindHost(lua: Lua, host: HostApi) {
        lua.global("log") { l ->
            host.log(l.toString(1) ?: "nil", l.mapArg(2))
            0
        }
        lua.global("status") { l ->
            host.status(if (l.isNil(1)) null else l.toString(1))
            0
        }
        lua.global("notify") { l ->
            l.push(host.notify(l.toString(1) ?: "", l.toString(2) ?: "", l.mapArg(3)))
            1
        }
        lua.table(
            "http",
            "get" to { l: Lua -> l.pushValue(host.httpGet(l.checkString(1), l.mapArg(2))); 1 },
            "cached" to { l: Lua -> l.pushValue(host.httpCached(l.checkString(1), if (l.getTop() >= 2) l.toInteger(2).toInt() else 600)); 1 },
            "request" to { l: Lua ->
                val opts = l.mapArg(1) ?: throw IllegalArgumentException("http.request expects a table")
                l.pushValue(
                    host.httpRequest(
                        url = opts["url"]?.toString() ?: throw IllegalArgumentException("url is required"),
                        method = opts["method"]?.toString() ?: "GET",
                        body = opts["body"]?.let { if (it is String) it else Json.stringify(it) },
                        contentType = opts["contentType"]?.toString(),
                        headers = opts["headers"] as? Map<*, *>,
                    )
                )
                1
            },
        )
        lua.table(
            "clipboard",
            "get" to { l: Lua -> l.pushValue(host.clipboardGet()); 1 },
            "set" to { l: Lua -> host.clipboardSet(l.checkString(1)); 0 },
        )
        lua.table(
            "device",
            "battery" to { l: Lua -> l.pushValue(host.battery()); 1 },
            "network" to { l: Lua -> l.push(host.network()); 1 },
            "unlocks" to { l: Lua -> l.push(host.unlocksToday().toLong()); 1 },
        )
        lua.table("apps", "list" to { l: Lua -> l.pushValue(host.appsList()); 1 })
        lua.table("notifications", "recent" to { l: Lua -> l.pushValue(host.notificationsRecent(if (l.isNumber(1)) l.toInteger(1).toInt() else 20)); 1 })
        lua.table("launcher", "run" to { l: Lua -> l.push(host.runCommand(l.checkString(1))); 1 })
        lua.global("sleep") { l ->
            Thread.sleep(l.toInteger(1).coerceIn(0, 5_000))
            0
        }
        lua.table(
            "rss",
            "fetch" to { l: Lua -> l.pushValue(host.rssFetch(l.checkString(1))); 1 },
            "fresh" to { l: Lua -> l.pushValue(host.rssFresh(l.checkString(1))); 1 },
            "parse" to { l: Lua -> l.pushValue(host.rssParse(l.checkString(1))); 1 },
        )
        lua.table(
            "store",
            "get" to { l: Lua -> l.pushValue(host.storeGet(l.checkString(1))); 1 },
            "set" to { l: Lua -> host.storeSet(l.checkString(1), if (l.isNil(2)) null else l.toString(2)); 0 },
        )
        lua.table(
            "json",
            "decode" to { l: Lua -> l.pushValue(Json.parse(l.checkString(1))); 1 },
            "encode" to { l: Lua -> l.push(Json.stringify(Json.normalizeLua(l.toObject(1)))); 1 },
        )
    }

    private fun Lua.checkString(index: Int): String =
        toString(index) ?: throw IllegalArgumentException("argument #$index must be a string")

    private fun Lua.mapArg(index: Int): Map<*, *>? =
        if (getTop() >= index && isTable(index)) Json.normalizeLua(toMap(index)) as? Map<*, *> else null

    private fun Lua.pushValue(value: Any?) {
        if (value == null) pushNil() else push(value, Lua.Conversion.FULL)
    }

    private fun Lua.global(name: String, fn: (Lua) -> Int) {
        push(safe(fn))
        setGlobal(name)
    }

    private fun Lua.table(name: String, vararg fns: Pair<String, (Lua) -> Int>) {
        newTable()
        for ((key, fn) in fns) {
            push(safe(fn))
            setField(-2, key)
        }
        setGlobal(name)
    }

    /** Converts Kotlin exceptions into Lua errors instead of letting them cross JNI. */
    private fun safe(fn: (Lua) -> Int) = JFunction { l ->
        try {
            fn(l)
        } catch (e: Throwable) {
            l.push(e.message ?: e.javaClass.simpleName)
            -1
        }
    }

    private companion object {
        const val HARD_KILL_GRACE_MS = 5_000L

        val PRELUDE = """
            local check = __kolahal_check
            local sethook = debug.sethook
            local gc = collectgarbage
            local HOOK_EVERY = 10000
            local MEMORY_LIMIT_KB = 64 * 1024
            local function hook()
              check()
              if gc("count") > MEMORY_LIMIT_KB then error("Memory limit exceeded (64 MB)", 0) end
            end
            sethook(hook, "", HOOK_EVERY)

            -- Timeouts and the memory cap must not be swallowed by pcall / xpcall / resume.
            local function fatal(e)
              -- Lowercased: the hook raises "Memory limit exceeded (64 MB)".
              local s = type(e) == "string" and e:lower()
              return s and (s:find("script timed out", 1, true) or s:find("memory limit exceeded", 1, true)) ~= nil
            end
            local function rethrow(res)
              if not res[1] and fatal(res[2]) then error(res[2], 0) end
              return table.unpack(res, 1, res.n)
            end
            local rawpcall, rawxpcall = pcall, xpcall
            pcall = function(f, ...) return rethrow(table.pack(rawpcall(f, ...))) end
            xpcall = function(f, handler, ...)
              return rethrow(table.pack(rawxpcall(f, function(e)
                if fatal(e) then return e end
                return handler(e)
              end, ...)))
            end

            -- Coroutines get their own hook so they cannot dodge the timeout.
            local cocreate, coresume = coroutine.create, coroutine.resume
            coroutine.create = function(f)
              local co = cocreate(f)
              sethook(co, hook, "", HOOK_EVERY)
              return co
            end
            coroutine.resume = function(co, ...) return rethrow(table.pack(coresume(co, ...))) end
            coroutine.wrap = function(f)
              local co = coroutine.create(f)
              return function(...)
                local res = table.pack(coresume(co, ...))
                if not res[1] then error(res[2], fatal(res[2]) and 0 or 2) end
                return table.unpack(res, 2, res.n)
              end
            end

            -- Sandbox
            local realload = load
            java, debug, io, package, require, dofile, loadfile = nil, nil, nil, nil, nil, nil, nil
            os = { time = os.time, date = os.date, clock = os.clock, difftime = os.difftime }
            -- Text chunks only. Passing env explicitly (even nil) would replace _ENV, so forward it only if given.
            load = function(chunk, name, _, ...)
              if select("#", ...) > 0 then return realload(chunk, name, "t", ...) end
              return realload(chunk, name, "t")
            end

            print = function(...)
              local t = table.pack(...)
              for i = 1, t.n do t[i] = tostring(t[i]) end
              log(table.concat(t, "\t"))
            end

            local src, name = __kolahal_src, __kolahal_name
            __kolahal_check, __kolahal_src, __kolahal_name = nil, nil, nil

            local function render(v)
              if type(v) == "table" then
                local ok, s = pcall(json.encode, v)
                if ok then return s end
              end
              return tostring(v)
            end

            function __kolahal_main(repl)
              local fn, err
              if repl then fn = realload("return " .. src, "=" .. name, "t") end
              if not fn then fn, err = realload(src, "=" .. name, "t") end
              if not fn then error(err, 0) end
              local res = table.pack(fn())
              if res.n == 0 or (res.n == 1 and res[1] == nil) then return nil end
              local out = {}
              for i = 1, res.n do out[i] = render(res[i]) end
              return table.concat(out, "\t")
            end
        """.trimIndent()
    }
}
