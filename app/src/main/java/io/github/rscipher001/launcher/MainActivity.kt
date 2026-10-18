package io.github.rscipher001.launcher

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rscipher001.launcher.terminal.TerminalViewModel
import io.github.rscipher001.launcher.terminal.Screen
import io.github.rscipher001.launcher.terminal.UiEvent
import io.github.rscipher001.launcher.data.Backup
import io.github.rscipher001.launcher.ui.FeedsScreen
import io.github.rscipher001.launcher.ui.SettingsScreen
import io.github.rscipher001.launcher.ui.ScriptsScreen
import io.github.rscipher001.launcher.ui.SetupScreen
import io.github.rscipher001.launcher.ui.KolahalTheme
import io.github.rscipher001.launcher.ui.Palettes
import io.github.rscipher001.launcher.ui.Term
import io.github.rscipher001.launcher.ui.TermFonts
import androidx.compose.ui.text.font.FontFamily
import io.github.rscipher001.launcher.data.Keys
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toArgb
import io.github.rscipher001.launcher.ui.ScriptEditor
import io.github.rscipher001.launcher.ui.TerminalScreen
import io.github.rscipher001.launcher.ui.WidgetPickerScreen
import io.github.rscipher001.launcher.ui.WidgetsScreen
import io.github.rscipher001.launcher.widgets.WidgetBinder
import io.github.rscipher001.launcher.widgets.WidgetHost
import io.github.rscipher001.launcher.widgets.WidgetLocation

class MainActivity : ComponentActivity() {
    private val vm: TerminalViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onNotificationPermission(it) }

    private val contactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onContactsPermission(it) }

    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onCalendarPermission(it) }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onScriptFolderPicked(it) }

    private val exportBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { vm.exportBackup(it) }

    private val importBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importBackup(it) }

    private val fontPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importFont(it) }

    // Font MIME types are rarely set right by file managers; importFont checks the name and header.
    private fun launchFontPicker() =
        fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*"))

    // Many file managers label .lua / .js as octet-stream or text, so accept anything and filter by name.
    private val importScripts =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importScriptFiles(it) }

    private val exportScript =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { vm.finishExportScript(it) }

    /** Add-a-widget flow (bind permission + configure activity). */
    private val widgetBinder = WidgetBinder(this) { placed ->
        // Back to where the picker was opened from; widgets for the page land on the page.
        if (vm.screen.value == Screen.WIDGET_PICKER) vm.closeScreen()
        if (placed.location == WidgetLocation.PANEL && vm.screen.value != Screen.WIDGETS) vm.openScreen(Screen.WIDGETS)
    }

    override fun onStart() {
        super.onStart()
        WidgetHost.get(this).startListening()
    }

    override fun onStop() {
        super.onStop()
        WidgetHost.get(this).stopListening()
    }

    @Deprecated("Needed for AppWidgetHost.startAppWidgetConfigureActivityForResult, which reports here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        widgetBinder.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        applyTheme()
        // Theme follows settings without reading config state in composition (which would recompose
        // the whole tree on every settings change).
        lifecycleScope.launch { vm.config.version.collect { applyTheme() } }
        lifecycleScope.launch {
            vm.events.collect {
                when (it) {
                    UiEvent.RequestNotificationPermission -> requestNotifications()
                    UiEvent.RequestContactsPermission -> contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    UiEvent.RequestCalendarPermission -> calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                    UiEvent.PickFont -> launchFontPicker()
                    else -> Unit
                }
            }
        }
        setContent {
            KolahalTheme {
                val editing by vm.editing.collectAsStateWithLifecycle()
                val script = editing
                // A launcher never "goes back" off the home screen; the editor installs its own handler.
                BackHandler(enabled = script == null) { }
                if (script != null) {
                    val jumpLine by vm.editLine.collectAsStateWithLifecycle()
                    ScriptEditor(
                        script = script,
                        jumpToLine = jumpLine,
                        onSave = { vm.saveScript(script, it) },
                        onRun = { vm.runScript(script, it) },
                        onClose = vm::closeEditor,
                    )
                } else {
                    val screen by vm.screen.collectAsStateWithLifecycle()
                    when (screen) {
                        Screen.SETTINGS -> SettingsScreen(
                            vm,
                            onExportBackup = { exportBackup.launch(Backup.FILE_NAME) },
                            onImportBackup = { importBackup.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                            onImportFont = ::launchFontPicker,
                        )
                        Screen.SCRIPTS -> ScriptsScreen(
                            vm,
                            onImportFiles = { importScripts.launch(arrayOf("*/*")) },
                            onExportScript = { s ->
                                vm.beginExportScript(s)
                                exportScript.launch("${s.name}.${s.lang.ext}")
                            },
                        )
                        Screen.FEEDS -> FeedsScreen(vm, onRequestNotifications = ::requestNotifications)
                        Screen.SETUP -> SetupScreen(vm)
                        Screen.WIDGETS -> WidgetsScreen(vm, onReAdd = { entry, w -> widgetBinder.add(entry, w.location, w.key) })
                        Screen.WIDGET_PICKER -> WidgetPickerScreen(vm, onPick = { widgetBinder.add(it) })
                        Screen.TERMINAL -> HomePager(vm) {
                            TerminalScreen(
                                vm,
                                onRequestNotifications = ::requestNotifications,
                                onPickFolder = { pickFolder.launch(null) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
        io.github.rscipher001.launcher.apps.PackageEvents.get(this).recheck()
    }

    override fun onPause() {
        super.onPause()
        vm.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Same check as Launcher3: only a Home press while home is showing resets the prompt;
        // returning from another app keeps whatever was typed.
        val alreadyHome = hasWindowFocus() &&
            (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
        if (intent.hasCategory(Intent.CATEGORY_HOME) && alreadyHome) vm.onHomePressed()
    }

    /** `statusbar`: show, hide (status bar only) or immersive (status and navigation bars). */
    private fun applyBars() {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        val status = androidx.core.view.WindowInsetsCompat.Type.statusBars()
        val nav = androidx.core.view.WindowInsetsCompat.Type.navigationBars()
        // A swipe from the edge shows hidden bars for a moment, then they hide again.
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        when (vm.config[Keys.STATUS_BAR]) {
            "hide" -> { controller.hide(status); controller.show(nav) }
            "immersive" -> controller.hide(status or nav)
            else -> controller.show(status or nav)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and other apps can bring the bars back; re-apply when home regains focus.
        if (hasFocus) applyBars()
    }

    private fun applyTheme() {
        Term.palette = Palettes.named(vm.config[Keys.THEME])
        Term.fontSize = vm.config[Keys.FONT_SIZE]
        val font = vm.config[Keys.FONT]
        val family = TermFonts.family(this, font)
        if (family == null) vm.onFontUnavailable(font)
        Term.fontFamily = family ?: FontFamily.Monospace
        Term.lineHeight = vm.config[Keys.LOG_LINE_HEIGHT]
        Term.chipHeight = vm.config[Keys.CHIP_HEIGHT]
        window.decorView.setBackgroundColor(Term.bg.toArgb())
        applyBars()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * `widgets.page`: the terminal with a widgets page beside it (left or right). Swipe sideways on
 * home to reach it; Back or Home returns to the terminal. A swipe that starts on a log line still
 * belongs to the line (copy / delete), so the page swipe works from the header, empty space or
 * the prompt.
 */
@androidx.compose.runtime.Composable
private fun HomePager(vm: TerminalViewModel, terminal: @androidx.compose.runtime.Composable () -> Unit) {
    val version by vm.config.version.collectAsStateWithLifecycle()
    val side = androidx.compose.runtime.remember(version) { vm.config[Keys.WIDGETS_PAGE] }
    if (side == "off") {
        terminal()
        return
    }
    val terminalPage = if (side == "left") 1 else 0
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = terminalPage) { 2 }
    val homePresses by vm.homePresses.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(homePresses, side) {
        if (pager.currentPage != terminalPage) pager.animateScrollToPage(terminalPage)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    BackHandler(enabled = pager.currentPage != terminalPage) {
        scope.launch { pager.animateScrollToPage(terminalPage) }
    }
    // The pager's own scrolling grabs touches before its children, which starved the app selector.
    // Our detector runs after the children instead: a swipe the selector or a log line already
    // claimed (consumed) never turns the page.
    androidx.compose.foundation.pager.HorizontalPager(
        state = pager,
        beyondViewportPageCount = 1,
        userScrollEnabled = false,
        key = { it },
        modifier = androidx.compose.ui.Modifier.pointerInput(pager) {
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onDragEnd = {
                    val v = tracker.calculateVelocity().x
                    val target = when {
                        v < -800f -> pager.currentPage + 1
                        v > 800f -> pager.currentPage - 1
                        else -> (pager.currentPage + pager.currentPageOffsetFraction).let { kotlin.math.round(it).toInt() }
                    }.coerceIn(0, 1)
                    scope.launch { pager.animateScrollToPage(target) }
                },
                onDragCancel = { scope.launch { pager.animateScrollToPage(pager.currentPage) } },
            ) { change, dx ->
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
                pager.dispatchRawDelta(-dx)
            }
        },
    ) { page ->
        if (page == terminalPage) terminal() else io.github.rscipher001.launcher.ui.WidgetPanel(vm)
    }
}
