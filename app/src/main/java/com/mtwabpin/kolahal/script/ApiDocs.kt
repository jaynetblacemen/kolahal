package com.mtwabpin.kolahal.script

import com.mtwabpin.kolahal.data.ScriptLang

/**
 * The script API as data, for editor completion, inline docs and the reference sheet.
 * Keep in sync with the bindings in [LuaEngine] / [JsEngine] (ApiDocsTest checks the names).
 */
data class ApiDoc(
    /** Full dotted name, e.g. `http.get`. */
    val name: String,
    val signature: String,
    val doc: String,
    val langs: Set<ScriptLang> = BOTH,
    val kind: Kind = Kind.FUNCTION,
) {
    enum class Kind { FUNCTION, NAMESPACE, VALUE, KEYWORD }

    /** Namespace part (`http` for `http.get`), or null for globals. */
    val qualifier: String? get() = name.substringBeforeLast('.', "").ifEmpty { null }

    /** Last segment (`get` for `http.get`). */
    val member: String get() = name.substringAfterLast('.')

    private companion object {
        val BOTH = setOf(ScriptLang.LUA, ScriptLang.JS)
    }
}

object ApiDocs {
    private val LUA = setOf(ScriptLang.LUA)
    private val JS = setOf(ScriptLang.JS)
    private val NS = ApiDoc.Kind.NAMESPACE
    private val VALUE = ApiDoc.Kind.VALUE

    val all: List<ApiDoc> = listOf(
        ApiDoc("log", "log(text, ctx?)", "Append a line to the terminal; ctx.url makes it a tappable link, ctx.image shows an image under it"),
        ApiDoc("notify", "notify(title, body, ctx?) -> bool", "Post a notification if enabled for the script; ctx.url opens on tap"),
        ApiDoc("status", "status(text)", "Pin one header line for this script; status(nil) clears it"),
        ApiDoc("allowed", "allowed(what) -> bool", "Whether you let this script use what: internet, header, notifications, apps, clipboard, lan, commands or an @on event"),
        ApiDoc("sleep", "sleep(ms)", "Pause up to 5000 ms", LUA),
        ApiDoc("sleep", "await sleep(ms)", "Pause up to 5000 ms (async)", JS),
        ApiDoc("print", "print(...)", "Log the values joined by tabs", LUA),
        ApiDoc("args", "args", "Arguments when run as a command (list of strings)", kind = VALUE),
        ApiDoc("argline", "argline", "Arguments joined by spaces", kind = VALUE),
        ApiDoc("event", "event", "Trigger payload for @on runs (event.name, app, title, text, level…); nil/null otherwise", kind = VALUE),

        ApiDoc("http", "http", "HTTP client: get, cached, request", kind = NS),
        ApiDoc("http.get", "http.get(url, headers?) -> { status, ok, body, headers }", "HTTP GET", LUA),
        ApiDoc("http.get", "await http.get(url, headers?) -> { status, ok, body, headers }", "HTTP GET (async)", JS),
        ApiDoc("http.cached", "http.cached(url, seconds?=600) -> response", "GET, reusing this script's stored response while fresh; adds `cached`", LUA),
        ApiDoc("http.cached", "await http.cached(url, seconds?=600) -> response", "GET, reusing this script's stored response while fresh; adds `cached`", JS),
        ApiDoc("http.request", "http.request{ url, method, body, contentType, headers } -> response", "Any method; a table body is sent as JSON", LUA),
        ApiDoc("http.request", "await http.request({ url, method, body, contentType, headers }) -> response", "Any method; an object body is sent as JSON", JS),

        ApiDoc("rss", "rss", "Feeds: fetch, fresh, parse", kind = NS),
        ApiDoc("rss.fetch", "rss.fetch(url) -> { title, link, items }", "Download and parse an RSS/Atom feed", LUA),
        ApiDoc("rss.fetch", "await rss.fetch(url) -> { title, link, items }", "Download and parse an RSS/Atom feed", JS),
        ApiDoc("rss.fresh", "rss.fresh(url) -> { title, link, items }", "Only items not returned before; marked seen if the run succeeds", LUA),
        ApiDoc("rss.fresh", "await rss.fresh(url) -> { title, link, items }", "Only items not returned before; marked seen if the run succeeds", JS),
        ApiDoc("rss.parse", "rss.parse(xml) -> { title, link, items = [{ title, link, id, date, summary }] }", "Parse feed XML"),

        ApiDoc("store", "store", "Persistent per-script strings: get, set", kind = NS),
        ApiDoc("store.get", "store.get(key) -> string?", "Read a stored string"),
        ApiDoc("store.set", "store.set(key, value)", "Store a string; nil/null deletes it"),

        ApiDoc("clipboard", "clipboard", "Clipboard: get, set", kind = NS),
        ApiDoc("clipboard.get", "clipboard.get() -> string?", "Clipboard text; usually nil in timers"),
        ApiDoc("clipboard.set", "clipboard.set(text)", "Copy text to the clipboard"),

        ApiDoc("device", "device", "Device info: battery, network, unlocks", kind = NS),
        ApiDoc("device.battery", "device.battery() -> { level, charging }", "Battery percent and charging state"),
        ApiDoc("device.network", "device.network() -> string", "\"wifi\", \"cell\", \"eth\", \"net\" or \"offline\""),
        ApiDoc("device.unlocks", "device.unlocks() -> number", "Today's unlock count"),

        ApiDoc("apps", "apps", "Installed apps: list", kind = NS),
        ApiDoc("apps.list", "apps.list() -> [{ label, pkg, profile }]", "Launchable apps across profiles"),

        ApiDoc("notifications", "notifications", "Recent notifications: recent", kind = NS),
        ApiDoc("notifications.recent", "notifications.recent(n?=20) -> [{ pkg, app, title, text, time }]", "Newest first, up to 50 (needs notification access)"),

        ApiDoc("launcher", "launcher", "Drive the launcher: run", kind = NS),
        ApiDoc("launcher.run", "launcher.run(command) -> bool", "Run a terminal command (needs @uses commands); only when started from the prompt"),

        ApiDoc("json", "json", "JSON: encode, decode", LUA, NS),
        ApiDoc("json.encode", "json.encode(value) -> string", "Serialize a table/value to JSON", LUA),
        ApiDoc("json.decode", "json.decode(text) -> value", "Parse JSON into tables", LUA),

        ApiDoc("console", "console", "console.log / console.error", JS, NS),
        ApiDoc("console.log", "console.log(...values)", "Log the values joined by spaces", JS),
        ApiDoc("console.error", "console.error(...values)", "Log the values as an error line", JS),
    )

    val luaKeywords = listOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
        "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
    )

    val jsKeywords = listOf(
        "async", "await", "break", "case", "catch", "const", "continue", "default", "do", "else", "false",
        "finally", "for", "function", "if", "in", "let", "new", "null", "of", "return", "switch", "this",
        "throw", "true", "try", "typeof", "undefined", "var", "while",
    )

    fun keywords(lang: ScriptLang) = if (lang == ScriptLang.LUA) luaKeywords else jsKeywords

    private val byLang: Map<ScriptLang, List<ApiDoc>> = ScriptLang.entries.associateWith { lang ->
        all.filter { lang in it.langs } +
            keywords(lang).map { ApiDoc(it, it, "keyword", setOf(lang), ApiDoc.Kind.KEYWORD) }
    }

    private val byName: Map<ScriptLang, Map<String, ApiDoc>> =
        byLang.mapValues { (_, docs) -> docs.filter { it.kind != ApiDoc.Kind.KEYWORD }.associateBy { it.name } }

    /** API entries and keywords available in [lang]. */
    fun forLang(lang: ScriptLang): List<ApiDoc> = byLang.getValue(lang)

    /** The API entry (not keyword) called [name] in [lang]. */
    fun find(name: String, lang: ScriptLang): ApiDoc? = byName.getValue(lang)[name]

    fun isNamespace(name: String, lang: ScriptLang) = find(name, lang)?.kind == ApiDoc.Kind.NAMESPACE

    /** A comment line at the top of a script ([ScriptMeta]): [args] is what follows its name, empty when nothing does. */
    data class Directive(val name: String, val args: String, val doc: String)

    /** Every `@` line, for the editor's reference: what the script is, how it runs, what it may use. */
    val directives: List<Directive> = listOf(
        Directive("command", "", "Run it by its name from the prompt"),
        Directive("desc", "<text>", "One line about it, in help, suggestions and Scripts"),
        Directive("usage", "[<args>]", "Its arguments, like a usage line in help: [<city>|--default <city>]"),
        Directive("on", "<event>, …", "Run when it happens: " + ScriptMeta.EVENTS.joinToString(" ")),
        Directive("uses", "<what>, …", "What it may use, if you allow it: " + ScriptMeta.USES.joinToString(" ")),
        Directive("every", "<15m|1h|1d>", "The timer it suggests, set when it is added"),
        Directive("at", "<HH:mm>, …", "The daily times it suggests"),
        Directive("notify", "", "It suggests letting it post notifications"),
        Directive("title", "<text>", "Its name for people, shown in Scripts"),
        Directive("author", "<name>", "Who wrote it"),
        Directive("version", "<x.y>", "Raise it when sharing an update"),
        Directive("tags", "<word>, …", "Words to find it by"),
        Directive("requires", "<x.y.z>", "The oldest Kolahal it works with"),
    )
}
