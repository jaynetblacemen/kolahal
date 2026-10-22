package com.mtwabpin.kolahal.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Themes people make in Settings (Look & feel → Your themes): one file each,
 * `files/themes/<name>.json`, with `"role": "#RRGGBB"` for every role in [ROLES]. Loaded once at
 * start ([load]); `theme.name` takes their names next to the built-in ones.
 */
object CustomThemes {
    /** Colour roles, in the builder's order. */
    val ROLES = listOf("background", "surface", "text", "muted", "accent", "link", "info", "script", "error", "personal", "work", "private")

    /** A theme's name: lowercase letters, digits and hyphens, like the built-in ones. */
    val NAME = Regex("[a-z0-9][a-z0-9-]{0,23}")

    private const val DIR = "themes"
    private val ROLE_COLOUR = Regex("\"([a-z]+)\"\\s*:\\s*\"#([0-9a-fA-F]{6})\"")

    private val _themes = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    /** Every custom theme: name to colours (ARGB, by role). */
    val themes: StateFlow<Map<String, Map<String, Int>>> = _themes.asStateFlow()

    val names: List<String> get() = _themes.value.keys.sorted()

    fun colors(name: String): Map<String, Int>? = _themes.value[name]

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** Reads the saved themes; at start, before the theme is applied. */
    fun load(context: Context) {
        _themes.value = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            val name = f.name.removeSuffix(".json")
            val colors = runCatching { f.readText() }.getOrNull()?.let(::decode)
            if (NAME.matches(name) && colors != null) name to colors else null
        }.toMap()
    }

    fun save(context: Context, name: String, colors: Map<String, Int>) {
        require(NAME.matches(name) && ROLES.all { it in colors }) { "not a theme: $name" }
        File(dir(context).apply { mkdirs() }, "$name.json").writeText(encode(colors))
        _themes.value = _themes.value + (name to colors)
    }

    fun delete(context: Context, name: String) {
        File(dir(context), "$name.json").delete()
        _themes.value = _themes.value - name
    }

    /** `{ "background": "#0B0D0B", … }`, one role per line. */
    fun encode(colors: Map<String, Int>): String =
        ROLES.joinToString(",\n", "{\n", "\n}\n") { "  \"$it\": \"${hex(colors.getValue(it))}\"" }

    /** The colours in [text] ([encode]'s form); null unless every role is there. */
    fun decode(text: String): Map<String, Int>? {
        val found = ROLE_COLOUR.findAll(text).associate { it.groupValues[1] to (0xFF000000 or it.groupValues[2].toLong(16)).toInt() }
        return found.filterKeys { it in ROLES }.takeIf { it.size == ROLES.size }
    }

    /** "#0B0D0B" (no alpha: themes are opaque). */
    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** An opaque colour from "#0B0D0B" or "0b0d0b"; null for anything else. */
    fun parseHex(text: String): Int? = text.trim().removePrefix("#").takeIf { s -> s.length == 6 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
        ?.let { (0xFF000000 or it.toLong(16)).toInt() }
}
