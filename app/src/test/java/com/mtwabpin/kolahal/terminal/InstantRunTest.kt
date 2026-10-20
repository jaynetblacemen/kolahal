package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.terminal.InstantRun.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantRunTest {
    private val apps = listOf("Groww", "Grove", "Settings", "Calendar", "Camera", "YouTube", "youtube")
    private val commands = mapOf(
        "settings" to "settings", "setup" to "setup [text]", "call" to "call <name|number>",
        "help" to "help", "history" to "history", "home" to "home", "theme" to "theme <name>", "system" to "system",
    )

    /** Same tiers as AppRepository.score, enough for these cases. */
    private fun ranked(q: String) = apps.mapNotNull { a ->
        val l = a.lowercase()
        when {
            l == q -> a to 0
            l.startsWith(q) -> a to 1
            l.contains(q) -> a to 3
            else -> null
        }
    }

    private fun target(text: String, mode: String = InstantRun.ALL, aliases: Map<String, String> = emptyMap()) =
        InstantRun.target(text, mode, 2, ::ranked, { it }, commands, aliases, listOf("weather"))

    @Test fun uniqueAppPrefixLaunches() {
        assertEquals(Target.App("Grove"), target("grov"))
        assertEquals(Target.App("Groww"), target("grow"))
        assertNull("gro is ambiguous", target("gro"))
        assertEquals(Target.App("YouTube"), target("yo", InstantRun.APPS))
    }

    @Test fun minimumLength() {
        assertNull(target("y"))
    }

    @Test fun commandWinsOverSameNamedApp() {
        assertEquals(Target.Command("settings"), target("setti"))
        assertNull("settings and setup", target("set"))
    }

    @Test fun appsModeIgnoresCommandsAndNeverLaunchesOverThem() {
        assertNull(target("setti", InstantRun.APPS))
        assertNull(target("hel", InstantRun.APPS))
    }

    @Test fun offDoesNothing() {
        assertNull(target("grov", InstantRun.OFF))
    }

    @Test fun sensitiveAndArgumentCommandsNeedEnter() {
        assertNull("call is sensitive", target("call"))
        assertNull("theme needs an argument", target("them"))
        assertEquals(Target.Command("help"), target("he"))
    }

    @Test fun aliasesFollowTheirExpansion() {
        assertEquals(Target.Command("st"), target("st", aliases = mapOf("st" to "settings")))
        assertFalse(InstantRun.canRun("mom", commands, mapOf("mom" to "call mom"), emptyList()))
        assertTrue(InstantRun.canRun("s", commands, mapOf("s" to "settings"), emptyList()))
        assertFalse("chained aliases are followed", InstantRun.canRun("a", commands, mapOf("a" to "b", "b" to "clear all"), emptyList()))
        assertFalse("alias cycles are refused", InstantRun.canRun("a", commands, mapOf("a" to "b", "b" to "a"), emptyList()))
    }

    @Test fun historyLines() {
        assertTrue(InstantRun.canRun("settings", commands, emptyMap(), emptyList()))
        assertTrue(InstantRun.canRun("theme gruvbox", commands, emptyMap(), emptyList()))
        assertTrue("an app name", InstantRun.canRun("grove", commands, emptyMap(), emptyList()) { it == "grove" })
        assertFalse("unknown words are not apps by default", InstantRun.canRun("grove", commands, emptyMap(), emptyList()))
        assertFalse("synonym of a sensitive command", InstantRun.canRun("cls", commands, emptyMap(), emptyList()))
        assertFalse("synonym of a sensitive command", InstantRun.canRun("reboot", commands, emptyMap(), emptyList()))
        assertFalse("optional-argument commands wait for Enter", InstantRun.canRun("setup", commands, emptyMap(), emptyList()))
        assertTrue(InstantRun.isSensitive("notifications-feed clear"))
        assertFalse(InstantRun.canRun("call mom", commands, emptyMap(), emptyList()))
        assertFalse(InstantRun.canRun("work off", commands, emptyMap(), emptyList()))
        assertFalse(InstantRun.canRun("weather london", commands, emptyMap(), listOf("weather")))
    }

    @Test fun scriptsAndStatusAreSensitive() {
        assertTrue(InstantRun.isSensitive("scripts rm hn"))
        assertTrue(InstantRun.isSensitive("status clear all"))
    }

    @Test fun aliasesCantCarrySensitiveCommandsPastTheCheck() {
        val aliases = mapOf("c" to "clear", "x" to "c all", "w" to "weather")
        assertTrue(InstantRun.isSensitive("c", aliases))
        assertTrue(InstantRun.isSensitive("x", aliases))
        assertFalse(InstantRun.isSensitive("w london", aliases))
        assertFalse(InstantRun.isSensitive("help", aliases))
        // A cycle deeper than the dispatcher expands is refused.
        assertTrue(InstantRun.isSensitive("a", mapOf("a" to "b", "b" to "a")))
    }
}
