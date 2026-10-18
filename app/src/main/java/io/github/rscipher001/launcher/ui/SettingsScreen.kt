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
    "statusbar" to "Full screen",
    "font" to "Font",
    "font.size" to "Text size",
    "header.date" to "Date",
    "clock.24h" to "24-hour clock",
    "header.device" to "Device name",
    "header.icons" to "Icons instead of labels",
    "header.unlocks" to "Unlock count",
    "header.battery" to "Battery",
    "header.network" to "Network",
    "header.alarm" to "Next alarm",
    "header.profiles" to "Work / private state",
    "header.media" to "Now playing",
    "header.todo" to "Open todos",
    "header.status" to "Script status lines",
    "header.autohide" to "Shrink while using the log",
    "widgets.home" to "Widgets on home",
    "widgets.page" to "Widgets page (swipe sideways)",
    "widgets.height" to "Widget area height (dp)",
    "log.time" to "Times",
    "log.dim" to "Dim older output",
    "log.spacing" to "Space between commands (dp)",
    "log.lineheight" to "Line height (dp)",
    "log.keepdays" to "Keep log for (days)",
    "log.fresh" to "Fresh screen after (minutes away)",
    "suggestions.auto" to "Only while typing",
    "suggestions.idle" to "Favourites & most-used on empty prompt",
    "suggestions" to "How many",
    "suggestions.style" to "Style while typing",
    "selector.rail" to "App selector (right edge)",
    "selector.height" to "App selector height (dp)",
    "suggestions.rows" to "Chip rows",
    "chips.height" to "Chip height (dp)",
    "keybar.show" to "Show the key bar",
    "keybar.auto" to "Only while typing",
    "keybar.height" to "Row height (dp)",
    "keybar" to "Row 1 keys",
    "keybar2" to "Row 2 keys",
    "prompt.height" to "Prompt height while typing (dp)",
    "keyboard.suggestions" to "Keyboard autocorrect & suggestions",
    "keyboard.onhome" to "Open the keyboard on Home",
    "launch.instant" to "Instant launch",
    "launch.instant.min" to "Letters before instant launch",
    "launch.top" to "Enter opens the best match",
    "launch.single" to "Enter opens the only match",
    "search.engine" to "Search engine",
    "search.url" to "Custom search URL",
    "gesture.doubletap" to "Double-tap empty space",
    "gesture.swipedown" to "Swipe down on the header",
    "gesture.swipeup.on" to "Swipe up on the prompt",
    "gesture.swipeup" to "Swipe up action",
    "log.packages" to "App installs, updates & uninstalls",
    "feeds.log" to "Show new items in the log",
    "feeds.log.lines" to "Items per feed per refresh",
    "feeds.read.shown" to "Items shown in the log count as read",
    "feeds.backlog.read" to "Mark existing posts read when subscribing",
    "feeds.interval" to "Refresh interval for new feeds (min)",
    "feeds.notify" to "Notify for new feeds",
    "feeds.wifi" to "Refresh on Wi-Fi only",
)

/** A settings page: grouped keys (a null group title means no sub-header). */
private class Category(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: Int,
    val groups: List<Pair<String?, List<ConfigKey<*>>>>,
    /** A manager screen this page leads to (shown first): title, subtitle, screen. */
    val manage: Triple<String, String, Screen>? = null,
) {
    val keys get() = groups.flatMap { it.second }
}

private val CATEGORIES = listOf(
    Category(
        "look", "Look & feel", "Theme, font, text size, full screen", R.drawable.ic_palette,
        listOf(
            "Terminal" to listOf(Keys.THEME, Keys.FONT, Keys.FONT_SIZE),
            "Screen" to listOf(Keys.STATUS_BAR),
            "Settings pages" to listOf(Keys.GUI_COLORS),
        ),
    ),
    Category(
        "home", "Home screen", "Clock, header items, widgets", R.drawable.ic_home,
        listOf(
            "Clock" to listOf(Keys.HEADER_DATE, Keys.CLOCK_24H, Keys.HEADER_DEVICE),
            "Header items" to listOf(
                Keys.HEADER_ICONS, Keys.HEADER_UNLOCKS, Keys.HEADER_BATTERY, Keys.HEADER_NETWORK, Keys.HEADER_ALARM,
                Keys.HEADER_PROFILES, Keys.HEADER_MEDIA, Keys.HEADER_TODO, Keys.HEADER_STATUS,
            ),
            "Behaviour" to listOf(Keys.HEADER_AUTOHIDE),
            "Widgets" to listOf(Keys.WIDGETS_PAGE, Keys.WIDGETS_HOME, Keys.WIDGETS_HEIGHT),
        ),
        manage = Triple("Arrange widgets", "Add, resize and reorder widgets", Screen.WIDGETS),
    ),
    Category(
        "log", "Log", "Times, spacing, line height, history", R.drawable.ic_stat_terminal,
        listOf(
            "Display" to listOf(Keys.SHOW_TIMESTAMPS, Keys.LOG_DIM, Keys.LOG_SPACING, Keys.LOG_LINE_HEIGHT),
            "Events" to listOf(Keys.LOG_PACKAGES),
            "History" to listOf(Keys.LOG_FRESH, Keys.LOG_KEEP_DAYS),
        ),
    ),
    Category(
        "feeds", "Feeds", "Refresh, log, read / unread, notifications", R.drawable.ic_rss,
        listOf(
            "In the log" to listOf(Keys.FEEDS_LOG, Keys.FEEDS_LOG_LINES),
            "Read / unread" to listOf(Keys.FEEDS_READ_SHOWN, Keys.FEEDS_BACKLOG_READ),
            "Refreshing" to listOf(Keys.FEEDS_WIFI),
            "New subscriptions" to listOf(Keys.FEEDS_INTERVAL, Keys.FEEDS_NOTIFY),
        ),
        manage = Triple("Manage feeds", "Subscriptions, unread counts, refresh, OPML import / export", Screen.FEEDS),
    ),
    Category(
        "typing", "Typing", "Suggestions, key bar, prompt, keyboard", R.drawable.ic_keyboard,
        listOf(
            "Suggestions" to listOf(Keys.SUGGESTIONS_STYLE, Keys.SELECTOR_RAIL, Keys.SELECTOR_HEIGHT, Keys.SUGGESTIONS_AUTO, Keys.SUGGESTION_ROWS, Keys.SUGGEST_IDLE, Keys.SUGGESTIONS, Keys.CHIP_HEIGHT),
            "Key bar" to listOf(Keys.KEYBAR_SHOW, Keys.KEYBAR_AUTO, Keys.KEYBAR_HEIGHT, Keys.KEYBAR, Keys.KEYBAR2),
            "Prompt & keyboard" to listOf(Keys.PROMPT_HEIGHT_TYPING, Keys.IME_SUGGESTIONS, Keys.FOCUS_ON_HOME),
        ),
    ),
    Category(
        "launch", "Launching & search", "Instant launch, Enter, web search", R.drawable.ic_search,
        listOf(
            "Launching" to listOf(Keys.LAUNCH_INSTANT, Keys.LAUNCH_INSTANT_MIN, Keys.LAUNCH_TOP, Keys.AUTO_LAUNCH),
            "Web search" to listOf(Keys.SEARCH_ENGINE, Keys.SEARCH_URL),
        ),
    ),
    Category(
        "gestures", "Gestures", "Double-tap and swipes", R.drawable.ic_touch,
        listOf(null to listOf(Keys.GESTURE_DOUBLE_TAP, Keys.GESTURE_SWIPE_DOWN, Keys.GESTURE_SWIPE_UP_ON, Keys.GESTURE_SWIPE_UP)),
    ),
)

/** Keys no page lists (e.g. added later) still get a home under "More". */
private val OTHER: Category by lazy {
    val listed = CATEGORIES.flatMap { it.keys }.toSet()
    Category("other", "More", "Everything else", R.drawable.ic_tune, listOf(null to Keys.ALL.filter { it !in listed }))
}

private fun allCategories() = CATEGORIES + listOfNotNull(OTHER.takeIf { it.keys.isNotEmpty() })

private fun matches(key: ConfigKey<*>, q: String): Boolean {
    val title = TITLES[key.key] ?: key.key
    return listOf(title, key.key, key.help).any { it.contains(q, ignoreCase = true) }
}

/**
 * Material 3 Expressive settings. The main page has a search box, the setting categories, the
 * manager pages and backup; each category opens its own page with grouped rows. Each row observes
 * only its own value, so a change redraws one row.
 */
@Composable
fun SettingsScreen(
    vm: TerminalViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onImportFont: () -> Unit = {},
) {
    val config = vm.config
    val version = vm.config.version.collectAsStateWithLifecycle()
    val useSystemColors by remember { derivedStateOf { version.value; config[Keys.GUI_COLORS] == "system" } }
    val notice by vm.notice.collectAsStateWithLifecycle()
    // Held by the view model, so returning from a manager screen (Feeds, Widgets) reopens this page.
    var openId by vm.settingsCategory
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val open = allCategories().firstOrNull { it.id == openId }
    // Back closes a category page first, then the search, then Settings.
    BackHandler {
        when {
            open != null -> openId = null
            query.isNotEmpty() -> query = ""
            else -> vm.closeScreen()
        }
    }

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
                    title = { Text(open?.title ?: "Settings") },
                    subtitle = { Text(open?.subtitle ?: "Kolahal") },
                    navigationIcon = { BackButton { if (open != null) openId = null else vm.closeScreen() } },
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
                if (open != null) {
                    open.manage?.let { (title, subtitle, screen) ->
                        item(key = "manage:${open.id}") {
                            Spacer(Modifier.height(8.dp))
                            ActionGroup(listOf(Action(open.icon, title, subtitle, chevron = true) { vm.openScreen(screen) }))
                        }
                    }
                    for ((title, keys) in open.groups) {
                        if (keys.isEmpty()) continue
                        item(key = "h:${open.id}:$title") { if (title != null) GroupHeader(title) else Spacer(Modifier.height(8.dp)) }
                        keys.forEachIndexed { index, key ->
                            item(key = key.key) { SettingItem(vm, config, key, version, index, keys.size, onImportFont) }
                        }
                    }
                    return@LazyColumn
                }

                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search settings") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            { IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), "Clear") } }
                        } else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    )
                }

                if (query.isNotBlank()) {
                    val hits = allCategories().flatMap { c -> c.keys.filter { matches(it, query.trim()) }.map { c to it } }
                    if (hits.isEmpty()) {
                        item(key = "nohits") {
                            Text(
                                "No setting matches “${query.trim()}”",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    hits.groupBy({ it.first }, { it.second }).forEach { (c, keys) ->
                        item(key = "h:hit:${c.id}") { GroupHeader(c.title) }
                        keys.forEachIndexed { index, key ->
                            item(key = "hit:" + key.key) { SettingItem(vm, config, key, version, index, keys.size, onImportFont) }
                        }
                    }
                    return@LazyColumn
                }

                item(key = "h:settings") { GroupHeader("Settings") }
                item(key = "categories") {
                    ActionGroup(allCategories().map { c -> Action(c.icon, c.title, c.subtitle, chevron = true) { openId = c.id } })
                }
                item(key = "h:manage") { GroupHeader("Manage") }
                item(key = "manage") {
                    ActionGroup(
                        listOf(
                            Action(R.drawable.ic_code, "Scripts", "Lua / JS scripts, timers, triggers, library", chevron = true) { vm.openScreen(Screen.SCRIPTS) },
                            Action(R.drawable.ic_rss, "Feeds", "RSS / Atom subscriptions, OPML import / export", chevron = true) { vm.openScreen(Screen.FEEDS) },
                            Action(R.drawable.ic_widgets, "Widgets", "Add, resize and arrange widgets", chevron = true) { vm.openScreen(Screen.WIDGETS) },
                            Action(R.drawable.ic_check, "Setup checklist", "Default launcher, permissions, battery", chevron = true) { vm.openScreen(Screen.SETUP) },
                        )
                    )
                }
                item(key = "h:backup") { GroupHeader("Backup") }
                item(key = "backup") {
                    ActionGroup(
                        listOf(
                            Action(R.drawable.ic_download, "Export everything", "Settings, apps, scripts, todos, notes, reminders, feeds and the log in one file", onClick = onExportBackup),
                            Action(R.drawable.ic_upload, "Import a backup", "Restores everything (replaces todos, notes, feeds and the log)", onClick = onImportBackup),
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingItem(
    vm: TerminalViewModel,
    config: Config,
    key: ConfigKey<*>,
    version: State<Int>,
    index: Int,
    count: Int,
    onImportFont: () -> Unit = {},
) {
    // Only this row recomposes when its own value changes.
    val value by remember(key) { derivedStateOf { version.value; config.display(key) } }
    val changed by remember(key) { derivedStateOf { version.value; !config.isDefault(key) } }
    val shapes = segShapes(index, count)
    val title = TITLES[key.key] ?: key.key
    // Reset sits on the key-name line, so it never narrows the controls below it.
    val supporting: @Composable () -> Unit = {
        Column {
            Text(key.help.replaceFirstChar { it.uppercase() })
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
                        } else if (key == Keys.FONT) {
                            FontChoices(value, { vm.setConfig(key, it) }, onImportFont)
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
                                // Ticks only for short ranges; wide ones (dp sizes, days) slide smoothly.
                                steps = (key.range.last - key.range.first - 1).let { if (it in 1..11) it else 0 },
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

data class Action(val icon: Int, val title: String, val subtitle: String, val chevron: Boolean = false, val onClick: () -> Unit)

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
                trailingContent = if (a.chevron) {
                    { Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else null,
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
