package com.mtwabpin.kolahal.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun usageFromDirective() {
        assertEquals("xkcd [random|<number>]", ScriptMeta.usage("// @usage [random|<number>]\nlog(argline);", "xkcd"))
        assertEquals("the name in front is optional", "xkcd random", ScriptMeta.usage("-- @usage xkcd random", "xkcd"))
        assertEquals("empty: no arguments", "quote", ScriptMeta.usage("// @usage\nif (args.length === 0) notify('a', 'b');", "quote"))
        assertEquals("doesn't run into the next line", "ip", ScriptMeta.usage("-- @usage\n-- @desc public IP", "ip"))
    }

    @Test fun usageWithoutDirective() {
        assertEquals("never reads its arguments", "ip", ScriptMeta.usage("-- @command\nlog(http.get('x').body)", "ip"))
        assertNull("reads them: unknown", ScriptMeta.usage("-- @command\nlog(argline)", "w"))
        assertNull(ScriptMeta.usage("// @command\nlog(args[0]);", "w"))
    }
}
