package com.mtwabpin.kolahal.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParsingTest {
    @Test fun tokenizeHonoursQuotes() {
        assertEquals(listOf("open", "Google Maps", "-w"), CommandParsing.tokenize("open \"Google Maps\" -w"))
        assertEquals(listOf("a", "b c"), CommandParsing.tokenize("  a   'b c' "))
    }

    @Test fun parseInterval() {
        assertEquals(30, CommandParsing.parseInterval("30m"))
        assertEquals(30, CommandParsing.parseInterval("30"))
        assertEquals(120, CommandParsing.parseInterval("2h"))
        assertEquals(1440, CommandParsing.parseInterval("1d"))
        assertEquals(0, CommandParsing.parseInterval("off"))
        assertNull(CommandParsing.parseInterval("soon"))
    }

    @Test fun formatInterval() {
        assertEquals("30m", CommandParsing.formatInterval(30))
        assertEquals("2h", CommandParsing.formatInterval(120))
        assertEquals("1d", CommandParsing.formatInterval(1440))
    }
}
