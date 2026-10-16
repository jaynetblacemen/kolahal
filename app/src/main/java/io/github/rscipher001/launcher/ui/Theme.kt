package io.github.rscipher001.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.rscipher001.launcher.apps.ProfileKind

object Term {
    val bg = Color(0xFF0B0D0B)
    val surface = Color(0xFF151915)
    val fg = Color(0xFFD7DED3)
    val dim = Color(0xFF6F786B)
    val prompt = Color(0xFF8FD18A)
    val error = Color(0xFFF0907D)
    val info = Color(0xFF9DB8A0)
    val link = Color(0xFF7FB8F0)
    val script = Color(0xFFB5CEA8)
    val personal = Color(0xFF8FD18A)
    val work = Color(0xFFF2B35B)
    val private = Color(0xFFC792EA)

    fun profileColor(kind: ProfileKind) = when (kind) {
        ProfileKind.PERSONAL -> personal
        ProfileKind.WORK -> work
        ProfileKind.PRIVATE -> private
    }

    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 19.sp, color = fg)
}

@Composable
fun KolahalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Term.bg,
            surface = Term.surface,
            onSurface = Term.fg,
            onBackground = Term.fg,
            primary = Term.prompt,
            error = Term.error,
        ),
        content = content,
    )
}
