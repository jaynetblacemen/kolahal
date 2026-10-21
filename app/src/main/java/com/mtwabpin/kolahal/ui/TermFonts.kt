package com.mtwabpin.kolahal.ui

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.data.Keys
import java.io.File

/**
 * Terminal fonts: the bundled ones (res/font, OFL — see assets/licenses), the system monospace,
 * and one user-imported file kept at `filesDir/fonts/custom.ttf|otf`.
 */
object TermFonts {
    const val SYSTEM = "system"
    const val CUSTOM = "custom"

    /** Imported fonts larger than this are refused. */
    const val MAX_BYTES = 10L * 1024 * 1024

    val names: List<String> get() = Keys.FONT.choices

    fun label(name: String) = when (name) {
        SYSTEM -> "System mono"
        "jetbrains-mono" -> "JetBrains Mono"
        "fira-code" -> "Fira Code"
        "ibm-plex-mono" -> "IBM Plex Mono"
        "source-code-pro" -> "Source Code Pro"
        CUSTOM -> "Custom"
        else -> name
    }

    private val bundled: Map<String, FontFamily> by lazy {
        fun family(regular: Int, bold: Int) = FontFamily(Font(regular, FontWeight.Normal), Font(bold, FontWeight.Bold))
        mapOf(
            "jetbrains-mono" to family(R.font.jetbrains_mono_regular, R.font.jetbrains_mono_bold),
            "fira-code" to family(R.font.fira_code_regular, R.font.fira_code_bold),
            "ibm-plex-mono" to family(R.font.ibm_plex_mono_regular, R.font.ibm_plex_mono_bold),
            "source-code-pro" to family(R.font.source_code_pro_regular, R.font.source_code_pro_bold),
        )
    }

    fun dir(context: Context) = File(context.filesDir, "fonts")

    /** The imported font file, if there is one. */
    fun customFile(context: Context): File? =
        listOf("custom.ttf", "custom.otf").map { File(dir(context), it) }.firstOrNull { it.isFile }

    /** Loads [file] eagerly; null when it is not a usable font (so nothing fails later at layout). */
    fun loadTypeface(file: File): android.graphics.Typeface? = runCatching {
        // Font.Builder throws on a corrupt file; Typeface.createFromFile silently returns DEFAULT.
        if (Build.VERSION.SDK_INT >= 29) android.graphics.fonts.Font.Builder(file).build()
        android.graphics.Typeface.createFromFile(file)
    }.getOrNull()?.takeIf { it != android.graphics.Typeface.DEFAULT }

    private class Loaded(val path: String, val stamp: Long, val family: FontFamily)

    @Volatile private var customCache: Loaded? = null

    /** Bumped after an import so previews re-read the file even when `font` was already custom. */
    var revision by mutableIntStateOf(0)

    /** The imported font as a family, or null when it is missing or unreadable. Cached per file version. */
    fun customFamily(context: Context): FontFamily? {
        val file = customFile(context) ?: return null
        val stamp = file.lastModified() xor file.length()
        customCache?.let { if (it.path == file.path && it.stamp == stamp) return it.family }
        val tf = loadTypeface(file) ?: return null
        return FontFamily(androidx.compose.ui.text.font.Typeface(tf))
            .also { customCache = Loaded(file.path, stamp, it) }
    }

    /** [name] as a family; null only for a custom font that cannot be loaded. */
    fun family(context: Context, name: String): FontFamily? = when (name) {
        CUSTOM -> customFamily(context)
        else -> bundled[name] ?: FontFamily.Monospace
    }
}
