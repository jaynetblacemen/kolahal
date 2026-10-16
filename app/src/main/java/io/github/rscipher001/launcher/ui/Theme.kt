package io.github.rscipher001.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.rscipher001.launcher.apps.ProfileKind

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

    fun named(name: String) = when (name) {
        "amber" -> amber
        "mono" -> mono
        "dracula" -> dracula
        "solarized" -> solarized
        else -> green
    }
}

/**
 * Current terminal colours and type. Backed by Compose state, so changing the theme or font
 * size recomposes everything that reads from it.
 */
object Term {
    var palette by mutableStateOf(Palettes.green)
    var fontSize by mutableIntStateOf(14)

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

    private var monoCache: Triple<Palette, Int, TextStyle>? = null

    /** Base terminal text style, rebuilt only when the palette or font size changes. */
    val mono: TextStyle
        get() {
            val p = palette
            val size = fontSize
            monoCache?.let { (cp, cs, style) -> if (cp === p && cs == size) return style }
            return TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = size.sp,
                lineHeight = (size * 1.36f).sp,
                color = p.fg,
            ).also { monoCache = Triple(p, size, it) }
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
