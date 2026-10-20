package com.mtwabpin.kolahal.notifications

import android.app.Notification
import com.mtwabpin.kolahal.notifications.KolahalNotificationListener.Companion.isQuietRepost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietRepostTest {
    @Test fun newKeysAlwaysCount() {
        assertFalse(isQuietRepost(known = false, flags = Notification.FLAG_ONLY_ALERT_ONCE, progressMax = 100, indeterminate = true))
    }

    @Test fun alertOnceRepostIsQuiet() =
        assertTrue(isQuietRepost(known = true, flags = Notification.FLAG_ONLY_ALERT_ONCE, progressMax = 0, indeterminate = false))

    @Test fun progressRepostIsQuiet() {
        assertTrue(isQuietRepost(known = true, flags = 0, progressMax = 100, indeterminate = false))
        assertTrue(isQuietRepost(known = true, flags = 0, progressMax = 0, indeterminate = true))
    }

    @Test fun ordinaryRepostStillCounts() =
        assertFalse(isQuietRepost(known = true, flags = Notification.FLAG_AUTO_CANCEL, progressMax = 0, indeterminate = false))
}
