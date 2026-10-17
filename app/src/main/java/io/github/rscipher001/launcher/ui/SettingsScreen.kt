package io.github.rscipher001.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rscipher001.launcher.R
import io.github.rscipher001.launcher.data.Config
import io.github.rscipher001.launcher.data.ConfigKey
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.terminal.Screen
import io.github.rscipher001.launcher.terminal.TerminalViewModel
import kotlin.math.roundToInt

/** Human titles for settings; the description comes from the key's help text. */
private val TITLES = mapOf(
    "theme" to "Theme",
    "gui.colors" to "Settings colours",
    "font.size" to "Text size",
    "header.date" to "Date",
    "clock.24h" to "24-hour clock",
    "header.unlocks" to "Unlock count",
    "header.battery" to "Battery",
    "header.network" to "Network",
    "header.alarm" to "Next alarm",
    "header.profiles" to "Work / private state",
    "header.status" to "Script status lines",
    "header.device" to "Device name",
    "log.time" to "Times",
    "log.dim" to "Dim older output",
    "log.spacing" to "Space between commands",
    "log.keepdays" to "Keep log for (days)",
    "launch.top" to "Enter opens the best match",
    "launch.single" to "Open the only match",
    "suggestions.idle" to "Most-used apps on empty prompt",
    "suggestions" to "Suggestions shown",
    "keybar.show" to "Show the key bar",
    "search.engine" to "Search engine",
    "search.url" to "Custom search URL",
    "keybar" to "Key bar, row 1",
    "keybar2" to "Key bar, row 2",
    "keyboard.suggestions" to "Keyboard suggestions",
    "keyboard.onhome" to "Open keyboard on home",
    "widgets.home" to "Widgets on home",
    "widgets.height" to "Home widget area height (dp)",
)

private val SECTIONS: List<Pair<String, List<ConfigKey<*>>>> = listOf(
    "Appearance" to listOf(Keys.THEME, Keys.GUI_COLORS, Keys.FONT_SIZE),
    "Header" to listOf(
        Keys.HEADER_DATE, Keys.CLOCK_24H, Keys.HEADER_UNLOCKS, Keys.HEADER_BATTERY, Keys.HEADER_NETWORK,
        Keys.HEADER_ALARM, Keys.HEADER_PROFILES, Keys.HEADER_STATUS, Keys.HEADER_MEDIA, Keys.HEADER_TODO, Keys.HEADER_DEVICE, Keys.HEADER_AUTOHIDE, Keys.HEADER_ICONS,
    ),
    "Log" to listOf(Keys.SHOW_TIMESTAMPS, Keys.LOG_DIM, Keys.LOG_SPACING, Keys.LOG_LINE_HEIGHT, Keys.LOG_KEEP_DAYS),
    "Launching" to listOf(Keys.LAUNCH_INSTANT, Keys.LAUNCH_INSTANT_MIN, Keys.LAUNCH_TOP, Keys.AUTO_LAUNCH, Keys.SUGGEST_IDLE, Keys.SUGGESTIONS_AUTO, Keys.SUGGESTIONS, Keys.SEARCH_ENGINE, Keys.SEARCH_URL),
    "Widgets" to listOf(Keys.WIDGETS_HOME, Keys.WIDGETS_HEIGHT),
    "Gestures" to listOf(Keys.GESTURE_DOUBLE_TAP, Keys.GESTURE_SWIPE_DOWN, Keys.GESTURE_SWIPE_UP),
    "Keyboard & key bar" to listOf(Keys.KEYBAR_SHOW, Keys.KEYBAR_AUTO, Keys.KEYBAR_HEIGHT, Keys.PROMPT_HEIGHT_TYPING, Keys.CHIP_HEIGHT, Keys.KEYBAR, Keys.KEYBAR2, Keys.IME_SUGGESTIONS, Keys.FOCUS_ON_HOME),
)

/**
 * Material 3 Expressive settings: grouped segmented lists, switches, connected button groups,
 * sliders and theme swatches. Each row observes only its own value, so a change redraws one row.
 */
@Composable
fun SettingsScreen(
    vm: TerminalViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    val config = vm.config
    val version = vm.config.version.collectAsStateWithLifecycle()
    val useSystemColors by remember { derivedStateOf { version.value; config[Keys.GUI_COLORS] == "system" } }
    val notice by vm.notice.collectAsStateWithLifecycle()
    BackHandler { vm.closeScreen() }

    GuiTheme(useSystemColors) {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it) } }
        val listed = remember { SECTIONS.flatMap { it.second }.toSet() }
        val other = remember { Keys.ALL.filter { it !in listed } }

        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("Settings") },
                    subtitle = { Text("Kolahal") },
                    navigationIcon = { BackButton(vm::closeScreen) },
                    scrollBehavior = scroll,
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "h:backup") { GroupHeader("Backup") }
                item(key = "backup") {
                    ActionGroup(
                        listOf(
                            Action(R.drawable.ic_download, "Export everything", "Settings, apps, scripts, todos, notes, reminders, feeds and the log in one JSON file", onExportBackup),
                            Action(R.drawable.ic_upload, "Import from file", "Restore a Kolahal backup (replaces todos, notes, feeds and the log)", onImportBackup),
                        )
                    )
                }
                item(key = "h:scripts") { GroupHeader("Scripts") }
                item(key = "scripts") {
                    ActionGroup(
                        listOf(
                            Action(R.drawable.ic_code, "Script manager", "Import .lua / .js files, timers, notifications") { vm.openScreen(Screen.SCRIPTS) },
                            Action(R.drawable.ic_rss, "Feed manager", "RSS/Atom subscriptions, intervals, OPML import / export") {
                                vm.openScreen(Screen.FEEDS)
                            },
                            Action(R.drawable.ic_widgets, "Widgets", "Add, resize and arrange widgets; place them on home") {
                                vm.openScreen(Screen.WIDGETS)
                            },
                            Action(R.drawable.ic_check, "Setup checklist", "Default launcher, permissions, battery") {
                                vm.openScreen(Screen.SETUP)
                            },
                        )
                    )
                }
                for ((title, keys) in SECTIONS + listOf("Other" to other)) {
                    if (keys.isEmpty()) continue
                    item(key = "h:$title") { GroupHeader(title) }
                    keys.forEachIndexed { index, key ->
                        item(key = key.key) {
                            SettingItem(vm, config, key, version, index, keys.size)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingItem(vm: TerminalViewModel, config: Config, key: ConfigKey<*>, version: State<Int>, index: Int, count: Int) {
    // Only this row recomposes when its own value changes.
    val value by remember(key) { derivedStateOf { version.value; config.display(key) } }
    val changed by remember(key) { derivedStateOf { version.value; !config.isDefault(key) } }
    val shapes = segShapes(index, count)
    val title = TITLES[key.key] ?: key.key
    // Reset sits on the key-name line, so it never narrows the controls below it.
    val supporting: @Composable () -> Unit = {
        Column {
            Text(key.help)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    key.key + if (changed) "  · changed" else "",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                if (changed && key !is ConfigKey.Bool) {
                    TextButton(onClick = { vm.resetConfig(key) }) { Text("Reset") }
                }
            }
        }
    }

    Box(Modifier.padding(bottom = ListItemDefaults.SegmentedGap)) {
        when (key) {
            is ConfigKey.Bool -> SegmentedListItem(
                colors = segColors(),
                checked = value == "on",
                onCheckedChange = { vm.setConfig(key, if (it) "on" else "off") },
                shapes = shapes,
                supportingContent = supporting,
                trailingContent = { Switch(checked = value == "on", onCheckedChange = null) },
            ) { Text(title) }

            is ConfigKey.Choice -> SegmentedListItem(
                colors = segColors(),
                shapes = shapes,
                supportingContent = {
                    Column {
                        supporting()
                        Spacer(Modifier.height(10.dp))
                        if (key == Keys.THEME) {
                            ThemeSwatches(value) { vm.setConfig(key, it) }
                        } else if (key.choices.size > 4) {
                            ChipChoices(key.choices, value) { vm.setConfig(key, it) }
                        } else {
                            ConnectedChoices(key.choices, value) { vm.setConfig(key, it) }
                        }
                    }
                },
            ) { Text(title) }

            is ConfigKey.Num -> {
                var current by remember(key, value) { mutableFloatStateOf(value.toFloat()) }
                SegmentedListItem(
                    colors = segColors(),
                    shapes = shapes,
                    trailingContent = {
                        Text(current.roundToInt().toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    },
                    supportingContent = {
                        Column {
                            supporting()
                            Slider(
                                value = current,
                                onValueChange = { current = it },
                                onValueChangeFinished = { vm.setConfig(key, current.roundToInt().toString()) },
                                valueRange = key.range.first.toFloat()..key.range.last.toFloat(),
                                steps = (key.range.last - key.range.first - 1).coerceAtLeast(0),
                            )
                        }
                    },
                ) { Text(title) }
            }

            is ConfigKey.Text -> {
                var text by remember(key, value) { mutableStateOf(value) }
                SegmentedListItem(
                    colors = segColors(),
                    shapes = shapes,
                        supportingContent = {
                        Column {
                            supporting()
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                keyboardOptions = KeyboardOptions(
                                    autoCorrectEnabled = false,
                                    capitalization = KeyboardCapitalization.None,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { vm.setConfig(key, text) }),
                                trailingIcon = if (text != value) {
                                    { IconButton(onClick = { vm.setConfig(key, text) }) { Icon(painterResource(R.drawable.ic_check), "Apply") } }
                                } else null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                ) { Text(title) }
            }
        }
    }
}

/** Many options: wrapping single-select filter chips. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChipChoices(choices: List<String>, selected: String, onPick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { c ->
            androidx.compose.material3.FilterChip(selected = c == selected, onClick = { onPick(c) }, label = { Text(c) })
        }
    }
}

/** Single-select connected button group (Material 3 Expressive). */
@Composable
fun ConnectedChoices(choices: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier.fillMaxWidth(),
    ) {
        choices.forEachIndexed { i, choice ->
            ToggleButton(
                checked = choice == selected,
                onCheckedChange = { onPick(choice) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(choice, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Theme picker: a preview card per palette. */
@Composable
private fun ThemeSwatches(selected: String, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(Keys.THEME.choices) { name ->
            val p = Palettes.named(name)
            val on = name == selected
            Card(
                onClick = { onPick(name) },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = p.bg),
                border = if (on) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, p.surface),
                modifier = Modifier.width(112.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(p.prompt, p.work, p.link, p.private).forEach {
                            Box(Modifier.size(14.dp).background(it, CircleShape))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("$ ls", color = p.prompt, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
                    Text(name, color = p.fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

data class Action(val icon: Int, val title: String, val subtitle: String, val onClick: () -> Unit)

/** A segmented group of clickable rows. */
@Composable
fun ActionGroup(actions: List<Action>) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        actions.forEachIndexed { i, a ->
            SegmentedListItem(
                colors = segColors(),
                onClick = a.onClick,
                shapes = segShapes(i, actions.size),
                leadingContent = { Icon(painterResource(a.icon), null, tint = MaterialTheme.colorScheme.primary) },
                supportingContent = { Text(a.subtitle) },
            ) { Text(a.title) }
        }
    }
}

@Composable
fun GroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
}
