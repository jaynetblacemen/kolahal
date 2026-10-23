package com.mtwabpin.kolahal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file a theme made in the theme builder is kept in, and the hex codes the builder takes. */
class CustomThemesTest {
    private val colors = CustomThemes.ROLES.mapIndexed { i, role -> role to (0xFF000000.toInt() or (i * 0x151515)) }.toMap()

    @Test fun aThemeRoundTripsThroughItsFile() {
        val text = CustomThemes.encode(colors)
        assertTrue(text, text.contains("\"background\": \"#000000\""))
        assertEquals(colors, CustomThemes.decode(text))
    }

    @Test fun aFileMissingAColourIsNotATheme() {
        val text = CustomThemes.encode(colors).lines().filterNot { it.contains("\"error\"") }.joinToString("\n")
        assertNull(CustomThemes.decode(text))
        assertNull(CustomThemes.decode("not json"))
    }

    /** Written by hand: any spacing or case, extra keys ignored. */
    @Test fun aHandWrittenFileIsRead() {
        val text = "{" + colors.entries.joinToString(",") { (role, c) -> "\"$role\":\"${CustomThemes.hex(c).lowercase()}\"" } + ", \"note\": \"#FFFFFF\"}"
        assertEquals(colors, CustomThemes.decode(text))
    }

    @Test fun aLightVersionTravelsInTheSameFile() {
        val both = colors + colors.mapKeys { CustomThemes.LIGHT + it.key }.mapValues { it.value or 0x00F0F0F0 }
        val text = CustomThemes.encode(both)
        assertTrue(text, text.contains("\"light-background\""))
        assertEquals(both, CustomThemes.decode(text))
        assertEquals(colors.mapValues { it.value or 0x00F0F0F0 }, CustomThemes.light(both))
    }

    /** Made before themes had a light version, or with only part of one: the same in both modes. */
    @Test fun noOrHalfALightVersionIsNone() {
        assertNull(CustomThemes.light(colors))
        val half = colors + mapOf(CustomThemes.LIGHT + "background" to 0xFFFFFFFF.toInt())
        assertEquals(colors, CustomThemes.decode(CustomThemes.encode(colors) .replace("\n}", ",\n  \"light-background\": \"#FFFFFF\"\n}")))
        assertNull(CustomThemes.light(half))
        assertFalse(CustomThemes.isKey("light-nope"))
        assertTrue(CustomThemes.isKey("light-background"))
    }

    @Test fun hexCodes() {
        assertEquals("#0B0D0B", CustomThemes.hex(0xFF0B0D0B.toInt()))
        assertEquals(0xFF8FD18A.toInt(), CustomThemes.parseHex("#8fd18a"))
        assertEquals(0xFF8FD18A.toInt(), CustomThemes.parseHex(" 8FD18A "))
        for (bad in listOf("#8FD18", "#8FD18A00", "#GGGGGG", "", "#")) assertNull(bad, CustomThemes.parseHex(bad))
    }

    @Test fun names() {
        for (good in listOf("my-theme", "sepia2", "a")) assertTrue(good, CustomThemes.NAME.matches(good))
        for (bad in listOf("My Theme", "-dash", "a".repeat(25), "dots.no", "")) assertFalse(bad, CustomThemes.NAME.matches(bad))
    }
}
