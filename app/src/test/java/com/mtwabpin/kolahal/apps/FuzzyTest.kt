package com.mtwabpin.kolahal.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {
    @Test fun accentsDontMatter() {
        assertEquals("cafe", Fuzzy.fold("Café"))
        assertEquals("ubersetzer", Fuzzy.fold("Übersetzer"))
    }

    @Test fun slipsFindTheApp() {
        assertTrue(Fuzzy.slip("mpas", listOf("maps")))
        assertTrue(Fuzzy.slip("gmial", listOf("gmail")))
        assertTrue(Fuzzy.slip("chrone", listOf("chrome")))
        assertTrue(Fuzzy.slip("yuotube", listOf("youtube")))
        // Of a word's start, as typing goes.
        assertTrue(Fuzzy.slip("clacu", listOf("calculator")))
        assertTrue(Fuzzy.slip("teelgram", listOf("telegram")))
        assertTrue(Fuzzy.slip("whtasapp", listOf("whatsapp")))
    }

    /** Two slips from eight letters on, whichever way they go. */
    @Test fun twoSlipsFromEightLetters() {
        assertTrue("two missing", Fuzzy.slip("calcltor", listOf("calculator")))
        assertTrue("two extra", Fuzzy.slip("caalculattor", listOf("calculator")))
        assertTrue("two wrong", Fuzzy.slip("cakculatoe", listOf("calculator")))
        assertTrue("one of each", Fuzzy.slip("calcxlatr", listOf("calculator")))
        // Of a word's start, as typing goes.
        assertTrue(Fuzzy.slip("settngs up", listOf("settings up")))
        // Below eight letters, still one.
        assertFalse(Fuzzy.slip("calcltr", listOf("calculator")))
        assertFalse(Fuzzy.slip("telgrm", listOf("telegram")))
        // Three is too many.
        assertFalse(Fuzzy.slip("clclatoor", listOf("calculator")))
    }

    @Test fun notEverythingIsASlip() {
        assertFalse(Fuzzy.slip("map", listOf("mop")))
        assertFalse(Fuzzy.slip("maps", listOf("mail")))
        assertFalse(Fuzzy.slip("notes", listOf("photos")))
    }

    @Test fun swapsCountOnce() {
        assertEquals(1, Fuzzy.distance("mpas", "maps"))
        assertEquals(1, Fuzzy.distance("maps", "map"))
        assertEquals(2, Fuzzy.distance("maps", "mail"))
    }

    /** A launch counts one, halving every week: last month's habit loses to today's. */
    @Test fun oldLaunchesFade() {
        val week = 7 * 86_400_000L
        assertEquals(25.0, AppPrefs.decayed(50.0, week), 1e-9)
        val oldFavourite = AppPrefs.decayed(50.0, 8 * week) + 1
        val daily = (0 until 30).sumOf { AppPrefs.decayed(1.0, it * 86_400_000L) }
        assertTrue("$oldFavourite vs $daily", daily > oldFavourite * 5)
    }
}
