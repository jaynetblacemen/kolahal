package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Commands the Play build doesn't offer. Unit tests run on the debug build, which has every feature. */
class FeatureCommandsTest {
    @Test fun everyFeatureCommandIsACommand() {
        for ((_, names) in CommandProcessor.FEATURE_COMMANDS) {
            for (name in names) assertTrue("$name isn't a command", name in CommandProcessor.COMMAND_NAMES)
        }
    }

    @Test fun unavailableFollowsTheBuild() {
        assertEquals(!BuildConfig.LOCK_SERVICE, "lock" in CommandProcessor.UNAVAILABLE)
        assertEquals(!BuildConfig.NOTIFICATION_ACCESS, "notifs" in CommandProcessor.UNAVAILABLE)
        assertEquals(!BuildConfig.NOTIFICATION_ACCESS, "media" in CommandProcessor.UNAVAILABLE)
        assertEquals(!BuildConfig.CALL_LOG, "calls" in CommandProcessor.UNAVAILABLE)
        assertTrue("call" !in CommandProcessor.UNAVAILABLE)
    }
}
