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

class MainActivity : ComponentActivity() {
    private val vm: TerminalViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onNotificationPermission(it) }

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
            vm.events.collect { if (it == UiEvent.RequestNotificationPermission) requestNotifications() }
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
                        Screen.SETUP -> SetupScreen(vm)
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

    private fun applyTheme() {
        Term.palette = Palettes.named(vm.config[Keys.THEME])
        Term.fontSize = vm.config[Keys.FONT_SIZE]
        window.decorView.setBackgroundColor(Term.bg.toArgb())
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
