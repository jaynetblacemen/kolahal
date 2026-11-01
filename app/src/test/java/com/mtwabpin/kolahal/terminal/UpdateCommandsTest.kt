package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCommandsTest {
    @Test fun aVersionIsThreeNumbers() {
        assertEquals(listOf(0, 4, 10), UpdateCommands.parseVersion("0.4.10"))
        for (version in listOf("", "0.4", "0.4.10.1", "0.4.10-dev", "v0.4.10", "a.b.c", "0.4.99999999999")) {
            assertNull(version, UpdateCommands.parseVersion(version))
        }
    }

    @Test fun versionsCompareByNumberNotByText() {
        fun compare(a: String, b: String) = UpdateCommands.compareVersions(UpdateCommands.parseVersion(a)!!, UpdateCommands.parseVersion(b)!!)
        assertTrue(compare("0.4.10", "0.4.9") > 0)
        assertTrue(compare("0.4.9", "0.4.10") < 0)
        assertTrue(compare("1.0.0", "0.99.99") > 0)
        assertEquals(0, compare("0.4.10", "0.4.10"))
    }

    /** Play allows no update but its own. */
    @Test fun thePlayBuildDoesntOfferIt() {
        assertEquals(!BuildConfig.SELF_UPDATE, "update" in CommandCatalog.UNAVAILABLE)
    }

    /** Only when you ask: not by a prefix or from a script, with or without a pipe. */
    @Test fun itsSensitive() {
        for (line in listOf("update", "update install", "update|head", "update install | head")) {
            assertTrue(line, InstantRun.isSensitive(line))
        }
    }
}
