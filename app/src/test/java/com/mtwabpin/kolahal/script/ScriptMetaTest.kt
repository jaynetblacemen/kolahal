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

    @Test fun optionsDirective() {
        val code = """
            -- @option city London The city to show
            -- @option units metric|imperial Units for temperatures
            -- @option greeting "good morning" How it says hello
            -- @option empty "" Left blank
            -- @option bare
            -- @option city Paris A second line of a name is ignored
            -- @option 9lives x Not a name
        """.trimIndent()
        assertEquals(
            listOf(
                ScriptMeta.Option("city", "London", emptyList(), "The city to show"),
                ScriptMeta.Option("units", "metric", listOf("metric", "imperial"), "Units for temperatures"),
                ScriptMeta.Option("greeting", "good morning", emptyList(), "How it says hello"),
                ScriptMeta.Option("empty", "", emptyList(), "Left blank"),
                ScriptMeta.Option("bare", "", emptyList(), ""),
            ),
            ScriptMeta.options(code),
        )
        assertEquals(listOf("x"), ScriptMeta.options("// @option x 1").map { it.name })
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

    /** The internet for requests, feeds, links and pictures; the header line for status; notifications for notify. */
    @Test fun whatTheCodeUsesIsReadFromIt() {
        assertTrue(ScriptMeta.usesInternet("local r = http.get(u)"))
        assertTrue(ScriptMeta.usesInternet("local h = http\nh.get(u)"))
        assertTrue(ScriptMeta.usesInternet("const f = await rss.fetch(u);"))
        assertTrue(ScriptMeta.usesInternet("log(t, nil, { url = link })"))
        assertTrue(ScriptMeta.usesInternet("log(t, \"\", { image: pic });"))
        assertFalse(ScriptMeta.usesInternet("-- @uses dnd\ndevice.setDnd('on')\nlog('see https://example.com')"))
        // Commands can hand words to a site (a search).
        assertTrue(ScriptMeta.usesInternet("-- @uses commands\nlauncher.run('yt ' .. args[1])"))

        assertTrue(ScriptMeta.setsHeader("status(\"HN: 3 new\")"))
        assertTrue(ScriptMeta.setsHeader("status(nil)"))
        assertTrue(ScriptMeta.setsHeader("if (line) status(`Sun ${'$'}{rise}`);"))
        assertFalse(ScriptMeta.setsHeader("local r = http.get(u)\nif r.status ~= 200 then return end"))
        assertFalse(ScriptMeta.setsHeader("const ok = res.status === 200;"))

        assertTrue(ScriptMeta.postsNotifications("notify(\"Battery\", \"low\")"))
        assertFalse(ScriptMeta.postsNotifications("-- @notify\nlog(\"no notification\")"))
        assertFalse(ScriptMeta.postsNotifications("if s.notify then log(1) end"))
    }

    /** Only what the code uses has a switch, and only that is allowed: no internet or header line for one that uses neither. */
    @Test fun aScriptMayOnlyDoWhatItUses() {
        val offline = "-- @uses dnd\n-- @on unlock\ndevice.setDnd('on')"
        assertEquals(listOf("dnd", "unlock"), ScriptAccess.items(offline))
        val quiet = ScriptAccess.grants(offline, mapOf("dnd" to true))
        assertFalse(quiet.allows(ScriptAccess.INTERNET))
        assertFalse(quiet.allows(ScriptAccess.HEADER))
        assertTrue(quiet.allows("dnd"))
        assertTrue(quiet.allows("unlock"))

        val online = "local r = http.get(u)\nstatus(r.body)"
        assertEquals(listOf(ScriptAccess.INTERNET, ScriptAccess.HEADER), ScriptAccess.items(online))
        assertTrue(ScriptAccess.grants(online, emptyMap()).allows(ScriptAccess.INTERNET))
        assertFalse(ScriptAccess.grants(online, mapOf(ScriptAccess.INTERNET to false)).allows(ScriptAccess.INTERNET))
        assertTrue(ScriptAccess.grants(online, emptyMap()).allows(ScriptAccess.HEADER))
    }
}
