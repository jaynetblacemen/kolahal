package com.mtwabpin.kolahal.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.mtwabpin.kolahal.data.Keys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `theme.paper`: an old page behind home, still readable at its oldest. */
class PaperTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun Int.color() = Color(this)

    @Test fun atZeroThePageIsTheThemesOwnColour() {
        val bg = Palettes.named("solarized", dark = false).bg
        assertEquals(bg, Paper.base(bg, 0))
        val pixels = Paper.pixels(bg, 0).distinct()
        assertEquals(1, pixels.size)
        assertTrue(contrast(pixels.single().color(), bg) < 1.01f)
    }

    @Test fun higherIsAnOlderPage() {
        val bg = Palettes.named("mono", dark = false).bg
        val yellow = { c: Color -> c.red + c.green - 2 * c.blue }
        val (young, old) = listOf(20, 90).map { Paper.base(bg, it) }
        assertTrue(yellow(old) > yellow(young))
        assertTrue(old.luminance() < young.luminance())
        // More texture too: the tile's pixels spread wider.
        val spread = { s: Int -> Paper.pixels(bg, s).map { it.color().luminance() }.let { l -> l.max() - l.min() } }
        assertTrue(spread(90) > spread(20))
    }

    @Test fun theSamePageEveryTime() {
        val bg = Palettes.named("gruvbox", dark = false).bg
        assertArrayEquals(Paper.pixels(bg, 55), Paper.pixels(bg, 55))
    }

    /** At its oldest, on the paper's darker shade (a grain step under the page), every light theme still reads. */
    @Test fun everyLightThemeReadsOnItsOldestPage() {
        for (name in Keys.BUILT_IN_THEMES) {
            val p = Palettes.named(name, dark = false)
            val base = Paper.base(p.bg, 100)
            val step = Paper.grain(100) / 255f
            val darker = Color((base.red - step).coerceAtLeast(0f), (base.green - step * 0.96f).coerceAtLeast(0f), (base.blue - step * 0.85f).coerceAtLeast(0f))
            assertTrue("$name text", contrast(p.fg, darker) >= 5.5f)
            assertTrue("$name muted", contrast(p.dim, darker) >= 3f)
            for ((role, c) in listOf("accent" to p.prompt, "error" to p.error, "link" to p.link, "script" to p.script, "good" to p.good, "warn" to p.warn)) {
                assertTrue("$name $role", contrast(c, darker) >= 3f)
            }
            // Most of the page is lighter than that: half its pixels at least as light as the page itself, give or take the grain.
            val page = Paper.pixels(p.bg, 100).map { it.color().luminance() }.sorted()
            assertTrue("$name median", page[page.size / 2] >= darker.luminance())
        }
    }
}
