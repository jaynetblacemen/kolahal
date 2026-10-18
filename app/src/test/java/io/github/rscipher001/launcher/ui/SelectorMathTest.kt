package io.github.rscipher001.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorMathTest {
    @Test fun letterOfSectionsByFirstLetter() {
        assertEquals('M', SelectorMath.letterOf("Maps"))
        assertEquals('M', SelectorMath.letterOf("maps"))
        assertEquals('E', SelectorMath.letterOf("Éclair"))
        assertEquals('#', SelectorMath.letterOf("1Password"))
        assertEquals('#', SelectorMath.letterOf("Телеграм"))
        assertEquals('#', SelectorMath.letterOf(""))
        assertEquals('W', SelectorMath.letterOf("  ‎WhatsApp"))
    }

    private data class A(val label: String, val pinned: Boolean = false, val profile: Int = 0)

    private fun build(items: List<A>) =
        SelectorMath.build(items, { it.label }, { it.label + it.profile }, { it.pinned }, { it.profile })

    @Test fun buildFavouritesFirstThenAlphabeticalSections() {
        val list = build(listOf(A("chrome", profile = 1), A("Calendar"), A("Chrome"), A("Camera", pinned = true), A("2048"), A("Maps")))
        assertEquals(
            listOf("Camera", "2048", "Calendar", "Camera", "Chrome", "chrome", "Maps"),
            list.map { it.item.label },
        )
        assertEquals(listOf('★', '#', 'C', 'C', 'C', 'C', 'M'), list.map { it.section })
        assertEquals(listOf(true, true, true, false, false, false, true), list.map { it.sectionStart })
        assertEquals(listOf(0, 1, 2, 2, 2, 2, 3), list.map { it.sectionIndex })
        assertTrue(list[0].favourite && !list[3].favourite)
        // A favourite is listed twice, so keys must still be unique.
        assertEquals(list.size, list.map { it.key }.toSet().size)
        // Personal before work for the same label.
        assertEquals(listOf(0, 1), list.filter { it.item.label.equals("chrome", true) }.map { it.item.profile })
    }

    @Test fun buildWithoutFavouritesStartsAtFirstLetter() {
        val list = build(listOf(A("b"), A("a")))
        assertEquals(listOf('A', 'B'), list.map { it.section })
        assertEquals(listOf(0, 1), list.map { it.sectionIndex })
        assertTrue(build(emptyList()).isEmpty())
    }

    @Test fun scrollFractionIsProportionalWithEasedEnds() {
        assertEquals(0f, SelectorMath.scrollFraction(-1f))
        assertEquals(0f, SelectorMath.scrollFraction(0f))
        assertEquals(0f, SelectorMath.scrollFraction(SelectorMath.DEAD))
        assertEquals(1f, SelectorMath.scrollFraction(1f))
        assertEquals(1f, SelectorMath.scrollFraction(2f))
        assertEquals(0.5f, SelectorMath.scrollFraction(0.5f), 1e-5f)
        // Monotonic and continuous: small finger moves never jump.
        var prev = 0f
        for (i in 0..1000) {
            val f = SelectorMath.scrollFraction(i / 1000f)
            assertTrue(f >= prev)
            assertTrue(f - prev < 0.01f)
            prev = f
        }
        // Eased: slower than linear just after the top dead band.
        val u = 0.1f
        assertTrue(SelectorMath.scrollFraction(SelectorMath.DEAD + u * (1 - 2 * SelectorMath.DEAD)) < u)
    }

    @Test fun scrollOffsetCoversTheList() {
        assertEquals(0f, SelectorMath.scrollOffset(0f, 100, 40f, 800f))
        assertEquals(100 * 40f - 800f, SelectorMath.scrollOffset(1f, 100, 40f, 800f))
        assertEquals(1600f, SelectorMath.scrollOffset(0.5f, 100, 40f, 800f))
        // Fits in the window: never scrolls.
        assertEquals(0f, SelectorMath.scrollOffset(1f, 10, 40f, 800f))
    }

    @Test fun focusFollowsTheFingerAcrossTheWholeList() {
        val rows = 100
        val row = 40f
        val view = 800f
        // Finger at the top of the strip → first app; at the bottom → last app.
        val top = SelectorMath.contact(SelectorMath.scrollOffset(SelectorMath.scrollFraction(0f), rows, row, view), 0f, row)
        assertEquals(0, SelectorMath.focusedIndex(top, rows))
        val bottom = SelectorMath.contact(SelectorMath.scrollOffset(SelectorMath.scrollFraction(1f), rows, row, view), view - 1f, row)
        assertEquals(rows - 1, SelectorMath.focusedIndex(bottom, rows))
        // Middle of the strip → middle of the list.
        val mid = SelectorMath.contact(SelectorMath.scrollOffset(SelectorMath.scrollFraction(0.5f), rows, row, view), view / 2, row)
        assertEquals(50, SelectorMath.focusedIndex(mid, rows))
        assertEquals(-1, SelectorMath.focusedIndex(Float.NaN, rows))
        assertEquals(-1, SelectorMath.focusedIndex(3f, 0))
        assertEquals(9, SelectorMath.focusedIndex(25f, 10))
        assertEquals(0, SelectorMath.focusedIndex(-2f, 10))
    }

    @Test fun fisheyeMagnifiesTheFocusAndTapers() {
        assertEquals(1f + SelectorMath.PEAK, SelectorMath.fisheyeScale(0f), 1e-5f)
        assertEquals(1f, SelectorMath.fisheyeScale(SelectorMath.REACH))
        assertEquals(1f, SelectorMath.fisheyeScale(-10f))
        assertEquals(SelectorMath.fisheyeScale(-1.3f), SelectorMath.fisheyeScale(1.3f), 1e-6f)
        assertTrue(SelectorMath.fisheyeScale(0f) > SelectorMath.fisheyeScale(1f))
        assertTrue(SelectorMath.fisheyeScale(1f) > SelectorMath.fisheyeScale(2f))
        assertTrue(SelectorMath.fisheyeScale(2f) > SelectorMath.fisheyeScale(2.9f))
    }

    @Test fun fisheyeShiftPushesNeighboursApartWithoutOverlap() {
        assertEquals(0f, SelectorMath.fisheyeShift(0f))
        assertEquals(-SelectorMath.fisheyeShift(1.7f), SelectorMath.fisheyeShift(-1.7f), 1e-6f)
        val far = SelectorMath.PEAK * SelectorMath.REACH / 2
        assertEquals(far, SelectorMath.fisheyeShift(SelectorMath.REACH), 1e-5f)
        assertEquals(far, SelectorMath.fisheyeShift(20f), 1e-5f)
        // Adjacent rows stay about a (scaled) row apart: spacing ≈ the mean of their scales.
        var x = -5f
        while (x < 5f) {
            val gap = 1f + SelectorMath.fisheyeShift(x + 1) - SelectorMath.fisheyeShift(x)
            val need = (SelectorMath.fisheyeScale(x) + SelectorMath.fisheyeScale(x + 1)) / 2
            assertTrue("gap $gap < $need at $x", gap >= need - 0.03f)
            x += 0.05f
        }
    }

    @Test fun edgeSpeedRampsAtTheEnds() {
        assertEquals(0f, SelectorMath.edgeSpeed(400f, 800f, 50f, 1f))
        assertEquals(-1f, SelectorMath.edgeSpeed(0f, 800f, 50f, 1f))
        assertEquals(-1f, SelectorMath.edgeSpeed(-30f, 800f, 50f, 1f))
        assertEquals(-0.5f, SelectorMath.edgeSpeed(25f, 800f, 50f, 1f), 1e-5f)
        assertEquals(1f, SelectorMath.edgeSpeed(900f, 800f, 50f, 1f))
        assertEquals(0.5f, SelectorMath.edgeSpeed(775f, 800f, 50f, 1f), 1e-5f)
        // A short panel keeps a still middle.
        assertEquals(0f, SelectorMath.edgeSpeed(50f, 100f, 50f, 1f))
    }

    @Test fun completionHelpForCommandsAndKeys() {
        assertEquals("Show this list", completionHelp("help", "help "))
        assertEquals(io.github.rscipher001.launcher.data.Keys.SELECTOR_RAIL.help, completionHelp("selector.rail", "config set selector.rail "))
        assertNull(completionHelp("on", "config set selector.rail on"))
    }
}
