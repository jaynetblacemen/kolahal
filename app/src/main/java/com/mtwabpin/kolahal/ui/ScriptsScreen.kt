package com.mtwabpin.kolahal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.shortTime
import com.mtwabpin.kolahal.script.ScriptAccess
import com.mtwabpin.kolahal.script.ScriptCatalog
import com.mtwabpin.kolahal.script.ScriptLibrary
import com.mtwabpin.kolahal.script.ScriptMeta
import com.mtwabpin.kolahal.script.ScriptScheduler
import com.mtwabpin.kolahal.terminal.CommandParsing
import com.mtwabpin.kolahal.terminal.CommandProcessor
import com.mtwabpin.kolahal.terminal.Screen
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val INTERVALS = listOf(0 to "Off", 15 to "15m", 30 to "30m", 60 to "1h", 180 to "3h", 360 to "6h", 720 to "12h", 1440 to "1d")

/** A library script with its state against what is installed, and its `@on` events. */
private class LibRow(val entry: ScriptLibrary.Entry, val state: ScriptLibrary.State, val events: List<String>)

/** What a script's card shows and the filters match, read from its code once per change. */
private class ScriptInfo(val script: Script) {
    val title = ScriptMeta.title(script.code)
    val desc = ScriptMeta.description(script.code)
    val isCommand = ScriptMeta.isCommand(script.code)
    val usage = if (isCommand) ScriptMeta.usage(script.code, script.name) else null
    val events = ScriptMeta.events(script.code)
    val uses = ScriptMeta.uses(script.code)
    val timed = script.intervalMin > 0 || script.atTimes.isNotEmpty()
}

/** A filter chip: its name and the scripts it keeps ([inHeader]: the script has a header line now). */
private class ScriptFilter(val id: String, val label: String, val keeps: (info: ScriptInfo, inHeader: Boolean) -> Boolean)

/** Short names for what scripts use, in chips. */
private val USES_SHORT = mapOf("notifications" to "notifications", "clipboard" to "clipboard", "apps" to "apps", "lan" to "local network", "commands" to "commands")

private val SCRIPT_FILTERS = listOf(
    ScriptFilter("commands", "Commands") { s, _ -> s.isCommand },
    ScriptFilter("timers", "Timers") { s, _ -> s.timed },
    ScriptFilter("events", "Events") { s, _ -> s.events.isNotEmpty() },
    ScriptFilter("header", "In the header") { _, line -> line },
    ScriptFilter("failed", "Failed") { s, _ -> s.script.failed() },
    ScriptFilter("off", "Off") { s, _ -> !s.script.enabled },
) + ScriptMeta.USES.map { what -> ScriptFilter("uses:$what", "Uses ${USES_SHORT[what] ?: what}") { s, _ -> what in s.uses } }

/**
 * Script manager (Material 3 Expressive), the Scripts tab: a card per script, compact (its title,
 * kind, timers and last run on one line) or expanded (its header line, next run, usage, events and
 * access), filtered by chips and search. Add script holds new, library, shared and imported ones;
 * a card opens the script's sheet.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    vm: TerminalViewModel,
    onImportFiles: () -> Unit,
    onExportScript: (Script) -> Unit,
) {
    val scripts by vm.scriptList.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var sheetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var consentFor by remember { mutableStateOf<Script?>(null) }
    var filters by rememberSaveable { mutableStateOf(listOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    // Details: all cards open or closed, and the ones you flipped from that.
    var expandAll by rememberSaveable { mutableStateOf(false) }
    var flipped by rememberSaveable { mutableStateOf(listOf<Long>()) }
    BackHandler {
        when {
            searching -> { searching = false; query = "" }
            else -> vm.closeScreen()
        }
    }

    // Switching on a script that declares what nobody allowed yet asks first, with everything on.
    fun setEnabled(s: Script, on: Boolean) {
        if (on && s.grants.undecided.isNotEmpty()) consentFor = s else vm.setScriptEnabled(s, on)
    }

    // The library scripts and their directives come from assets; read them off the main thread.
    val library by produceState<List<LibRow>?>(null, vm.library, scripts) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                vm.library.entries.map { LibRow(it, vm.library.state(it, scripts), ScriptMeta.events(vm.library.code(it))) }
            }.getOrDefault(emptyList())
        }
    }
    val libByName = remember(library) { library.orEmpty().associateBy { it.entry.name } }
    // Shared scripts with a newer version, from the list kept by Get more scripts (no download here).
    val sharedUpdates by produceState(emptyMap<String, ScriptCatalog.Entry>(), scripts) {
        value = withContext(Dispatchers.IO) {
            vm.scriptCatalog.cached().orEmpty()
                .filter { vm.scriptCatalog.state(it, scripts) == ScriptCatalog.State.UPDATE_AVAILABLE }.associateBy { it.name }
        }
    }

    val infos = remember(scripts) { scripts.map(::ScriptInfo) }
    // Only chips something matches, with how many.
    val chips = remember(infos, statuses) {
        SCRIPT_FILTERS.map { f -> f to infos.count { f.keeps(it, it.script.name in statuses) } }.filter { it.second > 0 }
    }
    val shown = remember(infos, statuses, filters, query) {
        val active = SCRIPT_FILTERS.filter { it.id in filters }
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        infos.filter { info ->
            val text = listOfNotNull(info.script.name, info.title, info.desc).joinToString(" ").lowercase()
            active.all { it.keeps(info, info.script.name in statuses) } && words.all { it in text }
        }
    }
    val on = scripts.count { it.enabled }

    GuiTheme {
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
                    subtitle = {
                        Text(
                            if (scripts.isEmpty()) "Lua 5.4 · JavaScript"
                            else listOfNotNull("$on on", (scripts.size - on).takeIf { it > 0 }?.let { "$it off" }).joinToString(" · ")
                        )
                    },
                    navigationIcon = { BackButton(vm::closeScreen) },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = "Search scripts")
                        }
                        IconButton(onClick = { expandAll = !expandAll; flipped = emptyList() }) {
                            Icon(
                                painterResource(R.drawable.ic_expand_more),
                                contentDescription = if (expandAll) "Hide details" else "Show details",
                                modifier = Modifier.rotate(if (expandAll) 180f else 0f),
                            )
                        }
                    },
                    scrollBehavior = scroll,
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(painterResource(R.drawable.ic_add), null) },
                    text = { Text("Add script") },
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "tabs") { FeatureTabs(vm, Screen.SCRIPTS) }
                if (searching) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search by name, title or description") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (scripts.size > 1 && chips.isNotEmpty()) {
                    // One row, scrolled sideways: the chips grow with what scripts use.
                    item(key = "chips") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((f, count) in chips) {
                                val selected = f.id in filters
                                FilterChip(
                                    selected = selected,
                                    onClick = { filters = if (selected) filters - f.id else filters + f.id },
                                    label = { Text("${f.label} $count") },
                                )
                            }
                        }
                    }
                }
                when {
                    scripts.isEmpty() -> item(key = "empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                "No scripts yet. Write one, take one from the library, get one people shared, or import a file.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            )
                            ActionGroup(addActions(vm, onNew = { creating = true }, onImportFiles = onImportFiles))
                        }
                    }
                    shown.isEmpty() -> item(key = "nomatch") {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("No script matches", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { filters = emptyList(); query = "" }) { Text("Show all") }
                        }
                    }
                    else -> items(shown, key = { it.script.id }) { info ->
                        val s = info.script
                        ScriptCard(
                            info,
                            line = statuses[s.name],
                            expanded = expandAll != (s.id in flipped),
                            updateAvailable = libByName[s.name]?.state == ScriptLibrary.State.UPDATE_AVAILABLE || s.name in sharedUpdates,
                            onExpand = { flipped = if (s.id in flipped) flipped - s.id else flipped + s.id },
                            onClick = { sheetId = s.id },
                            onEnabled = { setEnabled(s, it) },
                            onRun = { vm.runScriptNow(s) },
                        )
                    }
                }
                if (scripts.isNotEmpty()) {
                    item(key = "hint") {
                        Text(
                            "Tap a script for its timers, access, code and more. The arrow shows its details here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
                        )
                    }
                }
            }
        }

        val sheetScript = sheetId?.let { id -> scripts.firstOrNull { it.id == id } }
        if (sheetScript != null) {
            ScriptSheet(
                vm, sheetScript, libByName[sheetScript.name], sharedUpdates[sheetScript.name],
                onEnabled = { setEnabled(sheetScript, it) },
                onDismiss = { sheetId = null },
                onEdit = { sheetId = null; vm.openEditor(sheetScript) },
                onExport = { onExportScript(sheetScript) },
            )
        }

        if (adding) {
            ModalBottomSheet(onDismissRequest = { adding = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    ActionGroup(addActions(vm, onNew = { adding = false; creating = true }, onImportFiles = { adding = false; onImportFiles() }, close = { adding = false }))
                }
            }
        }

        consentFor?.let { s ->
            AccessDialog("Turn on ${s.name}.${s.lang.ext}?", s.grants.undecided, "Turn on", onDismiss = { consentFor = null }) { choices ->
                consentFor = null
                vm.enableScript(s, choices)
            }
        }

        if (creating) NewScriptDialog(scripts.map { it.name }.toSet(), onDismiss = { creating = false }) { name, lang, command ->
            creating = false
            vm.newScript(name, lang, command)
        }
    }
}

/** The ways to add a script: Add script's sheet, and the Scripts tab while it is empty. */
private fun addActions(vm: TerminalViewModel, onNew: () -> Unit, onImportFiles: () -> Unit, close: () -> Unit = {}) = listOf(
    Action(R.drawable.ic_add, "New script", "Lua or JavaScript, from a template", onClick = onNew),
    Action(R.drawable.ic_code, "From the library", "Ready-made scripts that come with Kolahal", chevron = true) {
        close()
        vm.openScreen(Screen.SCRIPT_LIBRARY)
    },
    Action(R.drawable.ic_download, "Get more scripts", "Shared by people who use Kolahal, on GitHub", chevron = true) {
        close()
        vm.openScreen(Screen.GET_SCRIPTS)
    },
    Action(R.drawable.ic_upload, "Import a file", ".lua or .js; the file name becomes the script name", onClick = onImportFiles),
)

/** How [s] gets started: `command · every 1h · daily 08:00 · on boot`, or "run by hand". */
private fun schedule(s: Script, isCommand: Boolean, events: List<String>): String = buildList {
    if (isCommand) add("command")
    if (s.intervalMin > 0) add("every ${CommandParsing.formatInterval(s.intervalMin)}")
    if (s.atTimes.isNotEmpty()) add("daily ${s.atTimes.joinToString(", ")}")
    if (events.isNotEmpty()) add("on ${events.joinToString(", ")}")
}.joinToString(" · ").ifEmpty { "run by hand" }

private fun Script.failed() = lastStatus?.let { it.startsWith("error") || it.startsWith("stopped") || it == "timeout" } == true

/** When a switched-on script runs next on its own: its next daily time, or about one interval after its last run. */
private fun nextRun(s: Script, now: Long = System.currentTimeMillis()): Pair<Long, Boolean>? {
    if (!s.enabled) return null
    // To the minute: the daily time itself, not a few seconds before it.
    val daily = s.atTimes.map { Math.round((now + ScriptScheduler.millisUntil(it)) / 60_000.0) * 60_000 }.minOrNull()
    val every = if (s.intervalMin > 0) maxOf(now, (s.lastRun ?: now) + s.intervalMin * 60_000L) else null
    return when {
        daily != null && (every == null || daily <= every) -> daily to true
        every != null -> every to false
        else -> null
    }
}

/**
 * One script. Compact: a dot (on, failed, off), its title and file name, Run for a command, the
 * details arrow, the switch, and one line of how it runs and how its last run went. Expanded, also
 * its header line, description, next run, usage, events and what it may use.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptCard(
    info: ScriptInfo,
    line: String?,
    expanded: Boolean,
    updateAvailable: Boolean,
    onExpand: () -> Unit,
    onClick: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRun: () -> Unit,
) {
    val s = info.script
    val colors = MaterialTheme.colorScheme
    val failed = s.failed()
    val summary = buildList {
        add(schedule(s, info.isCommand, info.events))
        add(s.lastRun?.let { (if (failed) "failed " else "ran ") + relative(it, short = true) } ?: "never run")
        if (updateAvailable) add("update available")
    }.joinToString(" · ")
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize().testTag("script-card"),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (!s.enabled) colors.outlineVariant else if (failed) colors.error else colors.primary)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        info.title ?: s.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (s.enabled) colors.onSurface else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${s.name}.${s.lang.ext}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (s.enabled) scriptColor(s.name) else colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (info.isCommand && s.enabled) TextButton(onClick = onRun) { Text("Run") }
                IconButton(onClick = onExpand) {
                    Icon(
                        painterResource(R.drawable.ic_expand_more),
                        contentDescription = if (expanded) "Hide details" else "Show details",
                        modifier = Modifier.rotate(if (expanded) 180f else 0f),
                    )
                }
                Switch(checked = s.enabled, onCheckedChange = onEnabled)
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) colors.error else if (updateAvailable) colors.primary else colors.onSurfaceVariant,
                maxLines = if (expanded) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 8.dp),
            )
            if (expanded) ScriptDetails(info, line)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptDetails(info: ScriptInfo, line: String?) {
    val s = info.script
    val colors = MaterialTheme.colorScheme
    val grants = s.grants
    Column(Modifier.padding(start = 20.dp, top = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        line?.let {
            Surface(shape = MaterialTheme.shapes.small, color = colors.surfaceContainerHighest) {
                Text(
                    "${s.name} ▸ $it",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        info.desc?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        nextRun(s)?.let { (at, exact) ->
            val time = Config.get(LocalContext.current).shortTime(at)
            Fact("Next run", if (exact) time else "about $time")
        }
        info.usage?.let { Fact("Usage", it) }
        if (info.events.isNotEmpty()) Fact("Runs on", info.events.joinToString(", ") { if (grants.allows(it)) it else "$it (switched off)" })
        // What it declares, and internet or its header line when they are switched off.
        val access = ScriptAccess.items(s.code).filter { it !in info.events && (it in grants.declared || !grants.allows(it)) }
        if (access.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (item in access) {
                    val allowed = grants.allows(item)
                    val name = USES_SHORT[item] ?: ScriptAccess.label(item).lowercase()
                    Surface(shape = MaterialTheme.shapes.small, color = if (allowed) colors.secondaryContainer else colors.surfaceContainerHighest) {
                        Text(
                            when {
                                allowed -> name
                                item in grants.undecided -> "$name · not allowed yet"
                                else -> "$name · off"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (allowed) colors.onSecondaryContainer else colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        if (s.failed()) Text(s.lastStatus.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.error)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Scripts → Add script → From the library: the ready-made scripts that come with Kolahal. */
@Composable
fun ScriptLibraryScreen(vm: TerminalViewModel) {
    val scripts by vm.scriptList.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    BackHandler { vm.closeScreen() }
    val library by produceState<List<LibRow>?>(null, vm.library, scripts) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                vm.library.entries.map { LibRow(it, vm.library.state(it, scripts), ScriptMeta.events(vm.library.code(it))) }
            }.getOrDefault(emptyList())
        }
    }
    GuiTheme {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it) } }
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("Library") },
                    subtitle = { Text("Ready-made scripts that come with Kolahal") },
                    navigationIcon = { BackButton(vm::closeScreen) },
                    scrollBehavior = scroll,
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                val rows = library.orEmpty()
                itemsIndexed(rows, key = { _, r -> "lib:" + r.entry.name }) { i, r ->
                    LibraryRow(r, i, rows.size) { vm.installFromLibrary(r.entry) }
                }
                item(key = "more") {
                    Column(Modifier.padding(top = 16.dp)) {
                        ActionGroup(
                            listOf(
                                Action(R.drawable.ic_download, "Get more scripts", "Shared by people who use Kolahal, on GitHub", chevron = true) {
                                    vm.openScreen(Screen.GET_SCRIPTS)
                                },
                            )
                        )
                    }
                }
            }
        }
    }
}

/** Everything about one script: run, edit, export, on/off, notifications, timers, library and delete. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScriptSheet(
    vm: TerminalViewModel,
    s: Script,
    lib: LibRow?,
    /** The shared version, when it is newer than this unedited copy. */
    sharedUpdate: ScriptCatalog.Entry?,
    onEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
) {
    val state = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    var confirmDelete by remember(s.id) { mutableStateOf(false) }
    var confirmReplace by remember(s.id) { mutableStateOf(false) }
    var times by remember(s.id, s.atTimes) { mutableStateOf(s.atTimes.joinToString(", ")) }
    var timesError by remember(s.id) { mutableStateOf(false) }
    val isCommand = remember(s.code) { ScriptMeta.isCommand(s.code) }
    val desc = remember(s.code) { ScriptMeta.description(s.code) }
    val title = remember(s.code) { ScriptMeta.title(s.code) }
    // `@version` and `@author`, when it says: v1.2 · by Someone
    val byLine = remember(s.code) {
        listOfNotNull(ScriptMeta.version(s.code)?.let { "v$it" }, ScriptMeta.author(s.code)?.let { "by $it" }).joinToString(" · ").ifEmpty { null }
    }
    val access = remember(s.code) { ScriptAccess.items(s.code) }
    val grants = remember(s.code, s.access) { s.grants }

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
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(
                listOfNotNull(if (s.lang == ScriptLang.LUA) "Lua" else "JavaScript", byLine, if (isCommand) "command" else null, desc)
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
            ) { onEnabled(it) }

            // What it may use: internet and its header line, notifications it posts, what it declares
            // with @uses, and the events of its @on lines.
            Spacer(Modifier.height(16.dp))
            Text("Access", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            access.forEach { item ->
                SwitchRow(ScriptAccess.label(item), ScriptAccess.help(item), grants.allows(item)) { vm.setScriptAccess(s, item, it) }
                if (item == ScriptAccess.HEADER) {
                    SwitchRow("Post notifications", "Lets the script notify you", s.notify) { vm.setScriptNotify(s, it) }
                }
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
                sharedUpdate?.let { e ->
                    FilledTonalButton(onClick = { vm.installSharedScript(e) }) { Text("Update to ${e.version}") }
                }
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

/**
 * Before a script may use what it declares: switching on one that declares what nobody allowed yet,
 * or installing a shared one. Each item ([items], [ScriptAccess] names) with a switch, all on, to
 * switch off what you don't want before [confirm]. The script's Access switches change them later.
 */
@Composable
internal fun AccessDialog(title: String, items: List<String>, confirm: String, onDismiss: () -> Unit, onConfirm: (Map<String, Boolean>) -> Unit) {
    val choices = remember(items) { mutableStateMapOf<String, Boolean>().apply { items.forEach { put(it, true) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("It asks to use:", style = MaterialTheme.typography.bodyMedium)
                items.forEach { item ->
                    SwitchRow(ScriptAccess.label(item), ScriptAccess.help(item), choices[item] == true, color = Color.Transparent) { choices[item] = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choices.toMap()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
        trailingContent = {
            when (r.state) {
                ScriptLibrary.State.NOT_INSTALLED -> FilledTonalButton(onClick = onInstall) { Text("Install") }
                ScriptLibrary.State.UPDATE_AVAILABLE -> FilledTonalButton(onClick = onInstall) { Text("Update") }
                // Replacing your edited copy is in its sheet (Scripts), behind a confirmation.
                ScriptLibrary.State.MODIFIED -> Text("Edited", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ScriptLibrary.State.INSTALLED -> Text("Installed", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) { Text(e.title) }
}
