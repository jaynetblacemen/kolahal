package com.mtwabpin.kolahal.script

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.JsObject
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.binding.toJsObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.mtwabpin.kolahal.data.StyledText

/**
 * QuickJS through quickjs-kt. Network helpers are `async` so scripts can `await` them
 * (and use `Promise.all` for parallel fetches). Only the bindings below are exposed —
 * QuickJS has no file, process or network access of its own.
 */
class JsEngine : ScriptEngine {

    override suspend fun run(
        code: String,
        chunkName: String,
        host: HostApi,
        timeoutMs: Long,
        repl: Boolean,
        args: List<String>,
        event: Map<*, *>?,
    ): String? {
        val js = QuickJs.create(Dispatchers.IO)
        try {
            js.memoryLimit = MEMORY_LIMIT
            js.evaluationTimeoutMillis = timeoutMs
            bindHost(js, host)
            js.evaluate<Any?>(
                "globalThis.args = ${Json.stringify(args)}; globalThis.argline = ${Json.stringify(args.joinToString(" "))};" +
                    " globalThis.event = ${Json.stringify(event)};",
                filename = "args.js",
            )
            if (repl) return js.evaluate<Any?>(code, filename = "$chunkName.js")?.let(::render)
            // Wrap so scripts may `return`; top-level await makes evaluate() wait for completion,
            // and the value comes back through a binding (evaluate does not unwrap returned promises).
            var result: Any? = null
            js.function("__kolahal_done") { args -> result = args.getOrNull(0) }
            js.evaluate<Any?>("__kolahal_done(await (async () => {\n$code\n})());", filename = "$chunkName.js")
            return result?.let(::render)
        } finally {
            js.close()
        }
    }

    private fun bindHost(js: QuickJs, host: HostApi) {
        js.function("log") { args ->
            val (title, body, ctx) = logArgs(args)
            host.log(title, body, ctx)
        }
        js.function("logs") { args ->
            // logs(["a", "b"]) or logs("a", "b"): one title line each.
            val list = args.singleOrNull() as? List<*> ?: args.toList()
            host.logs(list.map(::render))
        }
        js.function("status") { args -> host.status(args.str(0)) }
        js.function("allowed") { args -> host.allowed(args.str(0).orEmpty()) }
        js.function("notify") { args ->
            host.notify(args.str(0) ?: "", args.str(1) ?: "", args.getOrNull(2) as? Map<*, *>)
        }
        js.define("console") {
            function("log") { args -> host.log(args.joinToString(" ") { render(it) }) }
            function("error") { args -> host.logError(args.joinToString(" ") { render(it) }) }
        }
        js.define("http") {
            asyncFunction("get") { args ->
                io { toJs(host.httpGet(args.req(0), args.getOrNull(1) as? Map<*, *>)) }
            }
            asyncFunction("cached") { args ->
                io { toJs(host.httpCached(args.req(0), (args.getOrNull(1) as? Number)?.toInt() ?: 600)) }
            }
            asyncFunction("request") { args ->
                val opts = args.getOrNull(0) as? Map<*, *>
                    ?: throw IllegalArgumentException("http.request expects an options object")
                io {
                    toJs(
                        host.httpRequest(
                            url = opts["url"]?.toString() ?: throw IllegalArgumentException("url is required"),
                            method = opts["method"]?.toString() ?: "GET",
                            body = opts["body"]?.let { if (it is String) it else Json.stringify(it) },
                            contentType = opts["contentType"]?.toString(),
                            headers = opts["headers"] as? Map<*, *>,
                        )
                    )
                }
            }
        }
        js.define("clipboard") {
            function("get") { host.clipboardGet() }
            function("set") { args -> host.clipboardSet(args.req(0)) }
        }
        js.define("device") {
            function("battery") { toJs(host.battery()) }
            function("network") { host.network() }
            function("unlocks") { host.unlocksToday() }
        }
        js.define("apps") { function("list") { toJs(host.appsList()) } }
        js.define("notifications") { function("recent") { args -> toJs(host.notificationsRecent((args.getOrNull(0) as? Number)?.toInt() ?: 20)) } }
        js.define("launcher") { function("run") { args -> host.runCommand(args.req(0)) } }
        js.asyncFunction("sleep") { args ->
            kotlinx.coroutines.delay(((args.getOrNull(0) as? Number)?.toLong() ?: 0L).coerceIn(0, 5_000))
        }
        js.define("rss") {
            asyncFunction("fetch") { args -> io { toJs(host.rssFetch(args.req(0))) } }
            asyncFunction("fresh") { args -> io { toJs(host.rssFresh(args.req(0))) } }
            function("parse") { args -> toJs(host.rssParse(args.req(0))) }
        }
        js.define("store") {
            function("get") { args -> host.storeGet(args.req(0)) }
            function("set") { args -> host.storeSet(args.req(0), args.str(1)) }
        }
        js.define("md") {
            function("log") { args -> logArgs(args).let { (title, body, ctx) -> host.logMarkdown(title, body, ctx) } }
            function("escape") { args -> Markdown.escape(args.str(0).orEmpty()) }
        }
        js.define("fmt") {
            function("bold") { args -> StyledText.wrap(StyledText.Style.BOLD, args.str(0) ?: "undefined") }
            function("italic") { args -> StyledText.wrap(StyledText.Style.ITALIC, args.str(0) ?: "undefined") }
            function("underline") { args -> StyledText.wrap(StyledText.Style.UNDERLINE, args.str(0) ?: "undefined") }
            function("strike") { args -> StyledText.wrap(StyledText.Style.STRIKE, args.str(0) ?: "undefined") }
            function("code") { args -> StyledText.wrap(StyledText.Style.CODE, args.str(0) ?: "undefined") }
            function("color") { args ->
                val role = args.str(0)
                val colour = role?.let(StyledText.Colour::named)
                    ?: throw IllegalArgumentException("fmt.color: no colour '$role'; use ${StyledText.Colour.entries.joinToString { it.role }}")
                StyledText.colour(colour, args.str(1) ?: "undefined")
            }
            function("list") { args ->
                val items = args.getOrNull(0) as? List<*> ?: throw IllegalArgumentException("fmt.list expects an array of items")
                StyledText.list(items.map(::render), numbered = args.getOrNull(1) == true)
            }
            function("plain") { args -> StyledText.plain(args.str(0).orEmpty()) }
        }
        js.define("secrets") {
            function("get") { args -> host.secretGet(args.req(0)) }
        }
    }

    /** Network calls in flight for this run: `Promise.all` over hundreds of requests waits its turn. */
    private val httpSlots = Semaphore(4)

    private suspend fun <T> io(block: () -> T): T = httpSlots.withPermit { withContext(Dispatchers.IO) { block() } }

    /** `(title, body?, ctx?)` as log and md.log take them; an object second is the ctx: log(title, ctx). */
    private fun logArgs(args: Array<Any?>): Triple<String, String?, Map<*, *>?> {
        val second = args.getOrNull(1)
        return if (second is Map<*, *>) Triple(args.str(0) ?: "undefined", null, second)
        else Triple(args.str(0) ?: "undefined", second?.let(::render), args.getOrNull(2) as? Map<*, *>)
    }

    private fun Array<Any?>.str(i: Int): String? = getOrNull(i)?.let { if (it is String) it else render(it) }

    private fun Array<Any?>.req(i: Int): String =
        str(i) ?: throw IllegalArgumentException("argument ${i + 1} is required")

    /** Kotlin maps must be wrapped as [JsObject] to arrive as plain JS objects (not `Map`). */
    private fun toJs(v: Any?): Any? = when (v) {
        is JsObject -> v
        is Map<*, *> -> v.entries.associate { (k, value) -> k.toString() to toJs(value) }.toJsObject()
        is Collection<*> -> v.map(::toJs)
        else -> v
    }

    private fun render(v: Any?): String = when (v) {
        null -> "null"
        is String -> v
        is Map<*, *>, is Collection<*> -> Json.stringify(v)
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    private companion object {
        const val MEMORY_LIMIT = 64L * 1024 * 1024
    }
}
