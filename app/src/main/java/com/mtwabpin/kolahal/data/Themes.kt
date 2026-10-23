package com.mtwabpin.kolahal.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Themes people make in Settings (Look & feel → Your themes): one file each,
 * `files/themes/<name>.json`, with `"role": "#RRGGBB"` for every role in [ROLES] (the dark
 * version) and `"light-role"` for each of the light version's ([LIGHT]). A theme without a light
 * version (made before themes had one) looks the same in both modes. Loaded once at start
 * ([load]); `theme.name` takes their names next to the built-in ones.
 */
object CustomThemes {
    /** Colour roles, in the builder's order. */
    val ROLES = listOf("background", "surface", "text", "muted", "accent", "link", "info", "script", "error", "personal", "work", "private")

    /** A theme's name: lowercase letters, digits and hyphens, like the built-in ones. */
    val NAME = Regex("[a-z0-9][a-z0-9-]{0,23}")

    /** Prefix of the light version's roles: `light-background`. */
    const val LIGHT = "light-"

    private const val DIR = "themes"
    private val ROLE_COLOUR = Regex("\"([a-z-]+)\"\\s*:\\s*\"#([0-9a-fA-F]{6})\"")

    /** [colors]' light version by role, or null when it has none (or only part of one). */
    fun light(colors: Map<String, Int>): Map<String, Int>? =
        ROLES.associateWith { colors[LIGHT + it] ?: return null }

    /** [colors] with a light version only if it is whole: half of one would be read as none. */
    private fun whole(colors: Map<String, Int>): Map<String, Int> =
        colors.filterKeys { it in ROLES } + (light(colors)?.mapKeys { LIGHT + it.key } ?: emptyMap())

    /** A key a theme's file may hold: a role of either version. */
    fun isKey(key: String) = key in ROLES || (key.startsWith(LIGHT) && key.removePrefix(LIGHT) in ROLES)

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
        val kept = whole(colors)
        File(dir(context).apply { mkdirs() }, "$name.json").writeText(encode(kept))
        _themes.value = _themes.value + (name to kept)
    }

    fun delete(context: Context, name: String) {
        File(dir(context), "$name.json").delete()
        _themes.value = _themes.value - name
    }

    /** `{ "background": "#0B0D0B", …, "light-background": "#F3F7F1", … }`, one role per line. */
    fun encode(colors: Map<String, Int>): String {
        val keys = ROLES + (light(colors)?.let { ROLES.map { LIGHT + it } } ?: emptyList())
        return keys.joinToString(",\n", "{\n", "\n}\n") { "  \"$it\": \"${hex(colors.getValue(it))}\"" }
    }

    /** The colours in [text] ([encode]'s form); null unless every role of the dark version is there. */
    fun decode(text: String): Map<String, Int>? {
        val found = ROLE_COLOUR.findAll(text).associate { it.groupValues[1] to (0xFF000000 or it.groupValues[2].toLong(16)).toInt() }
        return whole(found).takeIf { colors -> ROLES.all { it in colors } }
    }

    /** "#0B0D0B" (no alpha: themes are opaque). */
    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** An opaque colour from "#0B0D0B" or "0b0d0b"; null for anything else. */
    fun parseHex(text: String): Int? = text.trim().removePrefix("#").takeIf { s -> s.length == 6 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
        ?.let { (0xFF000000 or it.toLong(16)).toInt() }
}
