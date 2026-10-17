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
                        Screen.TERMINAL -> TerminalScreen(
                            vm,
                            onRequestNotifications = ::requestNotifications,
                            onPickFolder = { pickFolder.launch(null) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
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
        Term.lineHeight = vm.config[Keys.LOG_LINE_HEIGHT]
        Term.chipHeight = vm.config[Keys.CHIP_HEIGHT]
        window.decorView.setBackgroundColor(Term.bg.toArgb())
        applyBars()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
