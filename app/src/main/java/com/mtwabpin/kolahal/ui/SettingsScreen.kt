package com.mtwabpin.kolahal.ui

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
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.ConfigKey
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.terminal.Screen
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import kotlin.math.roundToInt

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
                            Action(R.drawable.ic_download, "Export everything", "Settings, apps, scripts, todos, notes, reminders, feeds and the log in one file (notification and call lines stay on the phone)", onClick = onExportBackup),
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
                val slider = remember(key) {
                    // Ticks only for short ranges; wide ones (dp sizes, days) slide smoothly.
                    val steps = (key.range.last - key.range.first - 1).let { if (it in 1..11) it else 0 }
                    SliderState(current, steps, key.range.first.toFloat()..key.range.last.toFloat())
                }
                slider.value = current
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
                                state = slider,
                                onValueChange = { current = it },
                                onValueChangeFinished = { vm.setConfig(key, current.roundToInt().toString()) },
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
