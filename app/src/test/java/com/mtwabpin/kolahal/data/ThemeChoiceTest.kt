package com.mtwabpin.kolahal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `theme.name` as one theme or a light/dark pair. */
class ThemeChoiceTest {
    @Test fun oneThemeOrAPair() {
        assertEquals("gruvbox", Keys.THEME.parse("Gruvbox"))
        assertEquals("gruvbox-light/gruvbox", Keys.THEME.parse("gruvbox-light/gruvbox"))
        assertEquals("solarized-light/green", Keys.THEME.parse(" solarized-light / green "))
        // The same theme twice is just that theme.
        assertEquals("amber", Keys.THEME.parse("amber/amber"))
        assertNull(Keys.THEME.parse("nope/gruvbox"))
        assertNull(Keys.THEME.parse("gruvbox/"))
        assertNull(Keys.THEME.parse("a/b/c"))
    }

    @Test fun aPairFollowsThePhone() {
        val pair = "gruvbox-light/gruvbox"
        assertEquals("gruvbox", ThemeChoice.showing(pair, dark = true))
        assertEquals("gruvbox-light", ThemeChoice.showing(pair, dark = false))
        assertEquals("amber", ThemeChoice.showing("amber", dark = false))
        assertEquals("amber", ThemeChoice.showing("amber", dark = true))
        assertEquals(listOf("gruvbox-light", "gruvbox"), ThemeChoice.names(pair))
        assertNull(ThemeChoice.light("amber"))
    }

    @Test fun theBuilderKeepsTheOtherHalf() {
        val pair = "mine-light/gruvbox"
        assertEquals("mine-day/gruvbox", ThemeChoice.saved(pair, old = "mine-light", new = "mine-day", dark = true))
        assertEquals("mine-light/new", ThemeChoice.saved(pair, old = null, new = "new", dark = true))
        assertEquals("new/gruvbox", ThemeChoice.saved(pair, old = null, new = "new", dark = false))
        assertEquals("new", ThemeChoice.saved("amber", old = null, new = "new", dark = true))
    }

    @Test fun deletingHalfLeavesTheOther() {
        assertEquals("gruvbox", ThemeChoice.without("mine/gruvbox", "mine"))
        assertEquals("mine", ThemeChoice.without("mine/gruvbox", "gruvbox"))
        assertNull(ThemeChoice.without("mine", "mine"))
        assertEquals("amber", ThemeChoice.without("amber", "mine"))
    }

    @Test fun alwaysDarkIsOffUnlessChosen() = assertEquals(false, Keys.THEME_ALWAYS_DARK.default)
}
