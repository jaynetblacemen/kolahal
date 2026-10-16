package io.github.rscipher001.launcher.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptMetaTest {
    @Test fun luaSingleEvent() =
        assertEquals(listOf("unlock"), ScriptMeta.events("-- @on unlock\nlog('hi')"))

    @Test fun jsCommaSeparated() =
        assertEquals(listOf("charger", "unplug"), ScriptMeta.events("// @on charger, unplug\nlog('x');"))

    @Test fun multipleLinesMergeInCanonicalOrder() =
        assertEquals(
            listOf("unlock", "wifi", "notification"),
            ScriptMeta.events("-- @on notification\n  -- @on wifi unlock\n-- @on Unlock"),
        )

    @Test fun unknownEventsIgnoredButReported() {
        val code = "-- @on unlock, sunrise"
        assertEquals(listOf("unlock"), ScriptMeta.events(code))
        assertEquals(listOf("sunrise"), ScriptMeta.unknownEvents(code))
    }

    @Test fun noDirective() {
        assertTrue(ScriptMeta.events("-- @command\n-- @desc d\nlocal on = 1").isEmpty())
        assertTrue(ScriptMeta.events("// @online\n").isEmpty())
        assertTrue(ScriptMeta.events("log('-- @on unlock')").isEmpty())
    }

    @Test fun otherDirectivesStillParse() {
        val code = "-- @command\n-- @desc hello world\n-- @on boot"
        assertTrue(ScriptMeta.isCommand(code))
        assertEquals("hello world", ScriptMeta.description(code))
        assertEquals(listOf("boot"), ScriptMeta.events(code))
        assertFalse(ScriptMeta.isCommand("-- @on boot"))
    }

    @Test fun allEventsAccepted() =
        assertEquals(ScriptMeta.EVENTS, ScriptMeta.events("// @on " + ScriptMeta.EVENTS.reversed().joinToString(",")))
}
