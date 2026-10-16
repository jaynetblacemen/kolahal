package io.github.rscipher001.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rscipher001.launcher.R
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.data.Script
import io.github.rscipher001.launcher.data.ScriptLang
import io.github.rscipher001.launcher.script.ScriptMeta
import io.github.rscipher001.launcher.terminal.CommandProcessor
import io.github.rscipher001.launcher.terminal.TerminalViewModel
import java.text.DateFormat
import java.util.Date

private val INTERVALS = listOf(0 to "Off", 15 to "15m", 30 to "30m", 60 to "1h", 180 to "3h", 360 to "6h", 720 to "12h", 1440 to "1d")

/**
 * Script manager (Material 3 Expressive): import `.lua` / `.js` files from the file manager,
 * create scripts, and set each script's timers, notifications and state without typing commands.
 */
@Composable
fun ScriptsScreen(
    vm: TerminalViewModel,
    onImportFiles: () -> Unit,
    onExportScript: (Script) -> Unit,
) {
    val scripts by vm.scriptList.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val version = vm.config.version.collectAsStateWithLifecycle()
    val useSystemColors by remember { derivedStateOf { version.value; vm.config[Keys.GUI_COLORS] == "system" } }
    var creating by remember { mutableStateOf(false) }
    BackHandler { vm.closeScreen() }

    GuiTheme(useSystemColors) {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it) } }

        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("Scripts") },
                    subtitle = { Text(if (scripts.isEmpty()) "Lua 5.4 · JavaScript" else "${scripts.size} scripts") },
                    navigationIcon = { BackButton(vm::closeScreen) },
                    actions = {
                        IconButton(onClick = onImportFiles) {
                            Icon(painterResource(R.drawable.ic_upload), contentDescription = "Import files")
                        }
                    },
                    scrollBehavior = scroll,
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(painterResource(R.drawable.ic_add), null) },
                    text = { Text("New script") },
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "import") {
                    ActionGroup(
                        listOf(
                            Action(
                                R.drawable.ic_upload,
                                "Import .lua / .js files",
                                "Pick files from the file manager. The file name becomes the script name; importing again updates it.",
                                onImportFiles,
                            ),
                        )
                    )
                }
                if (scripts.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No scripts yet. Import a file or tap “New script”.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                }
                items(scripts, key = { it.id }) { s -> ScriptCard(vm, s, onExportScript) }
            }
        }

        if (creating) NewScriptDialog(onDismiss = { creating = false }) { name, lang, command ->
            creating = false
            vm.newScript(name, lang, command)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptCard(vm: TerminalViewModel, s: Script, onExport: (Script) -> Unit) {
    var confirmDelete by remember(s.id) { mutableStateOf(false) }
    var times by remember(s.id, s.atTimes) { mutableStateOf(s.atTimes.joinToString(", ")) }
    var timesError by remember(s.id) { mutableStateOf(false) }
    val isCommand = remember(s.code) { ScriptMeta.isCommand(s.code) }
    val desc = remember(s.code) { ScriptMeta.description(s.code) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val failed = s.lastStatus?.startsWith("error") == true || s.lastStatus == "timeout"

    ElevatedCard(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${s.name}.${s.lang.ext}",
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        color = scriptColor(s.name),
                    )
                    Text(
                        listOfNotNull(
                            if (s.lang == ScriptLang.LUA) "Lua" else "JavaScript",
                            if (isCommand) "command" else null,
                            desc,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.enabled, onCheckedChange = { vm.setScriptEnabled(s, it) })
            }
            Spacer(Modifier.height(6.dp))
            Text(
                s.lastRun?.let { "Last run ${df.format(Date(it))} · ${s.lastStatus.orEmpty()}" } ?: "Never run",
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                maxLines = 3,
            )

            Spacer(Modifier.height(16.dp))
            Text("Runs every", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(INTERVALS) { (minutes, label) ->
                    FilterChip(
                        selected = s.intervalMin == minutes,
                        onClick = { vm.setScriptInterval(s, minutes) },
                        label = { Text(label) },
                    )
                }
                if (s.intervalMin > 0 && INTERVALS.none { it.first == s.intervalMin }) {
                    item {
                        // Interval set from the terminal (not one of the presets): shown, not a button.
                        FilterChip(selected = true, enabled = false, onClick = {}, label = { Text(CommandProcessor.formatInterval(s.intervalMin)) })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = times,
                onValueChange = { times = it; timesError = false },
                label = { Text("Daily at") },
                placeholder = { Text("08:00, 18:30") },
                singleLine = true,
                isError = timesError,
                supportingText = if (timesError) {
                    { Text("Use HH:mm, separated by commas") }
                } else null,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { timesError = !vm.setScriptTimes(s, times) }),
                trailingIcon = if (times != s.atTimes.joinToString(", ")) {
                    {
                        IconButton(onClick = { timesError = !vm.setScriptTimes(s, times) }) {
                            Icon(painterResource(R.drawable.ic_check), "Set times")
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Notifications", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "notify() posts a notification",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.notify, onCheckedChange = { vm.setScriptNotify(s, it) })
            }

            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { vm.runScriptNow(s) }) {
                    Icon(painterResource(R.drawable.ic_play), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Run")
                }
                FilledTonalButton(onClick = { vm.openEditor(s) }) {
                    Icon(painterResource(R.drawable.ic_edit), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Edit")
                }
                OutlinedButton(onClick = { onExport(s) }) {
                    Icon(painterResource(R.drawable.ic_download), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Export")
                }
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Delete")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(painterResource(R.drawable.ic_delete), null) },
            title = { Text("Delete ${s.name}.${s.lang.ext}?") },
            text = { Text("Its timers, stored data and status line are removed too. Export it first to keep a copy.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; vm.deleteScript(s) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NewScriptDialog(onDismiss: () -> Unit, onCreate: (String, ScriptLang, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf(ScriptLang.LUA) }
    var command by remember { mutableStateOf(false) }
    val valid = Regex("^[a-z0-9_-]{1,40}$").matches(name.trim().lowercase())
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_code), null) },
        title = { Text("New script") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("hn, weather, feeds…") },
                    singleLine = true,
                    isError = name.isNotEmpty() && !valid,
                    supportingText = { Text("lowercase letters, digits, - and _") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ConnectedChoices(listOf("Lua", "JavaScript"), if (lang == ScriptLang.LUA) "Lua" else "JavaScript") {
                    lang = if (it == "Lua") ScriptLang.LUA else ScriptLang.JS
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(checked = command, onCheckedChange = { command = it })
                    Column {
                        Text("Command template", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Runs from the prompt by name, with arguments",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onCreate(name, lang, command) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
