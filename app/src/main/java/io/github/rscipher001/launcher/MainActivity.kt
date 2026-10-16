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
import io.github.rscipher001.launcher.ui.KolahalTheme
import io.github.rscipher001.launcher.ui.Palettes
import io.github.rscipher001.launcher.ui.Term
import io.github.rscipher001.launcher.data.Keys
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import io.github.rscipher001.launcher.ui.ScriptEditor
import io.github.rscipher001.launcher.ui.TerminalScreen

class MainActivity : ComponentActivity() {
    private val vm: TerminalViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        applyTheme()
        setContent {
            val configVersion by vm.config.version.collectAsStateWithLifecycle()
            LaunchedEffect(configVersion) { applyTheme() }
            KolahalTheme {
                val editing by vm.editing.collectAsStateWithLifecycle()
                val script = editing
                // A launcher never "goes back" off the home screen; the editor installs its own handler.
                BackHandler(enabled = script == null) { }
                if (script != null) {
                    ScriptEditor(
                        script = script,
                        onSave = { vm.saveScript(script, it) },
                        onRun = { vm.runScript(script, it) },
                        onClose = vm::closeEditor,
                    )
                } else {
                    TerminalScreen(vm, onRequestNotifications = ::requestNotifications)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
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
