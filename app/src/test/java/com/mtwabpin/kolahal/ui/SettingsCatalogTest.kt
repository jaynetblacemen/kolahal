package com.mtwabpin.kolahal.ui

import com.mtwabpin.kolahal.data.ConfigKey
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings pages and the look settings that show home through them. */
class SettingsCatalogTest {
    @Test fun everyEyeIsOnARealPage() {
        val ids = allCategories().map { it.id }
        for (page in PEEK_PAGES.keys) assertTrue("no page called $page", page in ids)
    }

    @Test fun everyPeekSettingIsASliderOnAPage() {
        val keys = allCategories().flatMap { it.keys }
        for (name in PEEK_SLIDERS.keys) {
            val key = keys.firstOrNull { it.key == name }
            assertNotNull("$name is on no page", key)
            assertTrue("$name is not a slider", key is ConfigKey.Num)
        }
    }
}
