package io.github.rscipher001.launcher.ui

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.togetherWith
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Size
import io.github.rscipher001.launcher.R
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rscipher001.launcher.apps.Profile
import io.github.rscipher001.launcher.apps.ProfileKind
import io.github.rscipher001.launcher.data.Config
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.data.LogEntry
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.status.Status
import io.github.rscipher001.launcher.terminal.LogAction
import io.github.rscipher001.launcher.terminal.LogRow
import io.github.rscipher001.launcher.terminal.Suggestion
import io.github.rscipher001.launcher.terminal.TerminalViewModel
import io.github.rscipher001.launcher.terminal.UiEvent
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(vm: TerminalViewModel, onRequestNotifications: () -> Unit, onPickFolder: () -> Unit) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val unlocks by vm.unlocksToday.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val configVersion by vm.config.version.collectAsStateWithLifecycle()
    val config = vm.config
    val listState = rememberLazyListState()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val reversedRows = remember(rows) { rows.asReversed() }
    var pinned by remember { mutableStateOf(true) }
    var promptFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val hasHardwareKeyboard = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS

    // Read through updated state so a keyboard attached later (configChanges=keyboard) is seen.
    val currentKeyboard by rememberUpdatedState(keyboard)
    val currentHasHardwareKeyboard by rememberUpdatedState(hasHardwareKeyboard)
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                UiEvent.ScrollToBottom -> { pinned = true; listState.scrollToItem(0) }
                // Handled by MainActivity so it works from every page (setup, script manager).
                UiEvent.RequestNotificationPermission, UiEvent.RequestContactsPermission, UiEvent.RequestCalendarPermission, UiEvent.PickFont -> Unit
                UiEvent.ShowQuick -> Unit // opened by the collector below, where the menu lives
                UiEvent.FocusPrompt -> {
                    runCatching { focus.requestFocus() }
                    if (!currentHasHardwareKeyboard) currentKeyboard?.show()
                }
                UiEvent.HideKeyboard -> currentKeyboard?.hide()
                UiEvent.PickScriptFolder -> onPickFolder()
            }
        }
    }
    // Stay pinned to the newest line (index 0 with reverseLayout) unless the user scrolled up.
    // Only user scrolls change `pinned`: inserting items shifts the anchor and must not unpin.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.isScrollInProgress to
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 48)
        }.collect { (scrolling, atBottom) -> if (scrolling) pinned = atBottom }
    }
    // Keyed on the newest *row* (rows are built after entries), so the scroll targets the new line.
    LaunchedEffect(reversedRows.firstOrNull()?.key) {
        if (pinned) listState.scrollToItem(0)
    }
    // Near the oldest loaded line (top of the reversed list): load the next page.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 5
        }.collect { nearTop -> if (nearTop) vm.loadOlder() }
    }
    // Keyboard-first: grab focus straight away when a physical keyboard is attached.
    LaunchedEffect(hasHardwareKeyboard) {
        if (hasHardwareKeyboard) runCatching { focus.requestFocus() }
    }

    val maxSuggestions = remember(configVersion) { config[Keys.SUGGESTIONS] }
    val commandNames by vm.commandNames.collectAsStateWithLifecycle()
    val prefsVersion by vm.appPrefsVersion.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val suggestions = remember(vm.input.text, apps, maxSuggestions, commandNames, prefsVersion, contacts) {
        vm.suggestions(vm.input.text).take(maxSuggestions)
    }
    val timeMode = remember(configVersion) {
        when (config[Keys.SHOW_TIMESTAMPS]) {
            "all" -> TimeMode.ALL
            "off" -> TimeMode.OFF
            else -> TimeMode.COMMANDS
        }
    }
    val dimOld = remember(configVersion) { config[Keys.LOG_DIM] }
    var menu by remember { mutableStateOf<ActionMenu?>(null) }
    LaunchedEffect(Unit) {
        vm.events.collect { if (it == UiEvent.ShowQuick) menu = ActionMenu("Quick", vm.quickActions(), null) }
    }
    menu?.let { ActionSheet(it, onDismiss = { menu = null }) }
    val commandGap = remember(configVersion) { config[Keys.LOG_SPACING].dp }
    val showGutter = remember(configVersion) { config[Keys.LOG_GUTTER] }
    // Monospace advance, used to indent wrapped lines past their prefix.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(Term.fontSize, density) {
        with(density) { (measurer.measure("0000000000", Term.mono).size.width / 10f).toSp() }
    }
    val lineTimeFormat = remember(configVersion) {
        SimpleDateFormat(if (config[Keys.CLOCK_24H]) "HH:mm" else "h:mm", Locale.getDefault())
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Term.bg)
            .cutoutSafePadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        val headerCollapsed by vm.headerCollapsed.collectAsStateWithLifecycle()
        val headerHidden by vm.headerHidden.collectAsStateWithLifecycle()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val headerScroll = remember(vm, density) {
            val threshold = with(density) { 24.dp.toPx() }
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                var travelled = 0f
                override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                    val dy = available.y
                    if (dy == 0f) return Offset.Zero
                    // Reversed log: a positive delta (finger moving down) reveals older lines.
                    if (travelled != 0f && (dy > 0) != (travelled > 0)) travelled = 0f
                    travelled += dy
                    when {
                        travelled > threshold -> { vm.hideHeader(); travelled = 0f }
                        travelled < -threshold -> { vm.collapseHeader(); travelled = 0f }
                    }
                    return Offset.Zero
                }
            }
        }
        // Header transitions slide like scrolling: up and out when hidden, back down when it
        // returns, and full ↔ one-line morph in place. The log follows the size change smoothly.
        androidx.compose.animation.AnimatedVisibility(
            visible = !headerHidden,
            enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top) +
                androidx.compose.animation.slideInVertically { -it },
            exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top) +
                androidx.compose.animation.slideOutVertically { -it },
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = headerCollapsed,
                transitionSpec = {
                    (androidx.compose.animation.slideInVertically { -it / 3 } + androidx.compose.animation.fadeIn())
                        .togetherWith(androidx.compose.animation.slideOutVertically { -it / 3 } + androidx.compose.animation.fadeOut())
                        .using(androidx.compose.animation.SizeTransform(clip = true))
                },
                contentAlignment = Alignment.TopStart,
                label = "header",
            ) { collapsed ->
                if (collapsed) CompactHeader(
                    status = status,
                    statusCount = if (config[Keys.HEADER_STATUS]) statuses.size else 0,
                    config = config,
                    version = configVersion,
                    onExpand = vm::expandHeader,
                    mediaPlaying = config[Keys.HEADER_MEDIA] && nowPlaying?.playing == true,
                ) else Header(
                    unlocks = unlocks,
                    profiles = profiles,
                    status = status,
                    statuses = statuses,
                    config = config,
                    version = configVersion,
                    onStatusTap = vm::runScriptNamed,
                    onSwipeDown = { vm.onGesture(Keys.GESTURE_SWIPE_DOWN) },
                    onTarget = vm::onHeaderTap,
                    onProfileTap = vm::onProfileTap,
                    nowPlaying = nowPlaying?.takeIf { config[Keys.HEADER_MEDIA] },
                    onMediaToggle = vm::mediaToggle,
                    onMediaNext = vm::mediaNext,
                    todoCount = vm.todoCount.collectAsStateWithLifecycle().value,
                )
            }
        }
        // widgets.home: widgets placed on home; they fold away with the header.
        HomeWidgets(vm, visible = !headerCollapsed)
        val hiddenLines by vm.hiddenLines.collectAsStateWithLifecycle()
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .fillMaxSize()
                // header.autohide by direction: toward older lines the header goes away entirely,
                // back toward the newest it returns as the one-line mini header.
                .nestedScroll(headerScroll)
                .testTag("log")
                .pinchToZoomFont(onChange = { Term.fontSize = it }, onEnd = vm::saveFontSize)
                // Taps that no log line consumed (empty space) bring up the keyboard.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { vm.onGesture(Keys.GESTURE_DOUBLE_TAP) },
                    ) {
                        runCatching { focus.requestFocus() }
                        keyboard?.show()
                    }
                },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (reversedRows.isEmpty() && hiddenLines == 0) {
                item(key = "empty-hint") {
                    Text(
                        "type an app name or a command · help lists everything · ↑ recalls history · " +
                            "tap a line to act, long-press for more · ⚙ settings",
                        style = Term.mono.copy(color = Term.dim),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(reversedRows, key = { it.key }, contentType = { if (it is LogRow.Entry) it.entry.kind else it.javaClass }) { row ->
                when (row) {
                    is LogRow.Day -> DayDivider(row)
                    is LogRow.Unread -> UnreadDivider(row)
                    is LogRow.Entry -> LogLine(
                        row = row,
                        timeMode = timeMode,
                        timeFormat = lineTimeFormat,
                        dim = dimOld,
                        topGap = if (row.blockStart) commandGap else 0.dp,
                        gutter = showGutter,
                        charWidth = charWidth,
                        highlighted = menu?.entryId == row.entry.id,
                        apps = vm::appsOf,
                        onTap = vm::onTap,
                        onLongPress = { e -> menu = ActionMenu(e.text, vm.actionsFor(e), e.id) },
                        onAppTap = vm::launchApp,
                        onAppLongPress = { app -> menu = ActionMenu(app.label, vm.appActions(app), null) },
                        onSwipeCopy = vm::swipeCopy,
                        onSwipeDelete = vm::swipeDelete,
                    )
                }
            }
            // log.fresh: the older output is hidden, not gone. Last item = top of the reversed list.
            if (hiddenLines > 0) {
                item(key = "hidden-lines") {
                    Text(
                        "▴ $hiddenLines earlier lines · tap to show",
                        style = Term.mono.copy(color = Term.dim),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "show earlier output") { vm.showHidden() }
                            .heightIn(min = 48.dp)
                            .wrapContentHeight(),
                    )
                }
            }
        }
        // Scrolled away from the newest lines: one tap back down.
        val awayFromBottom by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
        androidx.compose.animation.AnimatedVisibility(
            visible = awayFromBottom,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) {
            val scope = rememberCoroutineScope()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .testTag("jump-latest")
                    .size(48.dp)
                    .background(Term.surface, RoundedCornerShape(24.dp))
                    .border(1.dp, Term.prompt.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                    .clickable(onClickLabel = "jump to the newest lines") { pinned = true; scope.launch { listState.animateScrollToItem(0) } },
            ) {
                Text("↓", style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold, fontSize = (Term.fontSize + 4).sp))
            }
        }
        // [selector] selector.rail: fluid full-list app selector on the log's right edge, keyboard down only.
        val railOn = remember(configVersion) { config[Keys.SELECTOR_RAIL] }
        if (railOn && !WindowInsets.isImeVisible) {
            val pinnedKeys = remember(apps, prefsVersion) { vm.pinnedKeys() }
            AppSelector(
                apps = apps,
                pinned = pinnedKeys,
                onLaunch = vm::launchApp,
                onLongPress = { app -> menu = ActionMenu(app.label, vm.appActions(app), null) },
                heightDp = remember(configVersion) { config[Keys.SELECTOR_HEIGHT] },
                showHandle = remember(configVersion) { config[Keys.SELECTOR_HANDLE] },
            )
        }
        // [/selector]
        }
        // A hardware keyboard already has these keys: the bar follows the on-screen keyboard.
        val typing = promptFocused && WindowInsets.isImeVisible
        // suggestions.auto: hidden on an idle home; shown while typing, or whenever there is input
        // (so hardware-keyboard typing still gets them).
        val suggestionsAuto = remember(configVersion) { config[Keys.SUGGESTIONS_AUTO] }
        val undoEntry by vm.undoDelete.collectAsStateWithLifecycle()
        if (undoEntry != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(Term.surface).heightIn(min = 44.dp).padding(start = 12.dp),
            ) {
                Text("line deleted", style = Term.mono.copy(color = Term.dim), modifier = Modifier.weight(1f))
                Text(
                    "UNDO",
                    style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable(onClick = vm::undoSwipeDelete).heightIn(min = 44.dp).wrapContentHeight().padding(horizontal = 16.dp),
                )
            }
        }
        // [selector] suggestions.style: list (default, bottom-anchored above the prompt) or chips.
        val suggestionsStyle = remember(configVersion) { config[Keys.SUGGESTIONS_STYLE] }
        if (!suggestionsAuto || typing || vm.input.text.isNotEmpty()) {
            if (suggestionsStyle == "chips") {
                SuggestionStrip(suggestions, rows = remember(configVersion) { config[Keys.SUGGESTION_ROWS] }, onPick = vm::onSuggestion)
            } else {
                SuggestionList(
                    suggestions,
                    onPick = vm::onSuggestion,
                    onAppLongPress = { app -> menu = ActionMenu(app.label, vm.appActions(app), null) },
                )
            }
        }
        // [/selector]
        // keybar.auto: only while typing, so an idle home shows more of the log.
        val keyBarAuto = remember(configVersion) { config[Keys.KEYBAR_AUTO] }
        val promptTypingHeight = remember(configVersion) { config[Keys.PROMPT_HEIGHT_TYPING].dp }
        val keyBarOn = remember(configVersion) { config[Keys.KEYBAR_SHOW] }
        if (keyBarOn && (!keyBarAuto || typing)) {
            KeyBar(
                specs = remember(configVersion) { listOf(config[Keys.KEYBAR], config[Keys.KEYBAR2]) },
                rowHeight = remember(configVersion) { config[Keys.KEYBAR_HEIGHT].dp },
                onKey = { key ->
                    if (key == KeyBarKey.KBD) {
                        runCatching { focus.requestFocus() }
                        keyboard?.show()
                    } else {
                        vm.onKeyBar(key)
                    }
                },
                onText = vm::insert,
            )
        }
        Prompt(
            vm = vm,
            focus = focus,
            imeSuggestions = remember(configVersion) { config[Keys.IME_SUGGESTIONS] },
            onFocus = { promptFocused = it },
            // prompt.height applies while typing; idle keeps the roomy default.
            height = if (typing) promptTypingHeight else 52.dp,
            keyBarOn = keyBarOn,
            onQuick = { vm.openQuick() },
            onToggleKeyBar = {
                config.set(Keys.KEYBAR_SHOW, if (keyBarOn) "off" else "on")
                // The bar shows while typing (keybar.auto): turning it on brings the keyboard up too.
                if (!keyBarOn && keyBarAuto && !typing) {
                    runCatching { focus.requestFocus() }
                    keyboard?.show()
                }
            },
            onTapPrompt = {
                runCatching { focus.requestFocus() }
                keyboard?.show()
            },
        )
    }
}

/** What a tap on a header item does. */
enum class HeaderTarget { CLOCK, UNLOCKS, BATTERY, NETWORK, ALARM, SETTINGS, TODO }

/** One-line header while the log is in use: time, battery and how many status lines are hidden. */
@Composable
private fun CompactHeader(status: Status, statusCount: Int, config: Config, version: Int, onExpand: () -> Unit, mediaPlaying: Boolean = false) {
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(1_000L * (60 - (System.currentTimeMillis() / 1000 % 60)))
        }
    }
    val is24h = remember(version) { config[Keys.CLOCK_24H] }
    val timeFmt = remember(is24h) { SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault()) }
    val small = Term.mono.copy(fontSize = (Term.fontSize - 1).sp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("header-compact")
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 24.dp.toPx()) onExpand() },
                ) { _, dy -> total += dy }
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
    ) {
        Text(timeFmt.format(now), style = small.copy(color = Term.fg, fontWeight = FontWeight.Bold))
        status.batteryPct?.let { pct ->
            val color = when {
                status.charging -> Term.prompt
                pct <= 15 -> Term.error
                else -> Term.dim
            }
            if (config[Keys.HEADER_ICONS]) {
                Spacer(Modifier.width(10.dp))
                BatteryGlyph(pct, status.charging, color, (Term.fontSize + 2).dp)
                Text(" $pct%", style = small.copy(color = color))
            } else {
                Text("  bat $pct%" + if (status.charging) "+" else "", style = small.copy(color = color))
            }
        }
        if (mediaPlaying) Text("  ♪", style = small.copy(color = Term.prompt))
        if (statusCount > 0) Text("  · $statusCount status", style = small.copy(color = Term.dim))
        Spacer(Modifier.weight(1f))
        Text("▾", style = small.copy(color = Term.dim))
    }
}

/**
 * Clock line plus a row of status items that wrap as whole items. Items are tappable
 * (alarms, unlock history, battery, network, profile toggle); swipe down opens notifications.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    unlocks: Int,
    profiles: List<Profile>,
    status: Status,
    statuses: Map<String, String>,
    config: Config,
    version: Int,
    onStatusTap: (String) -> Unit,
    onSwipeDown: () -> Unit,
    onTarget: (HeaderTarget) -> Unit,
    onProfileTap: (Profile) -> Unit,
    nowPlaying: io.github.rscipher001.launcher.control.NowPlaying? = null,
    onMediaToggle: () -> Unit = {},
    onMediaNext: () -> Unit = {},
    todoCount: Int = 0,
) {
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(1_000L * (60 - (System.currentTimeMillis() / 1000 % 60)))
        }
    }
    val is24h = remember(version) { config[Keys.CLOCK_24H] }
    val timeFmt = remember(is24h) { SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val alarmFmt = remember(is24h) { SimpleDateFormat(if (is24h) "EEE HH:mm" else "EEE h:mm a", Locale.getDefault()) }
    val small = Term.mono.copy(fontSize = (Term.fontSize - 1).sp)

    val useIcons = config[Keys.HEADER_ICONS]
    val iconSize = (Term.fontSize + 3).dp

    /** A header item: an icon (header.icons) or a dim text label, then the value. */
    @Composable
    fun Item(
        label: String,
        value: String,
        valueColor: Color = Term.fg,
        icon: (@Composable () -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                // 48dp touch target even for short items like ⚙.
                .clickable(onClickLabel = label.ifEmpty { null }, onClick = onClick)
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .padding(horizontal = 6.dp),
        ) {
            if (useIcons && icon != null) {
                icon()
                if (value.isNotEmpty()) Spacer(Modifier.width(4.dp))
            }
            Text(
                buildAnnotatedString {
                    if (label.isNotEmpty() && !(useIcons && icon != null)) withStyle(SpanStyle(color = Term.dim)) { append("$label ") }
                    withStyle(SpanStyle(color = valueColor, fontWeight = FontWeight.Bold)) { append(value) }
                },
                style = small,
            )
        }
    }

    @Composable
    fun Glyph(res: Int, tint: Color = Term.dim) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(res),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = Modifier.size(iconSize),
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .testTag("header")
            // Swipe down on the header pulls down the notification shade.
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 48.dp.toPx()) onSwipeDown() },
                ) { _, dy -> total += dy }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onTarget(HeaderTarget.CLOCK) }.heightIn(min = 48.dp),
        ) {
            Text(
                timeFmt.format(now),
                style = Term.mono.copy(color = Term.fg, fontWeight = FontWeight.Bold, fontSize = (Term.fontSize + 8).sp),
            )
            if (config[Keys.HEADER_DATE]) {
                Text(
                    "  " + dateFmt.format(now),
                    style = Term.mono.copy(color = Term.dim),
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            if (config[Keys.HEADER_DEVICE]) {
                Spacer(Modifier.weight(1f))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Term.prompt, fontWeight = FontWeight.Bold)) { append("kolahal") }
                        withStyle(SpanStyle(color = Term.dim)) { append("@" + Build.MODEL.lowercase().replace(' ', '-')) }
                    },
                    style = small,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        // `now` ticks each minute, so a paused session drops out after 30 minutes.
        if (nowPlaying != null && nowPlaying.showInHeader(now.time)) NowPlayingLine(nowPlaying, onMediaToggle, onMediaNext)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (config[Keys.HEADER_UNLOCKS]) {
                Item("unlocks", unlocks.toString(), icon = { Glyph(R.drawable.ic_lock_open) }) { onTarget(HeaderTarget.UNLOCKS) }
            }
            val pct = status.batteryPct
            if (config[Keys.HEADER_BATTERY] && pct != null) {
                val color = when {
                    status.charging -> Term.prompt
                    pct <= 15 -> Term.error
                    else -> Term.fg
                }
                Item(
                    "bat", "$pct%" + if (status.charging && !useIcons) "+" else "", color,
                    icon = { BatteryGlyph(pct, status.charging, color, iconSize) },
                ) { onTarget(HeaderTarget.BATTERY) }
            }
            if (config[Keys.HEADER_NETWORK]) {
                val offline = status.network == "offline"
                val netIcon = when (status.network) {
                    "wifi" -> R.drawable.ic_wifi
                    "cell" -> R.drawable.ic_signal
                    "offline" -> R.drawable.ic_cloud_off
                    else -> null
                }
                // With an icon the kind is obvious; only unusual kinds (eth, vpn…) keep their name.
                Item(
                    "net", if (useIcons && netIcon != null) "" else status.network, if (offline) Term.error else Term.fg,
                    icon = netIcon?.let { res -> { Glyph(res, if (offline) Term.error else Term.fg) } },
                ) { onTarget(HeaderTarget.NETWORK) }
            }
            if (config[Keys.HEADER_ALARM] && status.nextAlarm != null) {
                Item("alarm", alarmFmt.format(Date(status.nextAlarm)), icon = { Glyph(R.drawable.ic_alarm) }) { onTarget(HeaderTarget.ALARM) }
            }
            if (config[Keys.HEADER_TODO] && todoCount > 0) {
                Item("todo", todoCount.toString(), icon = { Glyph(R.drawable.ic_check) }) { onTarget(HeaderTarget.TODO) }
            }
            Item("", "⚙", Term.dim) { onTarget(HeaderTarget.SETTINGS) }
            if (config[Keys.HEADER_PROFILES]) {
                profiles.filter { it.kind != ProfileKind.PERSONAL && it.kind != ProfileKind.CLONE }.forEach { p ->
                    val color = if (p.quiet) Term.dim else Term.profileColor(p.kind)
                    val res = if (p.kind == ProfileKind.WORK) R.drawable.ic_work else R.drawable.ic_lock
                    Item(p.kind.label, if (p.quiet) "off" else "on", color, icon = { Glyph(res, color) }) {
                        onProfileTap(p)
                    }
                }
            }
        }
        if (config[Keys.HEADER_STATUS]) {
            statuses.forEach { (name, text) ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Term.dim)) { append("$name ▸ ") }
                        withStyle(SpanStyle(color = Term.script)) { append(text) }
                    },
                    style = small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onStatusTap(name) }
                        .heightIn(min = 44.dp)
                        .wrapContentHeight(),
                )
            }
        }
    }
}

@Composable
private fun SuggestionStrip(items: List<Suggestion>, rows: Int, onPick: (Suggestion) -> Unit) {
    if (items.isEmpty()) return
    val key = { _: Int, s: Suggestion -> s.key }
    // suggestions.rows 2: twice as many chips in reach before any sideways scrolling.
    if (rows >= 2 && items.size > 3) {
        androidx.compose.foundation.lazy.staggeredgrid.LazyHorizontalStaggeredGrid(
            rows = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height((Term.chipHeight * 2 + 6).dp)
                .testTag("suggestions")
                .padding(vertical = 2.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalItemSpacing = 8.dp,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items.size, key = { key(it, items[it]) }) { index -> SuggestionChip(index, items[index], onPick) }
        }
        return
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("suggestions")
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items, key = key) { index, s -> SuggestionChip(index, s, onPick) }
    }
}

@Composable
private fun SuggestionChip(index: Int, s: Suggestion, onPick: (Suggestion) -> Unit) {
        run {
            val (color, tag) = when (s) {
                is Suggestion.App -> Term.profileColor(s.app.profile.kind) to s.app.profile.kind.tag
                is Suggestion.Completion -> Term.dim to null
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = Term.chipHeight.dp)
                    .alpha(if (s is Suggestion.App && s.app.profile.quiet) 0.45f else 1f)
                    // The first chip is what Enter / TAB act on: fill it so that is visible.
                    .background(if (index == 0) color.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(4.dp))
                    .border(1.dp, color.copy(alpha = if (index == 0) 0.9f else 0.6f), RoundedCornerShape(4.dp))
                    .clickable { onPick(s) }
                    .padding(horizontal = 12.dp, vertical = (if (Term.chipHeight < 40) 2 else 8).dp),
            ) {
                if (tag != null && tag != ProfileKind.PERSONAL.tag) {
                    Text(tag, style = Term.mono.copy(color = color, fontWeight = FontWeight.Bold, fontSize = (Term.fontSize - 2).sp))
                    Spacer(Modifier.width(6.dp))
                }
                // Commands carry the prompt's `$`, so "maps" (command) and "Maps" (app) read apart.
                if (s is Suggestion.Completion) {
                    Text("$ ", style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold))
                }
                Text(s.label, style = Term.mono.copy(color = if (s is Suggestion.App) Term.fg else Term.dim))
            }
        }
}

/** Named keys the touch key bar understands; anything else in the spec is inserted literally. */
enum class KeyBarKey(val label: String) {
    ESC("ESC"), TAB("TAB"), UP("↑"), DOWN("↓"), LEFT("←"), RIGHT("→"),
    HOME("⇤"), END("⇥"), ENTER("⏎"), KBD("⌨"), SPACE("␣"), BKSP("⌫");

    companion object {
        fun of(token: String) = entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
    }
}

/** Marker for a cancelled key press (distinct from the timeout's null). */
private object CANCELLED

/** Keys that repeat while held. */
private val REPEATING = setOf(KeyBarKey.UP, KeyBarKey.DOWN, KeyBarKey.LEFT, KeyBarKey.RIGHT, KeyBarKey.BKSP)

/**
 * On-screen keys that touch keyboards lack: history, cursor, completion, and a few symbols.
 * Up to [FILL_MAX] keys share the width; more scroll, with a fade marking the overflow.
 * Arrows and backspace repeat while held; dragging sideways across the bar moves the cursor.
 */
@Composable
private fun KeyBar(specs: List<String>, rowHeight: Dp, onKey: (KeyBarKey) -> Unit, onText: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Term.surface),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        specs.filter { it.isNotBlank() }.forEach { KeyRow(it, rowHeight, onKey, onText) }
    }
}

@Composable
private fun KeyRow(spec: String, rowHeight: Dp, onKey: (KeyBarKey) -> Unit, onText: (String) -> Unit) {
    val keys = remember(spec) {
        spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { token -> KeyBarKey.of(token) to token }
    }
    val haptics = LocalHapticFeedback.current
    val currentOnKey by rememberUpdatedState(onKey)
    val press: (KeyBarKey?, String) -> Unit = { named, token ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (named != null) currentOnKey(named) else onText(token)
    }
    val bar = Modifier
        .fillMaxWidth()
        .height(rowHeight)
        .testTag("keybar")
        .background(Term.surface)

    @Composable
    fun Key(named: KeyBarKey?, token: String, modifier: Modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .fillMaxHeight()
                .pointerInput(named, token) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (named !in REPEATING) {
                            // Plain keys fire on release, so a drag or scroll that starts on ⏎ does nothing.
                            if (waitForUpOrCancellation() != null) press(named, token)
                            return@awaitEachGesture
                        }
                        // Hold to repeat: fire now, then after 400ms every 50ms. A drag or scroll consumes the
                        // pointer (waitForUpOrCancellation returns null) and must stop the repeat.
                        press(named, token)
                        var delayMs = 400L
                        while (true) {
                            val outcome = withTimeoutOrNull(delayMs) { waitForUpOrCancellation() ?: CANCELLED }
                            if (outcome != null) break // released or cancelled; null means still held
                            press(named, token)
                            delayMs = 50L
                        }
                    }
                },
        ) {
            // Padding inside the touch area (after pointerInput), so the whole key is tappable.
            Text(
                named?.label ?: token,
                style = Term.mono.copy(color = Term.fg, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }

    if (keys.size <= FILL_MAX) {
        Row(
            bar.pointerInput(Unit) {
                var acc = 0f
                val step = 14.dp.toPx()
                detectHorizontalDragGestures(onDragStart = { acc = 0f }) { change, dx ->
                    change.consume()
                    acc += dx
                    while (acc >= step) { currentOnKey(KeyBarKey.RIGHT); acc -= step }
                    while (acc <= -step) { currentOnKey(KeyBarKey.LEFT); acc += step }
                }
            }
        ) {
            keys.forEach { (named, token) -> Key(named, token, Modifier.weight(1f)) }
        }
    } else {
        Box(bar) {
            LazyRow(Modifier.fillMaxSize()) {
                itemsIndexed(keys) { _, (named, token) ->
                    Key(named, token, Modifier.widthIn(min = 48.dp))
                }
            }
            // Fade on the right edge: more keys are available by scrolling.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(24.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Term.surface)))
            )
        }
    }
}

// Keys share the row width up to this count; 7 keys stay ≥ 48dp wide on a 360dp screen.
private const val FILL_MAX = 7

@Composable
private fun Prompt(
    vm: TerminalViewModel,
    focus: FocusRequester,
    imeSuggestions: Boolean,
    onFocus: (Boolean) -> Unit,
    height: Dp,
    keyBarOn: Boolean,
    onQuick: () -> Unit,
    onToggleKeyBar: () -> Unit,
    onTapPrompt: () -> Unit,
) {
    val configVersion by vm.config.version.collectAsStateWithLifecycle()
    val swipeUpOn = remember(configVersion) { vm.config[Keys.GESTURE_SWIPE_UP_ON] }
    val promptStyle = Term.mono.copy(fontSize = (Term.fontSize + 2).sp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            // Swipe up on the prompt line: gesture.swipeup, only while gesture.swipeup.on (off by
            // default). When off the detector isn't installed at all, so it can't misfire.
            .then(
                if (!swipeUpOn) Modifier else Modifier.pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total < -48.dp.toPx()) vm.onGesture(Keys.GESTURE_SWIPE_UP) },
                    ) { _, dy -> total += dy }
                },
            )
            .clickable(onClick = onTapPrompt)
            // Vertical room comes from [height] (centred), so small values really shrink the line.
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        // Tap `$`: the quick sheet (settings, battery, profiles, …) within thumb reach.
        Text(
            "$ ",
            style = promptStyle.copy(color = Term.prompt, fontWeight = FontWeight.Bold),
            modifier = Modifier
                .testTag("prompt-quick")
                .clickable(onClickLabel = "quick actions", onClick = onQuick)
                .heightIn(min = 32.dp)
                .padding(end = 2.dp)
                .wrapContentHeight(),
        )
        BasicTextField(
            value = vm.input,
            onValueChange = vm::onInputChange,
            singleLine = true,
            textStyle = promptStyle,
            cursorBrush = SolidColor(Term.prompt),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = imeSuggestions,
                capitalization = KeyboardCapitalization.None,
                // Password type is the reliable way to switch off IME autocorrect and suggestions.
                keyboardType = if (imeSuggestions) KeyboardType.Text else KeyboardType.Password,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { vm.submit() }),
            modifier = Modifier
                .weight(1f)
                .testTag("prompt")
                .focusRequester(focus)
                .onFocusChanged { onFocus(it.isFocused) }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.DirectionUp -> { vm.historyUp(); true }
                        e.key == Key.DirectionDown -> { vm.historyDown(); true }
                        e.key == Key.Tab -> { vm.complete(); true }
                        e.key == Key.Escape -> { vm.escape(); true }
                        e.key == Key.Enter || e.key == Key.NumPadEnter -> { vm.submit(); true }
                        e.isCtrlPressed && e.key == Key.L -> { vm.clearScreen(); true }
                        e.isCtrlPressed && e.key == Key.U -> { vm.escape(); true }
                        e.isCtrlPressed && e.key == Key.A -> { vm.onKeyBar(KeyBarKey.HOME); true }
                        e.isCtrlPressed && e.key == Key.E -> { vm.onKeyBar(KeyBarKey.END); true }
                        else -> false
                    }
                },
        )
        // Clear: touch typing has no Esc when the key bar is hidden.
        if (vm.input.text.isNotEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .testTag("prompt-clear")
                    .widthIn(min = 44.dp)
                    .heightIn(min = 32.dp)
                    .clickable(onClickLabel = "clear the prompt") { vm.escape() },
            ) {
                Text("✕", style = promptStyle.copy(color = Term.dim))
            }
        }
        // Key bar toggle (keybar.show); dim while the bar is off. Flush right, in line with the log's edge;
        // the touch target still reaches to its left.
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier
                .testTag("keybar-toggle")
                .widthIn(min = 44.dp)
                .heightIn(min = 32.dp)
                .clickable(onClickLabel = "toggle key bar", onClick = onToggleKeyBar),
        ) {
            Text("⌨", style = promptStyle.copy(color = if (keyBarOn) Term.prompt else Term.dim))
        }
    }
}

/**
 * Two-finger pinch changes the terminal font size. Runs in the Initial pass and consumes only
 * multi-touch events, so one-finger scrolling and taps still reach the list.
 */
private fun Modifier.pinchToZoomFont(onChange: (Int) -> Unit, onEnd: (Int) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var startDistance = 0f
        var startSize = Term.fontSize
        var changed = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                if (startDistance == 0f) {
                    startDistance = distance
                    startSize = Term.fontSize
                } else if (startDistance > 0f) {
                    val size = (startSize * distance / startDistance).roundToInt().coerceIn(10, 24)
                    if (size != Term.fontSize) {
                        onChange(size)
                        changed = true
                    }
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (changed) onEnd(Term.fontSize)
    }
}

/**
 * A battery drawn to scale: outline, a fill proportional to [pct], and a bolt cut out of the
 * fill while charging. Sized to sit on a line of header text.
 */
@Composable
private fun BatteryGlyph(pct: Int, charging: Boolean, color: Color, height: Dp) {
    val bg = Term.bg
    androidx.compose.foundation.Canvas(Modifier.size(width = height * 1.6f, height = height)) {
        val stroke = 1.4.dp.toPx()
        val capW = size.width * 0.08f
        val bodyW = size.width - capW
        val bodyH = size.height * 0.62f
        val top = (size.height - bodyH) / 2
        val r = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        drawRoundRect(color, Offset(stroke / 2, top), Size(bodyW - stroke, bodyH), r, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawRect(color, Offset(bodyW, top + bodyH * 0.3f), Size(capW, bodyH * 0.4f))
        val inset = stroke * 1.8f
        val fullW = bodyW - stroke - inset * 2
        val fillW = fullW * (pct.coerceIn(0, 100) / 100f)
        if (fillW > 0f) drawRect(color, Offset(stroke / 2 + inset, top + inset), Size(fillW, bodyH - inset * 2))
        if (charging) {
            // Bolt in the background colour so it reads on top of the fill.
            val cx = stroke / 2 + inset + fullW / 2
            val h = bodyH - inset * 2
            val y0 = top + inset
            val bolt = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx + h * 0.15f, y0)
                lineTo(cx - h * 0.35f, y0 + h * 0.58f)
                lineTo(cx, y0 + h * 0.58f)
                lineTo(cx - h * 0.15f, y0 + h)
                lineTo(cx + h * 0.35f, y0 + h * 0.42f)
                lineTo(cx, y0 + h * 0.42f)
                close()
            }
            drawPath(bolt, if (fillW > fullW / 2) bg else color)
        }
    }
}
