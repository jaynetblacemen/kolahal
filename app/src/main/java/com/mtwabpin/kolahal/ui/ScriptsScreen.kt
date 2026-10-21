package com.mtwabpin.kolahal.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.script.ScriptLibrary
import com.mtwabpin.kolahal.script.ScriptMeta
import com.mtwabpin.kolahal.terminal.CommandParsing
import com.mtwabpin.kolahal.terminal.CommandProcessor
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val INTERVALS = listOf(0 to "Off", 15 to "15m", 30 to "30m", 60 to "1h", 180 to "3h", 360 to "6h", 720 to "12h", 1440 to "1d")

/** A library script with its state against what is installed, and its `@on` events. */
private class LibRow(val entry: ScriptLibrary.Entry, val state: ScriptLibrary.State, val events: List<String>)

/**
 * Script manager (Material 3 Expressive): your scripts as compact rows (tap one for its timers,
 * notifications and buttons in a sheet), then the library scripts not installed yet. Files are
 * imported from the top bar.
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
    var sheetId by rememberSaveable { mutableStateOf<Long?>(null) }
    BackHandler { vm.closeScreen() }

    // index.json and the script bodies come from assets; read them off the main thread.
    val library by produceState<List<LibRow>?>(null, vm.library, scripts) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                vm.library.entries.map { LibRow(it, vm.library.state(it, scripts), ScriptMeta.events(vm.library.code(it))) }
            }.getOrDefault(emptyList())
        }
    }
    val libByName = remember(library) { library.orEmpty().associateBy { it.entry.name } }
    val notInstalled = remember(library) { library?.filter { it.state == ScriptLibrary.State.NOT_INSTALLED } }

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
                            Icon(painterResource(R.drawable.ic_upload), contentDescription = "Import .lua / .js files")
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
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "h:mine") { GroupHeader("Your scripts") }
                if (scripts.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No scripts yet. Install one from the library below, tap “New script”, or import a file.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                    item(key = "import") {
                        ActionGroup(
                            listOf(
                                Action(
                                    R.drawable.ic_upload,
                                    "Import .lua / .js files",
                                    "Pick files from the file manager. The file name becomes the script name; importing again updates it.",
                                    onClick = onImportFiles,
                                ),
                            )
                        )
                    }
                } else {
                    itemsIndexed(scripts, key = { _, s -> s.id }) { i, s ->
                        ScriptRow(
                            s, i, scripts.size,
                            updateAvailable = libByName[s.name]?.state == ScriptLibrary.State.UPDATE_AVAILABLE,
                            onClick = { sheetId = s.id },
                            onEnabled = { vm.setScriptEnabled(s, it) },
                        )
                    }
                    item(key = "hint") {
                        Text(
                            "Tap a script to set its timers, run, edit or export it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                        )
                    }
                }

                item(key = "h:lib") { GroupHeader("Library") }
                val rows = notInstalled
                when {
                    rows == null -> Unit // first load; the rows appear within a frame or two
                    rows.isEmpty() -> item(key = "lib:all") {
                        Text(
                            "Every library script is installed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                    else -> itemsIndexed(rows, key = { _, r -> "lib:" + r.entry.name }) { i, r ->
                        LibraryRow(r, i, rows.size) { vm.installFromLibrary(r.entry) }
                    }
                }
            }
        }

        val sheetScript = sheetId?.let { id -> scripts.firstOrNull { it.id == id } }
        if (sheetScript != null) {
            ScriptSheet(
                vm, sheetScript, libByName[sheetScript.name],
                onDismiss = { sheetId = null },
                onEdit = { sheetId = null; vm.openEditor(sheetScript) },
                onExport = { onExportScript(sheetScript) },
            )
        }

        if (creating) NewScriptDialog(scripts.map { it.name }.toSet(), onDismiss = { creating = false }) { name, lang, command ->
            creating = false
            vm.newScript(name, lang, command)
        }
    }
}

/** How [s] gets started: `command · every 1h · daily 08:00 · on boot`, or "run by hand". */
private fun schedule(s: Script, isCommand: Boolean, events: List<String>): String = buildList {
    if (isCommand) add("command")
    if (s.intervalMin > 0) add("every ${CommandParsing.formatInterval(s.intervalMin)}")
    if (s.atTimes.isNotEmpty()) add("daily ${s.atTimes.joinToString(", ")}")
    if (events.isNotEmpty()) add("on ${events.joinToString(", ")}")
}.joinToString(" · ").ifEmpty { "run by hand" }

private fun Script.failed() = lastStatus?.startsWith("error") == true || lastStatus == "timeout"

/** One line per script: name, how it runs and its last run; the switch turns it on or off. */
@Composable
private fun ScriptRow(s: Script, index: Int, count: Int, updateAvailable: Boolean, onClick: () -> Unit, onEnabled: (Boolean) -> Unit) {
    val isCommand = remember(s.code) { ScriptMeta.isCommand(s.code) }
    val events = remember(s.code) { ScriptMeta.events(s.code) }
    val status = buildList {
        add(schedule(s, isCommand, events))
        add(s.lastRun?.let { (if (s.failed()) "failed " else "ran ") + relative(it, short = true) } ?: "never run")
        if (updateAvailable) add("update available")
    }.joinToString(" · ")
    SegmentedListItem(
        onClick = onClick,
        shapes = segShapes(index, count),
        colors = segColors(),
        leadingContent = {
            Icon(
                painterResource(R.drawable.ic_code),
                null,
                tint = if (s.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        },
        supportingContent = {
            Text(
                status,
                color = if (s.failed()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = { Switch(checked = s.enabled, onCheckedChange = onEnabled) },
    ) {
        Text(
            "${s.name}.${s.lang.ext}",
            fontFamily = FontFamily.Monospace,
            color = if (s.enabled) scriptColor(s.name) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Everything about one script: run, edit, export, on/off, notifications, timers, library and delete. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScriptSheet(
    vm: TerminalViewModel,
    s: Script,
    lib: LibRow?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember(s.id) { mutableStateOf(false) }
    var confirmReplace by remember(s.id) { mutableStateOf(false) }
    var times by remember(s.id, s.atTimes) { mutableStateOf(s.atTimes.joinToString(", ")) }
    var timesError by remember(s.id) { mutableStateOf(false) }
    val isCommand = remember(s.code) { ScriptMeta.isCommand(s.code) }
    val desc = remember(s.code) { ScriptMeta.description(s.code) }
    val events = remember(s.code) { ScriptMeta.events(s.code) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            Text(
                "${s.name}.${s.lang.ext}",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                color = scriptColor(s.name),
            )
            Text(
                listOfNotNull(if (s.lang == ScriptLang.LUA) "Lua" else "JavaScript", if (isCommand) "command" else null, desc)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                s.lastRun?.let { "Last run ${relative(it)} · ${s.lastStatus.orEmpty()}" } ?: "Never run",
                style = MaterialTheme.typography.bodySmall,
                color = if (s.failed()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.runScriptNow(s) }) {
                    Icon(painterResource(R.drawable.ic_play), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Run")
                }
                FilledTonalButton(onClick = onEdit) {
                    Icon(painterResource(R.drawable.ic_edit), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Edit")
                }
                OutlinedButton(onClick = onExport) {
                    Icon(painterResource(R.drawable.ic_download), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Export")
                }
            }

            Spacer(Modifier.height(8.dp))
            SwitchRow(
                "Enabled",
                if (isCommand) "Runs on its timers and events, and from the prompt" else "Runs on its timers and events",
                s.enabled,
            ) { vm.setScriptEnabled(s, it) }
            SwitchRow("Notifications", "Lets the script post notifications", s.notify) { vm.setScriptNotify(s, it) }

            if (events.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // Set by `@on` lines in the code; edit the script to change them.
                Text(
                    "Also runs on ${events.joinToString(", ")} (its @on lines)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Runs every", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            IntervalGroup(s.intervalMin, { vm.setScriptInterval(s, it) }, INTERVALS)
            if (s.intervalMin > 0 && INTERVALS.none { it.first == s.intervalMin }) {
                Text(
                    "Currently every ${CommandParsing.formatInterval(s.intervalMin)} (set from the terminal)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
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

            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (lib?.state) {
                    // Unedited copy of an older library version: nothing of yours is lost.
                    ScriptLibrary.State.UPDATE_AVAILABLE ->
                        FilledTonalButton(onClick = { vm.installFromLibrary(lib.entry) }) { Text("Update from library") }
                    ScriptLibrary.State.MODIFIED ->
                        TextButton(onClick = { confirmReplace = true }) { Text("Replace with library version") }
                    else -> Unit
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

    if (confirmReplace && lib != null) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace ${s.name}.${s.lang.ext}?") },
            text = { Text("Your copy differs from the library's. Its code is replaced and your changes are lost. Export it first to keep a copy.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmReplace = false; vm.installFromLibrary(lib.entry) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(painterResource(R.drawable.ic_delete), null) },
            title = { Text("Delete ${s.name}.${s.lang.ext}?") },
            text = { Text("Its timers, stored data and status line are removed too. Export it first to keep a copy.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDismiss(); vm.deleteScript(s) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NewScriptDialog(existing: Set<String>, onDismiss: () -> Unit, onCreate: (String, ScriptLang, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf(ScriptLang.LUA) }
    var command by remember { mutableStateOf(false) }
    val builtIns = CommandProcessor.COMMAND_NAMES
    val clean = name.trim().lowercase()
    // The same checks as `script new`, shown while typing.
    val problem = when {
        clean.isEmpty() -> null
        !Regex("^[a-z0-9_-]{1,40}$").matches(clean) -> "Lowercase letters, digits, - and _"
        clean in builtIns -> "'$clean' is a built-in command"
        clean in existing -> "You already have a script named $clean"
        else -> null
    }
    val valid = clean.isNotEmpty() && problem == null
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
                    isError = problem != null,
                    supportingText = { Text(problem ?: "Lowercase letters, digits, - and _") },
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

/** A library script not installed yet: its title, tap for the description; one tap installs it with suggested timers. */
@Composable
private fun LibraryRow(r: LibRow, index: Int, count: Int, onInstall: () -> Unit) {
    var open by rememberSaveable(r.entry.name) { mutableStateOf(false) }
    val e = r.entry
    SegmentedListItem(
        onClick = { open = !open },
        shapes = segShapes(index, count),
        colors = segColors(),
        overlineContent = {
            Text(
                "${e.name}.${e.lang.ext}" + (if (e.command) " · command" else
                    if (e.suggestedEvery > 0) " · every ${CommandParsing.formatInterval(e.suggestedEvery)}" else "") +
                    r.events.let { if (it.isEmpty()) "" else " · on " + it.joinToString(", ") },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            )
        },
        supportingContent = if (open) {
            { Text(e.description) }
        } else null,
        trailingContent = { FilledTonalButton(onClick = onInstall) { Text("Install") } },
    ) { Text(e.title) }
}
