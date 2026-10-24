package com.mtwabpin.kolahal.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.CrashReports
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.ConfigKey
import com.mtwabpin.kolahal.data.CustomThemes
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.terminal.Screen
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_THEME = "tb-test"

/** Settings: home showing through while a look setting changes, and the setting rows. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val config = Config.get(app)
    private lateinit var vm: TerminalViewModel

    @After fun tearDown() {
        Keys.ALL.forEach(config::reset)
        CrashReports.clear(app)
        CustomThemes.delete(app, TEST_THEME)
    }

    private fun crashReport(id: String, error: String) {
        val dir = java.io.File(app.filesDir, "crash").apply { mkdirs() }
        java.io.File(dir, "$id-crash.txt").writeText("Kolahal crash report\nKolahal\nAndroid\nWhen: x\nWhat: crash in test, thread main\n\n$error\n\tat a.b(c:1)\n")
    }

    /** Waits for text that appears after a read off the main thread (crash reports). */
    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }

    /** Settings open on [page] (null: the main page), on a phone-sized frame, as MainActivity shows it. */
    private fun show(page: String?) {
        compose.runOnUiThread {
            vm = TerminalViewModel(app, SavedStateHandle())
            vm.settingsCategory.value = page
        }
        compose.setContent {
            KolahalTheme {
                Box(Modifier.size(360.dp, 700.dp).consumeWindowInsets(WindowInsets.safeDrawing)) {
                    SettingsOverHome(vm, onExportBackup = {}, onImportBackup = {})
                }
            }
        }
    }

    private fun scrollTo(key: ConfigKey<*>) = compose.onNode(hasScrollToKeyAction()).performScrollToKey(key.key)

    /** Home's `$`: there only while home is drawn under Settings. */
    private val home get() = compose.onNodeWithTag("prompt-quick")

    @Test fun holdingTheEyeShowsHome() {
        show("look")
        home.assertDoesNotExist()
        val eye = compose.onNodeWithContentDescription("Hold to see home")
        eye.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(TerminalViewModel.Peek(null, typing = false), vm.peek.value)
        home.assertExists()
        compose.onNodeWithText("Let go to come back").assertIsDisplayed()

        eye.performTouchInput { up() }
        compose.waitForIdle()
        assertNull(vm.peek.value)
        // Home fades out as Settings comes back, then is gone.
        compose.mainClock.advanceTimeBy(PEEK_FADE_MS + 100L)
        home.assertDoesNotExist()
    }

    @Test fun aTapOnTheEyeSaysToHold() {
        show("typing")
        compose.onNodeWithContentDescription("Hold to see home").performClick()
        compose.onNodeWithText("Tap and hold the eye to see home").assertIsDisplayed()
        assertNull(vm.peek.value)
        home.assertDoesNotExist()
    }

    /**
     * prompt.height is a typing slider: while it is dragged, home shows under Settings as if typing,
     * at the height under the finger. The value is saved only when the finger lifts.
     */
    @Test fun draggingALookSliderShowsHomeAtThatValue() {
        show("typing")
        scrollTo(Keys.PROMPT_HEIGHT_TYPING)
        val slider = compose.onNodeWithTag("slider ${Keys.PROMPT_HEIGHT_TYPING.key}")
        slider.performTouchInput {
            down(center)
            // Past the end: the most, 72.
            repeat(8) { moveBy(Offset(width / 4f, 0f)) }
        }
        compose.waitForIdle()
        assertEquals(TerminalViewModel.Peek(Keys.PROMPT_HEIGHT_TYPING.key, typing = true), vm.peek.value)
        // Shown everywhere, saved nowhere yet.
        assertEquals(72, config[Keys.PROMPT_HEIGHT_TYPING])
        assertTrue(config.isDefault(Keys.PROMPT_HEIGHT_TYPING))
        // As if "s" were typed: in the prompt, with suggestions above it.
        compose.onNodeWithTag("prompt", useUnmergedTree = true).assert(hasText("s"))
        compose.onNodeWithTag("suggestions").assertExists()
        // The prompt line is 72 dp: the `$` fills it but for 2 dp above and below.
        val quick = home.getUnclippedBoundsInRoot()
        assertEquals(68f, (quick.bottom - quick.top).value, 1f)

        slider.performTouchInput { up() }
        compose.waitForIdle()
        assertNull(vm.peek.value)
        assertFalse(config.isDefault(Keys.PROMPT_HEIGHT_TYPING))
        assertEquals(72, config[Keys.PROMPT_HEIGHT_TYPING])
        // The "s" was only drawn: nothing was typed into the real prompt.
        assertEquals("", vm.input.text)
    }

    /** A slider found by searching peeks too, and the search results fade around it. */
    @Test fun aSliderFoundBySearchShowsHome() {
        show(null)
        compose.onNode(hasSetTextAction()).performTextInput(Keys.PROMPT_HEIGHT_TYPING.key)
        val slider = compose.onNodeWithTag("slider ${Keys.PROMPT_HEIGHT_TYPING.key}")
        slider.performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(-width / 4f, 0f)) }
        }
        compose.waitForIdle()
        assertEquals(TerminalViewModel.Peek(Keys.PROMPT_HEIGHT_TYPING.key, typing = true), vm.peek.value)
        home.assertExists()
        slider.performTouchInput { up() }
        compose.waitForIdle()
        assertNull(vm.peek.value)
        assertEquals(Keys.PROMPT_HEIGHT_TYPING.range.first, config[Keys.PROMPT_HEIGHT_TYPING])
    }

    /** The app selector has a page of its own, apart from Typing; its settings show once it is on. */
    @Test fun theAppSelectorHasItsOwnPage() {
        assertTrue(allCategories().first { it.id == "typing" }.keys.none { it.key.startsWith("selector.") })
        // All of them but the ones only `config set` changes for now (selector.aim, while it's tuned).
        assertTrue(Keys.ALL.filter { it.key.startsWith("selector.") && it !in Keys.CONFIG_ONLY }.all { it in allCategories().first { c -> c.id == "selector" }.keys })
        show("selector")
        compose.onNodeWithText(Keys.SELECTOR_RAIL.key, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(Keys.SELECTOR_LETTERS.key, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { config.set(Keys.SELECTOR_RAIL, "on") }
        scrollTo(Keys.SELECTOR_LETTERS)
        compose.onNodeWithText(Keys.SELECTOR_LETTERS.key, useUnmergedTree = true).assertExists()
    }

    /** A setting another one turns off (the key bar's rows while it is off) shows once it is on. */
    @Test fun settingsThatDontApplyStayHidden() {
        show("typing")
        scrollTo(Keys.KEYBAR_SHOW)
        compose.onNodeWithText(Keys.KEYBAR.key, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { config.set(Keys.KEYBAR_SHOW, "on") }
        scrollTo(Keys.KEYBAR)
        compose.onNodeWithText(Keys.KEYBAR.key, useUnmergedTree = true).assertExists()
    }

    /** Without the header its settings go, but the 24-hour clock stays: the log's times use it. */
    @Test fun headerOffHidesItsSettings() {
        show("home")
        scrollTo(Keys.HEADER_DATE)
        compose.onNodeWithText(Keys.HEADER_DATE.key, useUnmergedTree = true).assertExists()
        compose.runOnIdle { config.set(Keys.HEADER_SHOW, "off") }
        scrollTo(Keys.CLOCK_24H)
        compose.onNodeWithText(Keys.CLOCK_24H.key, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(Keys.HEADER_DATE.key, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(Keys.HEADER_AUTOHIDE.key, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun searchSkipsSettingsThatDontApply() {
        show(null)
        compose.onNode(hasSetTextAction()).performTextInput(Keys.SEARCH_URL.key)
        val row = hasText(Keys.SEARCH_URL.key) and !hasSetTextAction()
        compose.onNode(row, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { config.set(Keys.SEARCH_ENGINE, "custom") }
        compose.onNode(row, useUnmergedTree = true).assertExists()
    }

    /** About lists the crash reports kept on the phone; a tap offers email or delete, and delete takes that one. */
    @Test fun aboutCrashReportsCanBeDeletedOneByOne() {
        CrashReports.clear(app)
        crashReport("20261022-090000", "IllegalStateException: first sttest")
        crashReport("20261022-100000", "IllegalStateException: second sttest")
        show(null)
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("about")
        compose.onNodeWithText(VERSION_TEXT, substring = true).assertExists()
        waitForText("2 reports kept on this phone")
        compose.onNode(hasText("Crash reports") and hasText("2 reports kept on this phone")).performClick()
        waitForText("second sttest", substring = true)

        compose.onNodeWithText("second sttest", substring = true).performClick()
        compose.onNodeWithText("Email the developer").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("second sttest", substring = true).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("IllegalStateException: first sttest"), CrashReports.list(app).map { it.text().lines()[6] })
        compose.onNodeWithText("first sttest", substring = true).assertExists()
    }

    /** Your themes: made themes join the theme strip and the list, and open in the builder. */
    @Test fun yourThemesOpenInTheBuilder() {
        CustomThemes.save(app, TEST_THEME, Palettes.colorsOf(Palettes.amber))
        show("look")
        compose.onNode(hasText(TEST_THEME) and hasText("\$ ls")).assertExists()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("own-themes")
        compose.onNode(hasText(TEST_THEME) and hasText("Tap to change it")).performClick()
        assertEquals(Screen.THEME_BUILDER, vm.screen.value)
        assertEquals(TEST_THEME, vm.themeBuilderName.value)

        compose.runOnIdle { vm.closeScreen() }
        compose.onNodeWithText("Create a theme").performClick()
        assertEquals(Screen.THEME_BUILDER, vm.screen.value)
        assertNull(vm.themeBuilderName.value)
    }

    /** Home must not stay drawn under whatever comes next. */
    @Test fun peekEndsWhenSettingsGoesAway() {
        compose.runOnUiThread {
            vm = TerminalViewModel(app, SavedStateHandle())
            vm.peek(TerminalViewModel.Peek(null, typing = false))
            vm.openScreen(Screen.SCRIPTS)
            assertNull(vm.peek.value)
            vm.peek(TerminalViewModel.Peek(Keys.FONT_SIZE.key, typing = false))
            vm.onPause()
            assertNull(vm.peek.value)
        }
    }

    /** A changed setting shows a reset icon right after its name; a switch has none (switching it resets it). */
    @Test fun resetIconFollowsAChangedSettingsName() {
        config.set(Keys.LOG_DIM, "off")
        show("log")
        scrollTo(Keys.LOG_SPACING)
        val reset = compose.onNodeWithContentDescription("Reset to default")
        reset.assertDoesNotExist()

        compose.runOnIdle { config.set(Keys.LOG_SPACING, "20") }
        val icon = reset.assertIsDisplayed().getUnclippedBoundsInRoot()
        val name = compose.onNodeWithText(Keys.LOG_SPACING.key, useUnmergedTree = true).getUnclippedBoundsInRoot()
        // On the name's line, just after it.
        assertTrue("the icon starts at ${icon.left}, the name ends at ${name.right}", icon.left >= name.right && icon.left - name.right < 8.dp)
        val nameMiddle = (name.top + name.bottom) / 2
        assertTrue("the icon spans ${icon.top}–${icon.bottom}, the name's middle is at $nameMiddle", nameMiddle in icon.top..icon.bottom)

        reset.performClick()
        compose.waitForIdle()
        assertTrue(config.isDefault(Keys.LOG_SPACING))
        reset.assertDoesNotExist()
    }

    /**
     * The theme and font previews are plain rows, every card composed even off to the right: a lazy
     * row inside a Settings row crashed Compose on a phone while Settings scrolled (22 October 2026,
     * "LayoutNode … not found in RectList").
     */
    @Test fun lookPreviewsAreNotLazy() {
        show("look")
        compose.onNodeWithText(Keys.THEME.choices.last()).assertExists()
        scrollTo(Keys.FONT)
        compose.onNodeWithText(TermFonts.label(TermFonts.names.last { it != TermFonts.CUSTOM })).assertExists()
    }
}
