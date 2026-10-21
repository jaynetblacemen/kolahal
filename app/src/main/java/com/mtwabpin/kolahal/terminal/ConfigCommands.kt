package com.mtwabpin.kolahal.terminal

import android.content.Context
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.ConfigKey
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.ui.TermFonts

/** `config` / `set`, `theme` and `font`. Kept out of [CommandProcessor] like the other command groups. */
class ConfigCommands(private val context: Context, private val ui: TerminalUi) {
    private val logs = LogRepository.get(context)
    private val conf = Config.get(context)

    private fun out(text: String, ctx: LogContext = LogContext.EMPTY) { logs.add(LogKind.OUTPUT, text, ctx = ctx) }
    private fun info(text: String, ctx: LogContext = LogContext.EMPTY) { logs.add(LogKind.INFO, text, ctx = ctx) }
    private fun error(text: String) { logs.add(LogKind.ERROR, text) }

    /** [raw] is the untokenized argument text, so free-text values (e.g. a key bar with quotes) survive. */
    fun config(args: List<String>, raw: String = "") {
        val sub = args.firstOrNull()
        when {
            sub == null || sub == "ls" || sub == "list" -> {
                Keys.ALL.forEach { k ->
                    val marker = if (conf.isDefault(k)) "" else " *"
                    out(
                        "${k.key} = ${conf.display(k)}$marker",
                        LogContext.of(LogContext.COMMAND to "config set ${k.key} ", LogContext.DESC to k.help),
                    )
                }
                info("Tap a line to edit it · * = changed from default · 'config reset <key|all>'")
            }
            sub == "get" -> withKey(args.getOrNull(1)) { out("${it.key} = ${conf.display(it)}") }
            sub == "reset" -> {
                if (args.getOrNull(1) == "all") {
                    Keys.ALL.forEach(conf::reset)
                    info("All settings reset")
                } else withKey(args.getOrNull(1)) {
                    conf.reset(it)
                    info("${it.key} = ${conf.display(it)} (default)")
                }
            }
            sub == "set" -> setKey(args.getOrNull(1), args.drop(2).joinToString(" "), raw)
            Keys.byName(sub) != null -> setKey(sub, args.drop(1).joinToString(" "), raw)
            else -> error("Usage: config [ls|get|set|reset] <key> [value]")
        }
    }

    /** `font` lists (tap applies), `font <name>` sets, `font import` opens the file picker. */
    fun font(args: List<String>) {
        val sub = args.firstOrNull()?.lowercase()
        val hasCustom = TermFonts.customFile(context) != null
        when {
            sub == null || sub == "ls" || sub == "list" -> {
                val current = conf[Keys.FONT]
                TermFonts.names.forEach { name ->
                    if (name == TermFonts.CUSTOM && !hasCustom) return@forEach
                    val mark = if (name == current) "*" else " "
                    out("$mark ${name.padEnd(16)} ${TermFonts.label(name)}", LogContext.of(LogContext.RUN to "font $name"))
                }
                info("Tap a font to use it · 'font import' adds your own .ttf/.otf · size: config font.size")
            }
            sub == "import" -> ui.requestFontImport()
            sub == TermFonts.CUSTOM && !hasCustom ->
                logs.add(LogKind.ERROR, "No custom font yet — tap to import a .ttf/.otf", ctx = LogContext.of(LogContext.RUN to "font import"))
            else -> setKey(Keys.FONT.key, sub, "")
        }
    }

    private inline fun withKey(name: String?, action: (ConfigKey<*>) -> Unit) {
        val key = name?.let(Keys::byName)
        if (key == null) error("Unknown setting '${name.orEmpty()}' — run 'config'") else action(key)
    }

    private fun setKey(name: String?, parsed: String, raw: String) = withKey(name) { key ->
        val value = if (key is ConfigKey.Text && raw.contains(key.key)) raw.substringAfter(key.key).trim() else parsed
        if (value.isBlank()) return error("${key.key} = ${conf.display(key)}  (${hint(key)})")
        if (conf.set(key, value)) {
            info("${key.key} = ${conf.display(key)}")
            if (key == Keys.FEEDS_WIFI) runCatching { com.mtwabpin.kolahal.feeds.FeedScheduler.sync(context) }
        }
        else error("Invalid value for ${key.key}: ${hint(key)}")
    }

    private fun hint(key: ConfigKey<*>) = when (key) {
        is ConfigKey.Bool -> "on|off"
        is ConfigKey.Num -> "${key.range.first}..${key.range.last}"
        is ConfigKey.Choice -> key.choices.joinToString("|")
        is ConfigKey.Text -> key.help
    }
}
