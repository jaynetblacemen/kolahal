package com.mtwabpin.kolahal.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Defaults the maintainer chose; a change here changes every phone that never set them. */
class ConfigDefaultsTest {
    @Test fun chosenDefaults() {
        // 30 again in 0.3.0, after 5 from 0.2.3.
        assertEquals(30, Keys.SUGGESTIONS.default)
        assertEquals(false, Keys.SUGGESTIONS_WRAP.default)
        // Swiping up is off until it is given an action: it fires by accident easily.
        assertEquals("none", Keys.GESTURE_SWIPE_UP.default)
        assertEquals(false, Keys.HEADER_DEVICE.default)
    }
}
