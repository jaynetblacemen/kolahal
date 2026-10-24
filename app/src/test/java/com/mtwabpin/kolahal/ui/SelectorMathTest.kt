package com.mtwabpin.kolahal.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorMathTest {
    /** Plan B: the held row stays empty for 300 ms, then fills until the actions open at 800 ms. */
    @Test fun theHoldFillsAsItCounts() {
        assertEquals(0f, SelectorMath.holdFill(0), 0f)
        assertEquals(0f, SelectorMath.holdFill(300), 0f)
        assertEquals(0.5f, SelectorMath.holdFill(550), 0.001f)
        assertEquals(1f, SelectorMath.holdFill(800), 0f)
        assertEquals(1f, SelectorMath.holdFill(2_000), 0f)
    }

    /** A move clears the hold: the fill drains away rather than vanishing, and rises at once. */
    @Test fun aStoppedHoldDrains() {
        assertEquals(0.8f, SelectorMath.holdShown(0.2f, 0.8f, 16), 0f)
        assertEquals(0.8f - 60f / SelectorMath.DRAIN_MS, SelectorMath.holdShown(0.8f, 0f, 60), 0.001f)
        assertEquals(0f, SelectorMath.holdShown(0.8f, 0f, 500), 0f)
    }

    /** B2: the far side is the panel's last 30%, away from the strip. */
    @Test fun theFarSideIsAwayFromTheStrip() {
        // Strip on the right, panel at 0..252: the far side is left of 75.6.
        assertTrue(SelectorMath.farSide(60f, 0f, 252f, left = false))
        assertFalse(SelectorMath.farSide(120f, 0f, 252f, left = false))
        // Strip on the left, panel at 108..360: the far side is right of 284.4.
        assertTrue(SelectorMath.farSide(300f, 108f, 252f, left = true))
        assertFalse(SelectorMath.farSide(200f, 108f, 252f, left = true))
    }

    /** Plan A: with gesture navigation a touch starts only in the middle 200dp; otherwise anywhere on the strip. */
    @Test fun aTouchStartsInTheMiddleWhenTheEdgeSwipesBack() {
        assertEquals(75f to 200f, SelectorMath.startZone(350f, 200f, backGesture = true))
        assertEquals(0f to 350f, SelectorMath.startZone(350f, 200f, backGesture = false))
        assertEquals(0f to 180f, SelectorMath.startZone(180f, 200f, backGesture = true))
    }

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
        assertEquals(listOf(1, 1, 4, 1), SelectorMath.sectionSizes(list))
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

    /** Plan D: each section's room goes by the square root of its size: four times the apps, twice the room. */
    @Test fun everyLetterGetsRoom() {
        val sections = SelectorMath.Sections(listOf(1, 4, 16))
        // √1 : √4 : √16 is 1 : 2 : 4, out of 7.
        assertEquals(1f / 7, sections.shares[0], 1e-5f)
        assertEquals(2f / 7, sections.shares[1], 1e-5f)
        assertEquals(4f / 7, sections.shares[2], 1e-5f)
        val d = SelectorMath.DEAD
        fun t(u: Float) = d + u * (1 - 2 * d)
        // The dead bands pin the ends; halfway across a section's room is halfway through its apps.
        assertEquals(0f, sections.along(0f), 0f)
        assertEquals(0f, sections.along(d), 0f)
        assertEquals(20.99f, sections.along(1f), 0.001f)
        assertEquals(0.5f, sections.along(t(0.5f / 7)), 1e-3f)
        assertEquals(1f + 2f, sections.along(t(2f / 7)), 1e-3f)
        assertEquals(5f + 8f, sections.along(t(5f / 7)), 1e-3f)
        // Monotonic and continuous: small finger moves never jump.
        var prev = 0f
        for (i in 0..1000) {
            val a = sections.along(i / 1000f)
            assertTrue(a >= prev)
            assertTrue(a - prev < 0.2f)
            prev = a
        }
        assertEquals(0f, SelectorMath.Sections(emptyList()).along(0.5f), 0f)
    }

    @Test fun theStripCoversTheWholeList() {
        val rows = 100
        val one = SelectorMath.Sections(listOf(rows))
        // The strip's top is the first app, its bottom the last, its middle the middle one.
        assertEquals(0, SelectorMath.focusedIndex(one.along(0f), rows))
        assertEquals(rows - 1, SelectorMath.focusedIndex(one.along(1f), rows))
        assertEquals(50, SelectorMath.focusedIndex(one.along(0.5f), rows))
        // With sections too: the ends are the list's ends.
        val many = SelectorMath.Sections(listOf(5, 2, 30, 1, 62))
        assertEquals(0, SelectorMath.focusedIndex(many.along(0f), rows))
        assertEquals(rows - 1, SelectorMath.focusedIndex(many.along(1f), rows))
    }

    /** As Niagara does: the focused app is drawn level with the thumb, wherever on the screen it is. */
    @Test fun theFocusedAppIsLevelWithTheFinger() {
        val rows = 100
        val row = 40f
        val view = 800f
        val max = SelectorMath.maxScroll(rows, row, view)
        // Where a list position is drawn: its row, less the scroll, plus the panel's shift.
        fun drawnAt(at: Float, listY: Float) = SelectorMath.place(listY, max).let { (scroll, shift) -> at * row - scroll + shift }
        for ((fraction, finger) in listOf(0.5f to 600f, 0f to 600f, 1f to 100f, 0.02f to 750f)) {
            val at = (fraction * rows).coerceIn(0f, rows - 0.01f)
            val listY = SelectorMath.levelWith(at, finger, row)
            // Level with the finger, at the list's ends too (the panel moves the rest of the way).
            assertEquals("$fraction", finger, drawnAt(at, listY), 0.01f)
            // So picking starts on the same app.
            assertEquals(at, SelectorMath.contact(listY, finger, row), 0.01f)
        }
        // In the middle the list scrolls and the panel stays; at the top it can't, and the panel moves down.
        assertEquals(0f, SelectorMath.place(SelectorMath.levelWith(50f, 600f, row), max).second)
        assertEquals(600f, SelectorMath.place(SelectorMath.levelWith(0f, 600f, row), max).second)
        // A short list never scrolls: the panel does it all.
        assertEquals(0f, SelectorMath.place(SelectorMath.levelWith(5f, 100f, row), SelectorMath.maxScroll(10, row, view)).first)
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
        assertEquals(com.mtwabpin.kolahal.data.Keys.SELECTOR_RAIL.help, completionHelp("selector.rail", "config set selector.rail "))
        assertNull(completionHelp("on", "config set selector.rail on"))
    }

    /** One finger's path: (in the list, row, section) per move, felt as the selector does. */
    private fun feels(vararg moves: Triple<Boolean, Int, Int>): List<SelectorMath.Feel> {
        var wasSelecting = false
        var lastRow = -1
        var lastSection = -1
        return moves.map { (selecting, row, section) ->
            SelectorMath.feel(selecting, wasSelecting, row, lastRow, section, lastSection).also {
                wasSelecting = selecting
                lastRow = row
                lastSection = section
            }
        }
    }

    @Test fun lettersAppsAndTheSwitchEachHaveAFeel() {
        val edge = { row: Int, section: Int -> Triple(false, row, section) }
        val list = { row: Int, section: Int -> Triple(true, row, section) }
        assertEquals(
            listOf(
                SelectorMath.Feel.NONE, // touching down
                SelectorMath.Feel.NONE, // another app, the same letter
                SelectorMath.Feel.LETTER,
                SelectorMath.Feel.SWITCH, // into the list
                SelectorMath.Feel.APP,
                SelectorMath.Feel.NONE, // still on that app
                SelectorMath.Feel.APP, // a new letter in the list is still an app
                SelectorMath.Feel.SWITCH, // back to the letters
                SelectorMath.Feel.LETTER,
            ),
            feels(edge(0, 0), edge(3, 0), edge(5, 1), list(5, 1), list(6, 1), list(6, 1), list(7, 2), edge(9, 3), edge(12, 4)),
        )
    }
}
