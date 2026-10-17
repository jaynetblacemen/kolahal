package io.github.rscipher001.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailMathTest {
    @Test fun letterOfBucketsByFirstLetter() {
        assertEquals('M', RailMath.letterOf("Maps"))
        assertEquals('M', RailMath.letterOf("maps"))
        assertEquals('E', RailMath.letterOf("Éclair"))
        assertEquals('#', RailMath.letterOf("1Password"))
        assertEquals('#', RailMath.letterOf("Телеграм"))
        assertEquals('#', RailMath.letterOf(""))
        assertEquals('W', RailMath.letterOf("  ‎WhatsApp"))
    }

    private data class A(val label: String, val pinned: Boolean = false, val profile: Int = 0)

    @Test fun bucketsPinnedFirstThenLabelThenProfile() {
        val apps = listOf(A("Chrome", profile = 1), A("Calendar"), A("Chrome"), A("Camera", pinned = true), A("2048"))
        val b = RailMath.buckets(apps, { it.label }, { it.pinned }, { it.profile })
        assertEquals(listOf(A("Camera", pinned = true), A("Calendar"), A("Chrome"), A("Chrome", profile = 1)), b['C'])
        assertEquals(listOf(A("2048")), b['#'])
        assertNull(b['D'])
    }

    @Test fun letterIndexClamps() {
        assertEquals(0, RailMath.letterIndexAt(-5f, 10f, 27))
        assertEquals(0, RailMath.letterIndexAt(9.9f, 10f, 27))
        assertEquals(1, RailMath.letterIndexAt(10f, 10f, 27))
        assertEquals(26, RailMath.letterIndexAt(1000f, 10f, 27))
    }

    @Test fun popupCentresOnFingerInsideContainer() {
        assertEquals(400f - 100f, RailMath.popupTop(400f, 50f, 4, 1000f))
        assertEquals(0f, RailMath.popupTop(20f, 50f, 4, 1000f))
        assertEquals(800f, RailMath.popupTop(990f, 50f, 4, 1000f))
        // Taller than the container: pinned to the top.
        assertEquals(0f, RailMath.popupTop(300f, 50f, 30, 1000f))
    }

    @Test fun popupRowUnderFinger() {
        assertEquals(0, RailMath.popupRowAt(300f, 300f, 50f, 4))
        assertEquals(1, RailMath.popupRowAt(350f, 300f, 50f, 4))
        assertEquals(3, RailMath.popupRowAt(499f, 300f, 50f, 4))
        assertNull(RailMath.popupRowAt(500f, 300f, 50f, 4))
        assertNull(RailMath.popupRowAt(299f, 300f, 50f, 4))
        assertNull(RailMath.popupRowAt(300f, 300f, 50f, 0))
    }

    @Test fun edgeScrollStaysInRange() {
        assertEquals(-1, RailMath.edgeDirection(10f, 300f, 50f, 4))
        assertEquals(1, RailMath.edgeDirection(500f, 300f, 50f, 4))
        assertEquals(0, RailMath.edgeDirection(320f, 300f, 50f, 4))
        assertEquals(0, RailMath.scrolled(0, -1, 12, 8))
        assertEquals(4, RailMath.scrolled(4, 1, 12, 8))
        assertEquals(3, RailMath.scrolled(2, 1, 12, 8))
        assertEquals(0, RailMath.scrolled(0, 1, 5, 8))
    }

    @Test fun waveIsSymmetricAndFlat() {
        assertEquals(RailMath.waveScale(-1), RailMath.waveScale(1))
        assertEquals(1f, RailMath.waveScale(5))
        assert(RailMath.waveScale(0) > RailMath.waveScale(1))
    }

    @Test fun completionHelpForCommandsAndKeys() {
        assertEquals("show this list", completionHelp("help", "help "))
        assertEquals(io.github.rscipher001.launcher.data.Keys.SELECTOR_RAIL.help, completionHelp("selector.rail", "config set selector.rail "))
        assertNull(completionHelp("on", "config set selector.rail on"))
    }
}
