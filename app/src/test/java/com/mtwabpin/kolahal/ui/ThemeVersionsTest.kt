package com.mtwabpin.kolahal.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.mtwabpin.kolahal.data.CustomThemes
import com.mtwabpin.kolahal.data.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every built-in theme has a dark and a light version, and both read well. */
class ThemeVersionsTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test fun eachThemeHasADarkAndALightVersion() {
        for (name in Keys.BUILT_IN_THEMES) {
            val dark = Palettes.named(name, dark = true)
            val light = Palettes.named(name, dark = false)
            assertNotEquals(name, dark, light)
            assertTrue("$name: dark background", dark.bg.luminance() < 0.2f)
            assertTrue("$name: light background", light.bg.luminance() > 0.7f)
        }
        assertEquals(Palettes.gruvbox, Palettes.named("gruvbox"))
        assertEquals(Palettes.greenLight, Palettes.named("no-such-theme", dark = false))
    }

    @Test fun theLightVersionsAreReadable() {
        for (name in Keys.BUILT_IN_THEMES) {
            val p = Palettes.named(name, dark = false)
            assertTrue("$name text", contrast(p.fg, p.bg) >= 7f)
            assertTrue("$name muted", contrast(p.dim, p.bg) >= 4f)
            for ((role, c) in listOf("accent" to p.prompt, "error" to p.error, "info" to p.info, "link" to p.link, "script" to p.script, "work" to p.work, "private" to p.private)) {
                assertTrue("$name $role", contrast(c, p.bg) >= 3f)
            }
        }
    }

    @Test fun theBuilderGetsBothVersions() {
        val colors = Palettes.themeColors("dracula")
        assertEquals(Palettes.colorsOf(Palettes.dracula), colors.filterKeys { it in CustomThemes.ROLES })
        assertEquals(Palettes.colorsOf(Palettes.draculaLight), CustomThemes.light(colors))
    }
}
