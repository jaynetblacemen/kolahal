package io.github.rscipher001.launcher.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the hot paths of a normal session: cold start, typing into the prompt (suggestions),
 * running commands, scrolling the log and opening the settings screen.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = InstrumentationRegistry.getArguments().getString("targetAppId") ?: "io.github.rscipher001.launcher",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        fun type(text: String) {
            device.executeShellCommand("input text $text")
            device.waitForIdle()
        }
        fun enter() {
            device.executeShellCommand("input keyevent ENTER")
            device.waitForIdle()
        }

        // Prompt suggestions while typing an app name, then clear it.
        type("se")
        type("t")
        device.executeShellCommand("input keyevent ESCAPE")
        // Commands with long output and the log list.
        type("help"); enter()
        type("apps"); enter()
        type("=2*21"); enter()
        val w = device.displayWidth / 2
        val h = device.displayHeight
        device.swipe(w, h / 3, w, h * 2 / 3, 20)
        device.swipe(w, h * 2 / 3, w, h / 3, 20)
        // Settings screen (Compose Material 3) and back.
        type("settings"); enter()
        device.waitForIdle()
        device.swipe(w, h * 2 / 3, w, h / 4, 20)
        device.pressBack()
        device.waitForIdle()
    }
}
