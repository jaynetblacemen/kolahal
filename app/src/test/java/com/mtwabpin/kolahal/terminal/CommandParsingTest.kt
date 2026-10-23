package com.mtwabpin.kolahal.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParsingTest {
    @Test fun tokenizeHonoursQuotes() {
        assertEquals(listOf("open", "Google Maps", "-w"), CommandParsing.tokenize("open \"Google Maps\" -w"))
        assertEquals(listOf("a", "b c"), CommandParsing.tokenize("  a   'b c' "))
    }

    @Test fun anApostropheInsideAWordIsALetter() {
        assertEquals(listOf("sms", "mom", "I'm", "late"), CommandParsing.tokenize("sms mom I'm late"))
        assertEquals(listOf("open", "Domino's", "-w", "--resume"), CommandParsing.tokenize("open Domino's -w --resume"))
        assertEquals(listOf("say", "it's ok"), CommandParsing.tokenize("say \"it's ok\""))
    }

    @Test fun everyKindOfSpaceIsAPlainOne() {
        assertEquals("script secret x K v", CommandParsing.spaces("script\tsecret x K\nv"))
        val plain = "already plain"
        assertTrue(plain === CommandParsing.spaces(plain))
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
