package io.github.rscipher001.launcher.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rscipher001.launcher.R
import io.github.rscipher001.launcher.data.Keys
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

private const val SAMPLE_PROMPT = "$ ls ~"
private const val SAMPLE_GLYPHS = "0Oo1lI {}=>"

/**
 * Horizontally scrolling font previews in the terminal colours; the last card imports a font file.
 * The custom card shows once a font has been imported (or while `font = custom`).
 */
@Composable
fun FontChoices(selected: String, onPick: (String) -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val p = Term.palette
    // Re-read when the selection changes, which is what an import does.
    val custom = remember(selected, TermFonts.revision) { TermFonts.customFamily(context) }
    val names = TermFonts.names.filter { it != TermFonts.CUSTOM || custom != null || selected == TermFonts.CUSTOM }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(names) { name ->
            val family = if (name == TermFonts.CUSTOM) custom ?: FontFamily.Monospace else TermFonts.family(context, name) ?: FontFamily.Monospace
            val on = name == selected
            Card(
                onClick = { onPick(name) },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = p.bg),
                border = if (on) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, p.surface),
                modifier = Modifier.width(148.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    val sample = MaterialTheme.typography.bodyMedium.copy(fontFamily = family)
                    Text(SAMPLE_PROMPT, color = p.prompt, style = sample.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(SAMPLE_GLYPHS, color = p.fg, style = sample, maxLines = 1, overflow = TextOverflow.Clip)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (name == TermFonts.CUSTOM && custom == null) "Custom (missing)" else TermFonts.label(name),
                        color = if (name == TermFonts.CUSTOM && custom == null) p.error else p.dim,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item {
            Card(
                onClick = onImport,
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = p.surface),
                border = BorderStroke(1.dp, p.surface),
                modifier = Modifier.width(148.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Icon(painterResource(R.drawable.ic_upload), null, tint = p.prompt, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Import .ttf / .otf…", color = p.fg, style = MaterialTheme.typography.labelLarge, maxLines = 2)
                }
            }
        }
    }
}
