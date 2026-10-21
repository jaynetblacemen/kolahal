package com.mtwabpin.kolahal.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.togetherWith
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.terminal.LogAction
import com.mtwabpin.kolahal.terminal.LogRow
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import com.mtwabpin.kolahal.terminal.UiEvent
import java.text.SimpleDateFormat
import java.util.Locale

/** What the prompt shows when home is seen from Settings as if typing: one letter matches plenty. */
private const val PREVIEW_INPUT = "s"

/**
 * Home. [preview]: drawn under Settings while a look setting is changed (TerminalViewModel.Peek):
 * it doesn't take focus, the keyboard or events, and with `typing` it looks as if [PREVIEW_INPUT] were typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(vm: TerminalViewModel, onRequestNotifications: () -> Unit, onPickFolder: () -> Unit, preview: TerminalViewModel.Peek? = null) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    // An install or update may change icons: suggestions.icons loads them again.
    if (preview == null) LaunchedEffect(apps) { AppIcons.clear() }
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
    if (preview == null) LaunchedEffect(Unit) {
        // Home pressed on another page: scroll and focus now that the terminal is here.
        vm.consumeArrival()?.let { a ->
            pinned = true
            runCatching { listState.scrollToItem(0) }
            if (a.focus) {
                androidx.compose.runtime.withFrameNanos { } // the prompt's focus target is attached after the first frame
                runCatching { focus.requestFocus() }
                if (!currentHasHardwareKeyboard) currentKeyboard?.show()
            }
        }
        vm.events.collect { e ->
            when (e) {
                UiEvent.ScrollToBottom -> { pinned = true; listState.scrollToItem(0) }
                // Handled by MainActivity so it works from every page (setup, script manager).
                UiEvent.RequestNotificationPermission, UiEvent.RequestContactsPermission, UiEvent.RequestCalendarPermission, UiEvent.RequestCallLogPermission,
                UiEvent.RequestLocalNetworkPermission, UiEvent.PickFont -> Unit
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
        }.collect { (scrolling, atBottom) ->
            if (scrolling) pinned = atBottom
            // Settled back at the newest lines: older pages loaded on the way up can go.
            if (!scrolling && atBottom) vm.atNewest()
        }
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
    if (preview == null) LaunchedEffect(hasHardwareKeyboard) {
        if (hasHardwareKeyboard) runCatching { focus.requestFocus() }
    }

    val maxSuggestions = remember(configVersion) { config[Keys.SUGGESTIONS] }
    val commandNames by vm.commandNames.collectAsStateWithLifecycle()
    val prefsVersion by vm.appPrefsVersion.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val inputText = if (preview?.typing == true) PREVIEW_INPUT else vm.input.text
    val suggestions = remember(inputText, apps, maxSuggestions, commandNames, prefsVersion, contacts) {
        vm.suggestions(inputText).take(maxSuggestions)
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
    // Menus ask the system for an app's shortcuts: built off the main thread, shown when ready.
    val menuScope = rememberCoroutineScope()
    fun showMenu(title: String, id: Long?, build: () -> List<LogAction>) {
        menuScope.launch { menu = ActionMenu(title, kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { build() }, id) }
    }
    if (preview == null) LaunchedEffect(Unit) {
        vm.events.collect { if (it == UiEvent.ShowQuick) menu = ActionMenu("Quick actions", vm.quickActions(), null, systemFont = true) }
    }
    menu?.let { ActionSheet(it, onDismiss = { menu = null }) }
    val commandGap = remember(configVersion) { config[Keys.LOG_SPACING].dp }
    val showGutter = remember(configVersion) { config[Keys.LOG_GUTTER] }
    // Wrapped and detail lines are indented past `$ ` (or `• `) as wide as it is in the log's font.
    // Measured with a letter after it: a trailing space alone may not count towards the width.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val indents = remember(Term.fontSize, Term.fontFamily, Term.sans, density) {
        fun width(prefix: String, style: androidx.compose.ui.text.TextStyle) = with(density) {
            (measurer.measure("${prefix}0", style).size.width - measurer.measure("0", style).size.width).toSp()
        }
        LineIndents(text = width("$ ", Term.text), code = width("$ ", Term.code), bullet = width("• ", Term.text))
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
        // A hardware keyboard already has these keys: the bar follows the on-screen keyboard.
        val typing = if (preview != null) preview.typing else promptFocused && WindowInsets.isImeVisible
        // While searching, the header shrinks to one line: suggestions can't use the room it takes.
        // Only shown smaller; it returns as it was when the search ends.
        val searching = typing || inputText.isNotEmpty()
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
                targetState = headerCollapsed || searching,
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
        HomeWidgets(vm, visible = !headerCollapsed && !searching)
        val hiddenLines by vm.hiddenLines.collectAsStateWithLifecycle()
        // The log and the suggestions share what the header, key bar and prompt leave. Those are
        // measured first, so a long suggestion list can't push the prompt off the screen.
        Column(Modifier.weight(1f).fillMaxWidth()) {
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
                            "Tap a line to act, long-press for more · ⚙ settings",
                        style = Term.text.copy(color = Term.dim),
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
                        indents = indents,
                        highlighted = menu?.entryId == row.entry.id,
                        apps = vm::appsOf,
                        onTap = vm::onTap,
                        onLongPress = { e -> showMenu(e.text, e.id) { vm.actionsFor(e) } },
                        onAppTap = vm::launchApp,
                        onAppLongPress = { app -> showMenu(app.label, null) { vm.appActions(app) } },
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
                        style = Term.text.copy(color = Term.dim),
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
                TintedIcon(R.drawable.ic_arrow_down, Term.prompt, 24.dp)
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
                onLongPress = { app -> showMenu(app.label, null) { vm.appActions(app) } },
                heightDp = remember(configVersion) { config[Keys.SELECTOR_HEIGHT] },
                showHandle = remember(configVersion) { config[Keys.SELECTOR_HANDLE] },
            )
        }
        // [/selector]
        }
        // suggestions.auto: hidden on an idle home; shown while typing, or whenever there is input
        // (so hardware-keyboard typing still gets them).
        val suggestionsAuto = remember(configVersion) { config[Keys.SUGGESTIONS_AUTO] }
        val undoEntry by vm.undoDelete.collectAsStateWithLifecycle()
        if (undoEntry != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(Term.surface).heightIn(min = 44.dp).padding(start = 12.dp),
            ) {
                Text("Line deleted", style = Term.text.copy(color = Term.dim), modifier = Modifier.weight(1f))
                Text(
                    "Undo",
                    style = Term.text.copy(color = Term.prompt, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable(onClick = vm::undoSwipeDelete).heightIn(min = 44.dp).wrapContentHeight().padding(horizontal = 16.dp),
                )
            }
        }
        // [selector] suggestions.style: list (default, bottom-anchored above the prompt) or chips.
        val suggestionsStyle = remember(configVersion) { config[Keys.SUGGESTIONS_STYLE] }
        val showIcons = remember(configVersion) { config[Keys.SUGGEST_ICONS] }
        if (!suggestionsAuto || typing || inputText.isNotEmpty()) {
            if (suggestionsStyle == "chips") {
                SuggestionStrip(suggestions, rows = remember(configVersion) { config[Keys.SUGGESTION_ROWS] }, onPick = vm::onSuggestion, icons = showIcons)
            } else {
                SuggestionList(
                    suggestions,
                    onPick = vm::onSuggestion,
                    onAppLongPress = { app -> showMenu(app.label, null) { vm.appActions(app) } },
                    icons = showIcons,
                )
            }
        }
        // [/selector]
        }
        // keybar.auto: only while typing, so an idle home shows more of the log.
        val keyBarAuto = remember(configVersion) { config[Keys.KEYBAR_AUTO] }
        val promptTypingHeight = remember(configVersion) { config[Keys.PROMPT_HEIGHT_TYPING].dp }
        val keyBarOn = remember(configVersion) { config[Keys.KEYBAR_SHOW] }
        Prompt(
            vm = vm,
            focus = focus,
            previewText = if (preview?.typing == true) PREVIEW_INPUT else null,
            imeSuggestions = remember(configVersion) { config[Keys.IME_SUGGESTIONS] },
            onFocus = { promptFocused = it },
            // prompt.height in both states, so the line doesn't jump under the finger as the keyboard opens.
            height = promptTypingHeight,
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
        // Below the prompt, so it sits on the keyboard like an extra row of keys and the best
        // suggestion stays right above what's typed.
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
