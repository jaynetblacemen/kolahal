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
        setContent {
            KolahalTheme {
                val editing by vm.editing.collectAsStateWithLifecycle()
                val script = editing
                // A launcher never "goes back" off the home screen.
                BackHandler(enabled = true) { if (script != null) vm.closeEditor() }
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
        if (intent.hasCategory(Intent.CATEGORY_HOME)) vm.onHomePressed()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
