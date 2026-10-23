package com.mtwabpin.kolahal.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The home screen's layout rules, on a frame smaller than the phone. */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val config = Config.get(app)

    @After fun tearDown() = Keys.ALL.forEach(config::reset)

    private fun show(width: Dp, height: Dp): TerminalViewModel {
        // On the main thread, as the activity makes it.
        lateinit var vm: TerminalViewModel
        compose.runOnUiThread { vm = TerminalViewModel(app, androidx.lifecycle.SavedStateHandle()) }
        compose.setContent {
            // The frame is all the room there is: the keyboard (the emulator shows one for the
            // focused prompt) and the system bars are taken out of the window, not of the frame.
            KolahalTheme {
                Box(Modifier.size(width, height).testTag("frame").consumeWindowInsets(WindowInsets.safeDrawing)) {
                    TerminalScreen(vm, onRequestNotifications = {}, onPickFolder = {})
                }
            }
        }
        return vm
    }

    private fun type(vm: TerminalViewModel, text: String) {
        compose.runOnIdle { vm.onInputChange(TextFieldValue(text, TextRange(text.length))) }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    /**
     * The suggestion list is capped at 40% of the window, which can be more than the room left
     * above the key bar and prompt: the list must give way, not push them out (21 October 2026).
     */
    @Test fun longSuggestionListKeepsKeyBarAndPromptInside() {
        config.set(Keys.SUGGESTIONS, "30")
        config.set(Keys.KEYBAR_SHOW, "on")
        config.set(Keys.KEYBAR_AUTO, "off")
        val vm = show(360.dp, 320.dp)
        type(vm, "s")
        compose.onNodeWithTag("suggestions").assertExists()
        val frame = compose.onNodeWithTag("frame").getUnclippedBoundsInRoot()
        // Pushed out, or squeezed to nothing at the bottom: both lose the prompt.
        val prompt = compose.onNodeWithTag("prompt", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the prompt ends at ${prompt.bottom}, the frame at ${frame.bottom}", prompt.bottom <= frame.bottom)
        assertTrue("the prompt is ${prompt.bottom - prompt.top} tall", prompt.bottom - prompt.top >= 16.dp)
        val rows = compose.onAllNodesWithTag("keybar", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val bottom = with(compose.density) { row.boundsInRoot.bottom.toDp() }
            assertTrue("a key bar row ends at $bottom, the frame at ${frame.bottom}", bottom <= frame.bottom && row.size.height > 0)
        }
    }

    @Test fun headerIsOneLineWhileTyping() {
        val vm = show(360.dp, 700.dp)
        type(vm, "s")
        compose.onNodeWithTag("header-compact").assertExists()
        compose.onNodeWithTag("header").assertDoesNotExist()
    }

    /** header.show off: no header, full or one-line, and it comes back when turned on. */
    @Test fun headerCanBeTurnedOff() {
        config.set(Keys.HEADER_SHOW, "off")
        val vm = show(360.dp, 700.dp)
        compose.onNodeWithTag("header").assertDoesNotExist()
        compose.onNodeWithTag("header-compact").assertDoesNotExist()
        // While typing it would be the one-line header: none either.
        type(vm, "s")
        compose.onNodeWithTag("header-compact").assertDoesNotExist()
        compose.runOnIdle { config.set(Keys.HEADER_SHOW, "on") }
        compose.onNodeWithTag("header-compact").assertExists()
    }

    /** log.taller-taps: a line you can tap is taller than plain output, unless it's turned off. */
    @Test fun tappableLinesAreTallerUnlessTurnedOff() {
        val logs = LogRepository.get(app)
        val command = logs.add(com.mtwabpin.kolahal.data.LogKind.COMMAND, "lh echo")
        val output = logs.add(com.mtwabpin.kolahal.data.LogKind.OUTPUT, "lh plain")
        try {
            Term.lineHeight = 25
            Term.tallerTaps = true
            show(360.dp, 700.dp)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("line-$output").fetchSemanticsNodes().isNotEmpty() }
            fun height(id: Long) = compose.onNodeWithTag("line-$id").getUnclippedBoundsInRoot().let { it.bottom - it.top }
            assertEquals(35f, height(command).value, 1f)
            assertEquals(25f, height(output).value, 1f)
            compose.runOnIdle { Term.tallerTaps = false }
            assertEquals(25f, height(command).value, 1f)
            // Set shorter than a line of text with its padding: as short as the text allows, tappable or not.
            compose.runOnIdle { Term.lineHeight = 10 }
            assertTrue(height(output).value < 20f)
            assertEquals(height(output).value, height(command).value, 0.5f)
        } finally {
            Term.tallerTaps = Keys.LOG_TALLER_TAPS.default
            Term.lineHeight = Keys.LOG_LINE_HEIGHT.default
            logs.delete(command)
            logs.delete(output)
        }
    }

    /** The header's ⚙ shows no word; a screen reader still has one to say. */
    @Test fun headerSettingsHasANameForScreenReaders() {
        lateinit var vm: TerminalViewModel
        compose.runOnUiThread { vm = TerminalViewModel(app, androidx.lifecycle.SavedStateHandle()) }
        // As Settings shows home, not typing: the emulator's keyboard, up or not, would otherwise
        // decide between the full header and the one-line one (which has no ⚙).
        compose.setContent {
            KolahalTheme {
                Box(Modifier.size(360.dp, 700.dp)) {
                    TerminalScreen(vm, onRequestNotifications = {}, onPickFolder = {}, preview = TerminalViewModel.Peek(null, typing = false))
                }
            }
        }
        compose.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test fun dollarOpensQuickActions() {
        show(360.dp, 700.dp)
        compose.onNodeWithTag("prompt-quick").performClick()
        compose.onNodeWithText("Quick actions").assertIsDisplayed()
        compose.onNodeWithText("Unlocks this week").assertIsDisplayed()
    }

    /**
     * suggestions.wrap: a setting's description is cut to one line, or (on) shown whole over more
     * lines in its own column, its first line level with the setting's name.
     */
    @Test fun wrappedDescriptionsShowWhole() {
        val vm = show(360.dp, 700.dp)
        type(vm, "config set header.au")
        val help = compose.onNodeWithText(Keys.HEADER_AUTOHIDE.help, substring = true, useUnmergedTree = true)
        assertEquals(1, help.layout().lineCount)

        compose.runOnIdle { config.set(Keys.SUGGESTIONS_WRAP, "on") }
        val wrapped = help.layout()
        assertTrue("${wrapped.lineCount} lines", wrapped.lineCount > 1)
        val name = compose.onNodeWithText(Keys.HEADER_AUTOHIDE.key, substring = true, useUnmergedTree = true)
        val nameBounds = name.getUnclippedBoundsInRoot()
        val helpBounds = help.getUnclippedBoundsInRoot()
        assertTrue("the description starts at ${helpBounds.left}, the name ends at ${nameBounds.right}", helpBounds.left >= nameBounds.right)
        with(compose.density) {
            val nameBaseline = nameBounds.top + name.layout().firstBaseline.toDp()
            val helpBaseline = helpBounds.top + wrapped.firstBaseline.toDp()
            assertEquals(nameBaseline.value, helpBaseline.value, 1f)
        }
    }

    /** gesture.swipe-up is none by default, which turns swiping up off; an action turns it on. */
    @Test fun swipeUpRunsOnlyWhenGivenAnAction() {
        val logs = LogRepository.get(app)
        val mark = "swiped up ${System.nanoTime()}"
        show(360.dp, 700.dp)
        fun swipe() = compose.onNodeWithTag("prompt-quick").performTouchInput {
            swipeUp(startY = height - 1f, endY = height - 1f - 120.dp.toPx(), durationMillis = 200)
        }
        fun ran() = logs.recent(50).any { it.text == mark }

        swipe()
        compose.waitForIdle()
        Thread.sleep(500)
        assertFalse("swiping up ran its action while it was none", ran())

        compose.runOnIdle { config.set(Keys.GESTURE_SWIPE_UP, "echo $mark") }
        swipe()
        compose.waitUntil(5_000) { ran() }
    }
}
