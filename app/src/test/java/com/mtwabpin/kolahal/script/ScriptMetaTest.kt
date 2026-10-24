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

    @Test fun anEmptyDescTakesNothingFromTheNextLine() {
        assertNull(ScriptMeta.description("-- @desc\nlocal x = 1"))
        assertNull(ScriptMeta.description("-- @desc   \nlocal x = 1"))
        assertEquals("tabs too", ScriptMeta.description("--\t@desc\ttabs too"))
    }

    @Test fun allEventsAccepted() =
        assertEquals(ScriptMeta.EVENTS, ScriptMeta.events("// @on " + ScriptMeta.EVENTS.reversed().joinToString(",")))

    @Test fun usageFromDirective() {
        assertEquals("xkcd [random|<number>]", ScriptMeta.usage("// @usage [random|<number>]\nlog(argline);", "xkcd"))
        assertEquals("the name in front is optional", "xkcd random", ScriptMeta.usage("-- @usage xkcd random", "xkcd"))
        assertEquals("empty: no arguments", "quote", ScriptMeta.usage("// @usage\nif (args.length === 0) notify('a', 'b');", "quote"))
        assertEquals("doesn't run into the next line", "ip", ScriptMeta.usage("-- @usage\n-- @desc public IP", "ip"))
    }

    @Test fun usesDirective() {
        assertEquals(setOf("notifications", "clipboard"), ScriptMeta.uses("-- @uses notifications, clipboard\nlog(1)"))
        assertEquals(setOf("lan"), ScriptMeta.uses("// @uses LAN camera\n"))
        assertTrue("none declared", ScriptMeta.uses("-- @command\nlog(1)").isEmpty())
    }

    @Test fun usageWithoutDirective() {
        assertEquals("never reads its arguments", "ip", ScriptMeta.usage("-- @command\nlog(http.get('x').body)", "ip"))
        assertNull("reads them: unknown", ScriptMeta.usage("-- @command\nlog(argline)", "w"))
        assertNull(ScriptMeta.usage("// @command\nlog(args[0]);", "w"))
    }

    /** Do Not Disturb and the work profile are only for scripts that ask. */
    @Test fun dndAndWorkAreAskedFor() {
        assertEquals(setOf("dnd", "work"), ScriptMeta.uses("-- @uses dnd, work\n"))
        assertEquals(setOf("dnd"), ScriptMeta.uses("// @uses dnd\n"))
    }

    /** A timed run waits for a network only when the code makes requests or reads feeds. */
    @Test fun goingOnlineIsHttpOrRss() {
        assertTrue(ScriptMeta.goesOnline("local r = http.get(url)"))
        assertTrue(ScriptMeta.goesOnline("const f = await rss.fetch(u);"))
        assertFalse(ScriptMeta.goesOnline("-- night-dnd.lua — No network.\nif device.dnd() == 'off' then device.setDnd('on') end"))
        assertFalse(ScriptMeta.goesOnline("log(\"see https://example.com\")"))
    }
}
