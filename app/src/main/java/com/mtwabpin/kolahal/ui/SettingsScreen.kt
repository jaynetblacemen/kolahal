package com.mtwabpin.kolahal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.clickable
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.CustomThemes
import com.mtwabpin.kolahal.data.ConfigKey
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.terminal.Screen
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Settings, with home drawn underneath while a look setting is being changed (a slider dragged,
 * the eye held): Settings fades away around the control and home shows as it will look. Home only
 * looks: it takes no touches.
 */
@Composable
fun SettingsOverHome(
    vm: TerminalViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onImportFont: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        val peek by vm.peek.collectAsStateWithLifecycle()
        // Kept after the peek ends, so home fades out instead of vanishing under Settings.
        val lastPeek = remember { arrayOfNulls<TerminalViewModel.Peek>(1) }
        if (peek != null) lastPeek[0] = peek
        AnimatedVisibility(
            visible = peek != null,
            enter = fadeIn(tween(PEEK_FADE_MS)),
            exit = fadeOut(tween(PEEK_FADE_MS)),
        ) {
            lastPeek[0]?.let { p ->
                Box(Modifier.fillMaxSize()) {
                    TerminalScreen(vm, onRequestNotifications = {}, onPickFolder = {}, preview = p)
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                        },
                    )
                }
            }
        }
        SettingsScreen(vm, onExportBackup, onImportBackup, onImportFont)
    }
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
    val notice by vm.notice.collectAsStateWithLifecycle()
    // Held by the view model, so returning from a manager screen (Feeds, Widgets) reopens this page.
    var openId by vm.settingsCategory
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val open = allCategories().firstOrNull { it.id == openId }
    val crashPage = openId == CRASHES_PAGE
    // Look & feel → Your themes.
    val ownThemes = CustomThemes.themes.collectAsStateWithLifecycle().value.keys.sorted()
    val themeInUse by remember { derivedStateOf { version.value; config[Keys.THEME] } }
    val inUse = themeInUse
    // Settings another one turns off (the key bar's rows while the key bar is off) show once it is on.
    val applicable by remember { derivedStateOf { version.value; Keys.ALL.filterTo(HashSet()) { applies(it, config) } } }
    // Read here, not in the list's content below: a derived value read while the list measures can
    // deadlock with a snapshot applied from another thread (Compose tests do that).
    val applicableKeys = applicable
    val context = LocalContext.current
    // About → Crash reports: read off the main thread, and again after they are deleted.
    var crashReads by remember { mutableIntStateOf(0) }
    var openCrash by remember { mutableStateOf<CrashRow?>(null) }
    val crashes by produceState<List<CrashRow>?>(null, crashReads) {
        value = withContext(Dispatchers.IO) { crashRows(context) }
    }
    val crashList = crashes
    // Showing home through Settings (a slider dragged, the eye held): all but that slider fades away.
    val peek by vm.peek.collectAsStateWithLifecycle()
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (peek == null) 1f else 0f, androidx.compose.animation.core.tween(PEEK_FADE_MS), label = "peek",
    )
    val tapScope = rememberCoroutineScope()
    // Back closes a category page first, then the search, then Settings.
    BackHandler {
        when {
            open != null || crashPage -> openId = null
            query.isNotEmpty() -> query = ""
            else -> vm.closeScreen()
        }
    }

    GuiTheme {
      Box(Modifier.fillMaxSize()) {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it.text) } }

        Scaffold(
            modifier = Modifier.imePadding().nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = shown),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(open?.title ?: if (crashPage) "Crash reports" else "Settings") },
                    subtitle = { Text(open?.subtitle ?: if (crashPage) "Kept on this phone" else "Kolahal") },
                    navigationIcon = { BackButton { if (open != null || crashPage) openId = null else vm.closeScreen() } },
                    actions = {
                        PEEK_PAGES[open?.id]?.let { typing ->
                            PeekButton(
                                onHold = { holding -> vm.peek(if (holding) TerminalViewModel.Peek(null, typing) else null) },
                                onTap = {
                                    tapScope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        snackbar.showSnackbar("Tap and hold the eye to see home")
                                    }
                                },
                            )
                        }
                    },
                    scrollBehavior = scroll,
                    modifier = Modifier.graphicsLayer { alpha = shown },
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
                            PeekFade(peek) {
                                Spacer(Modifier.height(8.dp))
                                ActionGroup(listOf(Action(open.icon, title, subtitle, chevron = true) { vm.openScreen(screen) }))
                            }
                        }
                    }
                    for ((title, all) in open.groups) {
                        val keys = all.filter { it in applicableKeys }
                        if (keys.isEmpty()) continue
                        item(key = "h:${open.id}:$title") {
                            PeekFade(peek, modifier = Modifier.animateItem()) { if (title != null) GroupHeader(title) else Spacer(Modifier.height(8.dp)) }
                        }
                        keys.forEachIndexed { index, key ->
                            item(key = key.key) {
                                PeekFade(peek, keep = peek?.key == key.key, modifier = Modifier.animateItem()) {
                                    SettingItem(vm, config, key, version, index, keys.size, onImportFont)
                                }
                            }
                        }
                    }
                    if (open.id == "look") {
                        item(key = "h:own-themes") { PeekFade(peek) { GroupHeader("Your themes") } }
                        item(key = "own-themes") {
                            PeekFade(peek) {
                                ActionGroup(
                                    ownThemes.map { name ->
                                        Action(R.drawable.ic_palette, name, if (name == inUse) "In use · tap to change it" else "Tap to change it", chevron = true) {
                                            vm.openThemeBuilder(name)
                                        }
                                    } + Action(R.drawable.ic_add, "Create a theme", "Start from the theme in use and pick any colour", chevron = true) {
                                        vm.openThemeBuilder(null)
                                    },
                                )
                            }
                        }
                    }
                    return@LazyColumn
                }

                if (crashPage) {
                    crashReportsPage(
                        crashList,
                        onOpen = { openCrash = it },
                        onDeleteAll = {
                            val n = com.mtwabpin.kolahal.CrashReports.clear(context)
                            crashReads++
                            tapScope.launch { snackbar.showSnackbar(if (n == 1) "Deleted 1 crash report" else "Deleted $n crash reports") }
                        },
                    )
                    return@LazyColumn
                }

                item(key = "tabs") { PeekFade(peek) { FeatureTabs(vm, Screen.SETTINGS) } }
                item(key = "search") {
                  PeekFade(peek) {
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
                }

                if (query.isNotBlank()) {
                    val hits = allCategories().flatMap { c -> c.keys.filter { it in applicableKeys && matches(it, query.trim()) }.map { c to it } }
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
                        item(key = "h:hit:${c.id}") { PeekFade(peek) { GroupHeader(c.title) } }
                        keys.forEachIndexed { index, key ->
                            item(key = "hit:" + key.key) { PeekFade(peek, keep = peek?.key == key.key) { SettingItem(vm, config, key, version, index, keys.size, onImportFont) } }
                        }
                    }
                    return@LazyColumn
                }

                item(key = "h:settings") { GroupHeader("Settings") }
                item(key = "categories") {
                    // Feed settings live in the Feeds tab; search still finds them here.
                    ActionGroup(allCategories().filter { it.id != FEEDS_CATEGORY }.map { c -> Action(c.icon, c.title, c.subtitle, chevron = true) { openId = c.id } })
                }
                item(key = "h:manage") { GroupHeader("Manage") }
                item(key = "manage") {
                    ActionGroup(
                        listOf(
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
                item(key = "h:about") { GroupHeader("About") }
                item(key = "about") {
                    val kept = when (val n = crashList?.size) {
                        null -> "Reports kept on this phone"
                        0 -> "None so far"
                        1 -> "1 report kept on this phone"
                        else -> "$n reports kept on this phone"
                    }
                    ActionGroup(
                        listOf(
                            Action(R.drawable.ic_book, "Guide", "How everything works, on the Kolahal website") {
                                if (!openGuide(context)) tapScope.launch { snackbar.showSnackbar("No browser to open $GUIDE_URL") }
                            },
                            Action(R.drawable.ic_bug, "Crash reports", kept, chevron = true) { openId = CRASHES_PAGE },
                            Action(R.drawable.ic_mail, "Email the developer", DEVELOPER_EMAIL) {
                                if (!emailDeveloper(context, VERSION_TEXT, aboutThisPhone())) {
                                    tapScope.launch { snackbar.showSnackbar("No app on this phone can send email") }
                                }
                            },
                            Action(R.drawable.ic_info, "Version", "$VERSION_TEXT · tap to copy") {
                                context.getSystemService(android.content.ClipboardManager::class.java)
                                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Kolahal version", VERSION_TEXT))
                                // Android 13 and later confirm a copy themselves.
                                if (android.os.Build.VERSION.SDK_INT < 33) tapScope.launch { snackbar.showSnackbar("Copied") }
                            },
                        )
                    )
                }
            }
        }
        if (peek != null) PeekHint(Modifier.align(Alignment.TopCenter))
        // A tapped crash report. The sheet stays after Email, so the report can be deleted once sent.
        openCrash?.let { c ->
            CrashSheet(
                c,
                onDismiss = { openCrash = null },
                onEmail = {
                    if (!emailDeveloper(context, "$VERSION_TEXT: ${c.title}", c.report.text())) {
                        tapScope.launch { snackbar.showSnackbar("No app on this phone can send email") }
                    }
                },
                onDelete = {
                    openCrash = null
                    c.report.delete()
                    crashReads++
                    tapScope.launch { snackbar.showSnackbar("Crash report deleted") }
                },
            )
        }
      }
    }
}

/** Fades away while Settings shows home through it, unless it is the setting being changed ([keep]). */
@Composable
private fun PeekFade(
    peek: TerminalViewModel.Peek?,
    keep: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        if (peek == null || keep) 1f else 0f, androidx.compose.animation.core.tween(PEEK_FADE_MS), label = "fade",
    )
    Box(modifier.graphicsLayer { this.alpha = alpha }) { content() }
}

/** How long Settings and home take to fade into each other when a peek starts or ends. */
internal const val PEEK_FADE_MS = 220

/** A press shorter than this on the eye is a tap: it says to hold instead of flashing home. */
private const val PEEK_HOLD_MS = 150L

/** The eye in the top bar: home shows while it is held; a tap ([onTap]) explains that. */
@Composable
private fun PeekButton(onHold: (Boolean) -> Unit, onTap: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { contentDescription = "Hold to see home" }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        kotlinx.coroutines.delay(PEEK_HOLD_MS)
                        onHold(true)
                    }
                    val released = tryAwaitRelease()
                    if (hold.isActive) {
                        hold.cancel()
                        if (released) onTap()
                    } else {
                        onHold(false)
                    }
                })
            },
    ) {
        Icon(painterResource(R.drawable.ic_visibility), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Shown at the top while home shows through Settings. */
@Composable
private fun PeekHint(modifier: Modifier) {
    Text(
        "Let go to come back",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
internal fun SettingItem(
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
    // The setting's name, then a reset icon right after it once it is changed (a switch is reset by
    // switching it): in the same place on every row, whatever sits on the right of the title.
    // An icon rather than a Reset button keeps this line short; it never narrows the controls below.
    val supporting: @Composable () -> Unit = {
        Column {
            Text(key.help.replaceFirstChar { it.uppercase() })
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    key.key,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                )
                if (changed && key !is ConfigKey.Bool) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        painterResource(R.drawable.ic_restart),
                        contentDescription = "Reset to default",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { vm.resetConfig(key) }
                            .padding(4.dp),
                    )
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
                            // Each card in the version showing now: dark or light, as the phone (or Always dark) says.
                            // Read with the version, so toggling Always dark redraws the cards.
                            val dark = androidx.compose.foundation.isSystemInDarkTheme() || run { version.value; config[Keys.THEME_ALWAYS_DARK] }
                            ThemeSwatches(value, dark) { vm.setConfig(key, it) }
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
                            // A look setting (PEEK_SLIDERS): while dragged, home shows through Settings at the
                            // value under the finger, which is saved on release.
                            val peekTyping = PEEK_SLIDERS[key.key]
                            val interactions = remember { MutableInteractionSource() }
                            val dragging by interactions.collectIsDraggedAsState()
                            if (peekTyping != null) LaunchedEffect(dragging) {
                                if (dragging) vm.peek(TerminalViewModel.Peek(key.key, peekTyping))
                                else if (vm.peek.value?.key == key.key) vm.peek(null)
                            }
                            // Leaving mid-drag (Home, another screen) never finishes the change: drop the preview.
                            DisposableEffect(key) { onDispose { config.preview(key, null) } }
                            Slider(
                                state = slider,
                                onValueChange = {
                                    current = it
                                    if (peekTyping != null) config.preview(key, current.roundToInt().toString())
                                },
                                onValueChangeFinished = {
                                    vm.setConfig(key, current.roundToInt().toString())
                                    config.preview(key, null)
                                },
                                interactionSource = interactions,
                                modifier = Modifier.testTag("slider ${key.key}"),
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
private fun ThemeSwatches(selected: String, dark: Boolean, onPick: (String) -> Unit) {
    // Not lazy, for the reason in FontChoices.
    // The built-in themes, then the ones made in the theme builder (they come and go).
    val own = CustomThemes.themes.collectAsStateWithLifecycle().value.keys.sorted()
    val scroll = rememberScrollState()
    val step = with(LocalDensity.current) { (112 + 10).dp.roundToPx() }
    // Opens at the theme in use (half a card of the one before shows), not at the strip's start.
    LaunchedEffect(Unit) {
        val at = (Keys.BUILT_IN_THEMES + own).indexOf(selected)
        if (at > 0) scroll.scrollTo(at * step - step / 2)
    }
    Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        (Keys.BUILT_IN_THEMES + own).forEach { name ->
            val p = Palettes.named(name, dark)
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

/**
 * A settings category's rows, grouped, in another screen: the Feeds tab shows the feeds category.
 * [applicable] is read by the caller outside the list's content (see [SettingsScreen]).
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.categorySettings(
    vm: TerminalViewModel,
    id: String,
    version: State<Int>,
    applicable: Set<ConfigKey<*>>,
) {
    val category = allCategories().firstOrNull { it.id == id } ?: return
    for ((title, all) in category.groups) {
        val keys = all.filter { it in applicable }
        if (keys.isEmpty()) continue
        item(key = "h:$id:$title") { if (title != null) GroupHeader(title) else Spacer(Modifier.height(8.dp)) }
        keys.forEachIndexed { index, key ->
            item(key = key.key) { SettingItem(vm, vm.config, key, version, index, keys.size, onImportFont = {}) }
        }
    }
}
