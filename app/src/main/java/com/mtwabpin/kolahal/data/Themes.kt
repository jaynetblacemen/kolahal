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
    val ROLES = listOf("background", "surface", "text", "muted", "accent", "link", "info", "script", "error", "good", "warn", "personal", "work", "private")

    /** Roles added later, and the role a theme made before them takes them from. */
    private val LATER = mapOf("good" to "personal", "warn" to "work")

    /** A theme's name: lowercase letters, digits and hyphens, like the built-in ones. */
    val NAME = Regex("[a-z0-9][a-z0-9-]{0,23}")

    /** Prefix of the light version's roles: `light-background`. */
    const val LIGHT = "light-"

    private const val DIR = "themes"
    private val ROLE_COLOUR = Regex("\"([a-z-]+)\"\\s*:\\s*\"#([0-9a-fA-F]{6})\"")

    /** [colors]' light version by role, or null when it has none (or only part of one). */
    fun light(colors: Map<String, Int>): Map<String, Int>? =
        ROLES.associateWith { colors[LIGHT + it] ?: return null }

    /**
     * [colors] with a light version only if it is whole: half of one would be read as none. Roles
     * added later ([LATER]) that a theme doesn't have yet come from their nearest role, in both versions.
     */
    private fun whole(colors: Map<String, Int>): Map<String, Int> {
        val filled = HashMap(colors)
        for ((role, from) in LATER) for (prefix in listOf("", LIGHT)) {
            if (prefix + role !in filled) filled[prefix + from]?.let { filled[prefix + role] = it }
        }
        return filled.filterKeys { it in ROLES } + (light(filled)?.mapKeys { LIGHT + it.key } ?: emptyMap())
    }

    /** [colors] as a whole theme ([whole]), or null when a role of its dark version is missing. */
    fun complete(colors: Map<String, Int>): Map<String, Int>? = whole(colors).takeIf { c -> ROLES.all { it in c } }

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
        val kept = complete(colors)
        require(NAME.matches(name) && kept != null) { "not a theme: $name" }
        File(dir(context).apply { mkdirs() }, "$name.json").writeText(encode(kept))
        _themes.value += (name to kept)
    }

    fun delete(context: Context, name: String) {
        File(dir(context), "$name.json").delete()
        _themes.value -= name
    }

    /**
     * `{ "background": "#0B0D0B", …, "light-background": "#F3F7F1", … }`, one role per line. A file to
     * share starts with the theme's [name], which [import] takes.
     */
    fun encode(colors: Map<String, Int>, name: String? = null): String {
        val keys = ROLES + (light(colors)?.let { ROLES.map { LIGHT + it } } ?: emptyList())
        val lines = listOfNotNull(name?.let { "  \"name\": \"$it\"" }) + keys.map { "  \"$it\": \"${hex(colors.getValue(it))}\"" }
        return lines.joinToString(",\n", "{\n", "\n}\n")
    }

    private val NAMED = Regex("\"name\"\\s*:\\s*\"([^\"]{1,64})\"")

    /**
     * A shared theme's file ([text], called [fileName]) kept as one of yours: under the name in it,
     * or else its file's, with -2, -3 … added when a built-in theme or one of yours has that name.
     * Returns the name, and false when you already have it (the same name and colours).
     */
    fun import(context: Context, text: String, fileName: String?): Pair<String, Boolean> {
        val colors = decode(text) ?: throw IllegalArgumentException("not a Kolahal theme")
        val wanted = listOfNotNull(NAMED.find(text)?.groupValues?.get(1), fileName?.substringBeforeLast('.'))
            .map(::tidy).firstOrNull { NAME.matches(it) } ?: "imported"
        if (colors(wanted) == colors) return wanted to false
        val name = freeName(wanted, Keys.BUILT_IN_THEMES.toSet() + names)
        save(context, name, colors)
        return name to true
    }

    /** [text] as a theme's name: lowercase, spaces and underscores as hyphens, anything else left out. */
    fun tidy(text: String): String =
        text.trim().lowercase().replace(Regex("[\\s_]+"), "-").replace(Regex("[^a-z0-9-]"), "").trimStart('-').take(24)

    /** [wanted], or with -2, -3 … when [taken] has it, still no longer than a name may be. */
    fun freeName(wanted: String, taken: Set<String>): String = generateSequence(1) { it + 1 }
        .map { if (it == 1) wanted else wanted.take(24 - "-$it".length).trimEnd('-') + "-$it" }
        .first { it !in taken }

    /** The colours in [text] ([encode]'s form); null unless every role of the dark version is there. */
    fun decode(text: String): Map<String, Int>? {
        val found = ROLE_COLOUR.findAll(text).associate { it.groupValues[1] to (0xFF000000 or it.groupValues[2].toLong(16)).toInt() }
        return complete(found)
    }

    /** "#0B0D0B" (no alpha: themes are opaque). */
    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** An opaque colour from "#0B0D0B" or "0b0d0b"; null for anything else. */
    fun parseHex(text: String): Int? = text.trim().removePrefix("#").takeIf { s -> s.length == 6 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
        ?.let { (0xFF000000 or it.toLong(16)).toInt() }
}
