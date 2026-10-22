package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.data.CustomThemes

data class Palette(
    val bg: Color,
    val surface: Color,
    val fg: Color,
    val dim: Color,
    val prompt: Color,
    val error: Color,
    val info: Color,
    val link: Color,
    val script: Color,
    val personal: Color,
    val work: Color,
    val private: Color,
)

object Palettes {
    val green = Palette(
        bg = Color(0xFF0B0D0B), surface = Color(0xFF151915), fg = Color(0xFFD7DED3), dim = Color(0xFF7F8A7B),
        prompt = Color(0xFF8FD18A), error = Color(0xFFF0907D), info = Color(0xFF9DB8A0), link = Color(0xFF7FB8F0),
        script = Color(0xFFB5CEA8), personal = Color(0xFF8FD18A), work = Color(0xFFF2B35B), private = Color(0xFFC792EA),
    )
    val amber = Palette(
        bg = Color(0xFF0E0A05), surface = Color(0xFF1A140B), fg = Color(0xFFFFCC80), dim = Color(0xFFA08256),
        prompt = Color(0xFFFFB000), error = Color(0xFFFF6E5A), info = Color(0xFFD9A25F), link = Color(0xFFFFE0A3),
        script = Color(0xFFE8B96B), personal = Color(0xFFFFB000), work = Color(0xFF7FD4FF), private = Color(0xFFE08CFF),
    )
    val mono = Palette(
        bg = Color(0xFF000000), surface = Color(0xFF141414), fg = Color(0xFFE6E6E6), dim = Color(0xFF8C8C8C),
        prompt = Color(0xFFFFFFFF), error = Color(0xFFFF8080), info = Color(0xFFB0B0B0), link = Color(0xFFCCCCCC),
        script = Color(0xFFD0D0D0), personal = Color(0xFFB8D8B8), work = Color(0xFFFFD27F), private = Color(0xFFD7A6FF),
    )
    val dracula = Palette(
        bg = Color(0xFF282A36), surface = Color(0xFF343746), fg = Color(0xFFF8F8F2), dim = Color(0xFF8A93C2),
        prompt = Color(0xFF50FA7B), error = Color(0xFFFF5555), info = Color(0xFF8BE9FD), link = Color(0xFF8BE9FD),
        script = Color(0xFFF1FA8C), personal = Color(0xFF50FA7B), work = Color(0xFFFFB86C), private = Color(0xFFBD93F9),
    )
    val solarized = Palette(
        bg = Color(0xFF002B36), surface = Color(0xFF073642), fg = Color(0xFF93A1A1), dim = Color(0xFF7A9097),
        prompt = Color(0xFF859900), error = Color(0xFFDC322F), info = Color(0xFF2AA198), link = Color(0xFF268BD2),
        script = Color(0xFFB58900), personal = Color(0xFF859900), work = Color(0xFFCB4B16), private = Color(0xFF6C71C4),
    )

    val gruvbox = Palette(
        bg = Color(0xFF282828), surface = Color(0xFF3C3836), fg = Color(0xFFEBDBB2), dim = Color(0xFFA89984),
        prompt = Color(0xFFB8BB26), error = Color(0xFFFB4934), info = Color(0xFF8EC07C), link = Color(0xFF83A598),
        script = Color(0xFFFABD2F), personal = Color(0xFFB8BB26), work = Color(0xFFFE8019), private = Color(0xFFD3869B),
    )
    /** Gruvbox Material, dark "medium" background. */
    val gruvboxMaterial = Palette(
        bg = Color(0xFF292828), surface = Color(0xFF32302F), fg = Color(0xFFD4BE98), dim = Color(0xFFA89984),
        prompt = Color(0xFFA9B665), error = Color(0xFFEA6962), info = Color(0xFF89B482), link = Color(0xFF7DAEA3),
        script = Color(0xFFD8A657), personal = Color(0xFFA9B665), work = Color(0xFFE78A4E), private = Color(0xFFD3869B),
    )

    /** A built-in theme, or one people made ([CustomThemes]); green for a name it doesn't know. */
    fun named(name: String): Palette = when (name) {
        "green" -> green
        "gruvbox" -> gruvbox
        "gruvbox-material" -> gruvboxMaterial
        "amber" -> amber
        "mono" -> mono
        "dracula" -> dracula
        "solarized" -> solarized
        else -> CustomThemes.colors(name)?.let(::fromColors) ?: green
    }

    /** A custom theme's colours, by role ([CustomThemes.ROLES]), as a palette. */
    fun fromColors(c: Map<String, Int>): Palette {
        fun role(name: String) = Color(c.getValue(name))
        return Palette(
            bg = role("background"), surface = role("surface"), fg = role("text"), dim = role("muted"),
            prompt = role("accent"), error = role("error"), info = role("info"), link = role("link"),
            script = role("script"), personal = role("personal"), work = role("work"), private = role("private"),
        )
    }

    /** A palette's colours by role: where the theme builder starts. */
    fun colorsOf(p: Palette): Map<String, Int> = mapOf(
        "background" to p.bg, "surface" to p.surface, "text" to p.fg, "muted" to p.dim, "accent" to p.prompt,
        "link" to p.link, "info" to p.info, "script" to p.script, "error" to p.error,
        "personal" to p.personal, "work" to p.work, "private" to p.private,
    ).mapValues { it.value.toArgb() }
}

/**
 * Current terminal colours and type. Backed by Compose state, so changing the theme or font
 * size recomposes everything that reads from it.
 */
object Term {
    var palette by mutableStateOf(Palettes.green)
    var fontSize by mutableIntStateOf(14)

    /** Terminal typeface (`font`); [FontFamily.Monospace] is the system monospace. */
    var fontFamily by mutableStateOf<FontFamily>(FontFamily.Monospace)

    /** Minimum height (dp) of a plain log line (`log.line-height`); tappable lines get +10, two-column rows +14. */
    var lineHeight by mutableIntStateOf(30)

    /** Minimum height (dp) of suggestion and app-grid chips (`chips.height`). */
    var chipHeight by mutableIntStateOf(48)

    val bg get() = palette.bg
    val surface get() = palette.surface
    val fg get() = palette.fg
    val dim get() = palette.dim
    val prompt get() = palette.prompt
    val error get() = palette.error
    val info get() = palette.info
    val link get() = palette.link
    val script get() = palette.script

    fun profileColor(kind: ProfileKind) = when (kind) {
        ProfileKind.PERSONAL -> palette.personal
        ProfileKind.WORK -> palette.work
        ProfileKind.PRIVATE -> palette.private
        ProfileKind.CLONE -> palette.info
    }

    /** `font.sans`: the phone's own (sans-serif) font for nothing, the prompt, or the whole terminal. */
    var sans by mutableStateOf(Sans.OFF)

    enum class Sans {
        OFF, PROMPT, ALL;

        companion object {
            fun of(value: String) = when (value) {
                "prompt" -> PROMPT
                "all" -> ALL
                else -> OFF
            }
        }
    }

    /** The terminal's text: [fontFamily], or the phone's font with `font.sans all`. */
    val text: TextStyle get() = style(if (sans == Sans.ALL) FontFamily.Default else fontFamily)

    /** Always [fontFamily]: the script editor, and lists that line columns up with spaces. */
    val code: TextStyle get() = style(fontFamily)

    /** The prompt line: the phone's font from `font.sans prompt` on. */
    val promptText: TextStyle get() = style(if (sans == Sans.OFF) fontFamily else FontFamily.Default)

    private var cachedPalette: Palette? = null
    private var cachedSize = 0
    private val styles = HashMap<FontFamily, TextStyle>()

    /** Base text style in [family], rebuilt only when the palette or font size changes. */
    private fun style(family: FontFamily): TextStyle {
        val p = palette
        val size = fontSize
        if (cachedPalette !== p || cachedSize != size) {
            styles.clear()
            cachedPalette = p
            cachedSize = size
        }
        return styles.getOrPut(family) {
            TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.36f).sp, color = p.fg)
        }
    }
}

@Composable
fun KolahalTheme(content: @Composable () -> Unit) {
    val p = Term.palette
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = p.bg,
            surface = p.surface,
            surfaceContainer = p.surface,
            onSurface = p.fg,
            onBackground = p.fg,
            primary = p.prompt,
            error = p.error,
        ),
        content = content,
    )
}

/**
 * Top / side padding that clears both the status bar and any camera cutout. With the status bar
 * hidden (`statusbar hide`) its inset is 0, but the cutout's is not, so nothing ends up behind
 * the camera hole.
 */
@androidx.compose.runtime.Composable
fun androidx.compose.ui.Modifier.cutoutSafePadding(): androidx.compose.ui.Modifier = windowInsetsPadding(
    androidx.compose.foundation.layout.WindowInsets.statusBars
        .union(androidx.compose.foundation.layout.WindowInsets.displayCutout)
        .only(androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal),
)
