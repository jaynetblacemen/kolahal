package com.mtwabpin.kolahal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings saved under the names from before 0.3.0 keep their values under the new names. */
class ConfigNamesTest {
    @Test fun oldNamesMoveToNewOnes() {
        val saved = mapOf("theme" to "amber", "log.lineheight" to "30", "keybar2" to "TAB", "font.size" to "16")
        assertEquals(
            mapOf("theme.name" to "amber", "log.line-height" to "30", "keybar.row2" to "TAB", "font.size" to "16"),
            Keys.migrate(saved),
        )
    }

    @Test fun aValueUnderTheNewNameWins() {
        assertEquals(mapOf("theme.name" to "dracula"), Keys.migrate(mapOf("theme" to "amber", "theme.name" to "dracula")))
    }

    @Test fun swipeUpSwitchFoldsIntoItsAction() {
        assertEquals(mapOf("gesture.swipe-up" to "apps"), Keys.migrate(mapOf("gesture.swipeup.on" to "on")))
        assertEquals(mapOf("gesture.swipe-up" to "quick"), Keys.migrate(mapOf("gesture.swipeup.on" to "on", "gesture.swipeup" to "quick")))
        // An action chosen while the gesture was off stays off.
        assertEquals(emptyMap<String, String>(), Keys.migrate(mapOf("gesture.swipeup" to "quick")))
        assertEquals(emptyMap<String, String>(), Keys.migrate(mapOf("gesture.swipeup.on" to "off")))
    }

    @Test fun everyOldNamePointsAtARealSetting() {
        for ((old, new) in Keys.RENAMED) {
            assertEquals(old, new, Keys.byName(old)?.key)
            assertNull("$old is still a setting of its own", Keys.ALL.firstOrNull { it.key == old })
        }
    }

    @Test fun noNameIsBothASettingAndAGroup() {
        val names = Keys.ALL.map { it.key }
        for (name in names) assertTrue("$name is also a group", names.none { it.startsWith("$name.") })
    }
}
