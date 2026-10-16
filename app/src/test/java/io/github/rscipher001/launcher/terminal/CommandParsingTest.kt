package io.github.rscipher001.launcher.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParsingTest {
    @Test fun tokenizeHonoursQuotes() {
        assertEquals(listOf("open", "Google Maps", "-w"), CommandProcessor.tokenize("open \"Google Maps\" -w"))
        assertEquals(listOf("a", "b c"), CommandProcessor.tokenize("  a   'b c' "))
    }

    @Test fun parseInterval() {
        assertEquals(30, CommandProcessor.parseInterval("30m"))
        assertEquals(30, CommandProcessor.parseInterval("30"))
        assertEquals(120, CommandProcessor.parseInterval("2h"))
        assertEquals(1440, CommandProcessor.parseInterval("1d"))
        assertEquals(0, CommandProcessor.parseInterval("off"))
        assertNull(CommandProcessor.parseInterval("soon"))
    }

    @Test fun formatInterval() {
        assertEquals("30m", CommandProcessor.formatInterval(30))
        assertEquals("2h", CommandProcessor.formatInterval(120))
        assertEquals("1d", CommandProcessor.formatInterval(1440))
    }
}
