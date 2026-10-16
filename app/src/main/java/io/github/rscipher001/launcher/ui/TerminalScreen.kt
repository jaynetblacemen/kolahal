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

@Composable
fun TerminalScreen(vm: TerminalViewModel, onRequestNotifications: () -> Unit, onPickFolder: () -> Unit) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val unlocks by vm.unlocksToday.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val configVersion by vm.config.version.collectAsStateWithLifecycle()
    val config = vm.config
    val listState = rememberLazyListState()
    var pinned by remember { mutableStateOf(true) }
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
                UiEvent.RequestNotificationPermission -> onRequestNotifications()
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
    LaunchedEffect(entries.lastOrNull()?.id) {
        if (pinned) listState.scrollToItem(0)
    }
    // Keyboard-first: grab focus straight away when a physical keyboard is attached.
    LaunchedEffect(hasHardwareKeyboard) {
        if (hasHardwareKeyboard) runCatching { focus.requestFocus() }
    }

    val rows by vm.rows.collectAsStateWithLifecycle()
    val reversedRows = remember(rows) { rows.asReversed() }
    val maxSuggestions = remember(configVersion) { config[Keys.SUGGESTIONS] }
    val commandNames by vm.commandNames.collectAsStateWithLifecycle()
    val prefsVersion by vm.appPrefsVersion.collectAsStateWithLifecycle()
    val suggestions = remember(vm.input.text, apps, maxSuggestions, commandNames, prefsVersion) {
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
    menu?.let { ActionSheet(it, onDismiss = { menu = null }) }
    val commandGap = remember(configVersion) { config[Keys.LOG_SPACING].dp }
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
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Header(
            unlocks = unlocks,
            profiles = profiles,
            status = status,
            statuses = statuses,
            config = config,
            version = configVersion,
            onStatusTap = vm::runScriptNamed,
            onSwipeDown = vm::expandNotifications,
            onTarget = vm::onHeaderTap,
            onProfileTap = vm::onProfileTap,
        )
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("log")
                .pinchToZoomFont(onChange = { Term.fontSize = it }, onEnd = vm::saveFontSize)
                // Taps that no log line consumed (empty space) bring up the keyboard.
                .pointerInput(Unit) {
                    detectTapGestures {
                        runCatching { focus.requestFocus() }
                        keyboard?.show()
                    }
                },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
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
                        charWidth = charWidth,
                        highlighted = menu?.entryId == row.entry.id,
                        apps = vm::appsOf,
                        onTap = vm::onTap,
                        onLongPress = { e -> menu = ActionMenu(e.text, vm.actionsFor(e), e.id) },
                        onAppTap = vm::launchApp,
                        onAppLongPress = { app -> menu = ActionMenu(app.label, vm.appActions(app), null) },
                    )
                }
            }
        }
        SuggestionStrip(suggestions, onPick = vm::onSuggestion)
        if (remember(configVersion) { config[Keys.KEYBAR_SHOW] }) {
            KeyBar(
                spec = remember(configVersion) { config[Keys.KEYBAR] },
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
            onTapPrompt = {
                runCatching { focus.requestFocus() }
                keyboard?.show()
            },
        )
    }
}

/** What a tap on a header item does. */
enum class HeaderTarget { CLOCK, UNLOCKS, BATTERY, NETWORK, ALARM }

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

    @Composable
    fun Item(label: String, value: String, valueColor: Color = Term.fg, onClick: () -> Unit) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Term.dim)) { append("$label ") }
                withStyle(SpanStyle(color = valueColor, fontWeight = FontWeight.Bold)) { append(value) }
            },
            style = small,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp, horizontal = 2.dp),
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
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.clickable { onTarget(HeaderTarget.CLOCK) }) {
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (config[Keys.HEADER_UNLOCKS]) Item("unlocks", unlocks.toString()) { onTarget(HeaderTarget.UNLOCKS) }
            val pct = status.batteryPct
            if (config[Keys.HEADER_BATTERY] && pct != null) {
                val color = when {
                    status.charging -> Term.prompt
                    pct <= 15 -> Term.error
                    else -> Term.fg
                }
                Item("bat", "$pct%" + if (status.charging) "+" else "", color) { onTarget(HeaderTarget.BATTERY) }
            }
            if (config[Keys.HEADER_NETWORK]) {
                Item("net", status.network, if (status.network == "offline") Term.error else Term.fg) {
                    onTarget(HeaderTarget.NETWORK)
                }
            }
            if (config[Keys.HEADER_ALARM] && status.nextAlarm != null) {
                Item("alarm", alarmFmt.format(Date(status.nextAlarm))) { onTarget(HeaderTarget.ALARM) }
            }
            if (config[Keys.HEADER_PROFILES]) {
                profiles.filter { it.kind != ProfileKind.PERSONAL && it.kind != ProfileKind.CLONE }.forEach { p ->
                    Item(p.kind.label, if (p.quiet) "off" else "on", if (p.quiet) Term.dim else Term.profileColor(p.kind)) {
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
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SuggestionStrip(items: List<Suggestion>, onPick: (Suggestion) -> Unit) {
    if (items.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("suggestions")
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items, key = { _, s ->
            when (s) {
                is Suggestion.App -> "a:" + s.app.key
                is Suggestion.Completion -> "c:" + s.replacement
            }
        }) { index, s ->
            val (color, tag) = when (s) {
                is Suggestion.App -> Term.profileColor(s.app.profile.kind) to s.app.profile.kind.tag
                is Suggestion.Completion -> Term.dim to null
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .alpha(if (s is Suggestion.App && s.app.profile.quiet) 0.45f else 1f)
                    // The first chip is what Enter / TAB act on: fill it so that is visible.
                    .background(if (index == 0) color.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(4.dp))
                    .border(1.dp, color.copy(alpha = if (index == 0) 0.9f else 0.6f), RoundedCornerShape(4.dp))
                    .clickable { onPick(s) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (tag != null && tag != ProfileKind.PERSONAL.tag) {
                    Text(tag, style = Term.mono.copy(color = color, fontWeight = FontWeight.Bold, fontSize = (Term.fontSize - 2).sp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(s.label, style = Term.mono.copy(color = if (s is Suggestion.App) Term.fg else Term.dim))
            }
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

/** Keys that repeat while held. */
private val REPEATING = setOf(KeyBarKey.UP, KeyBarKey.DOWN, KeyBarKey.LEFT, KeyBarKey.RIGHT, KeyBarKey.BKSP)

/**
 * On-screen keys that touch keyboards lack: history, cursor, completion, and a few symbols.
 * Up to [FILL_MAX] keys share the width; more scroll, with a fade marking the overflow.
 * Arrows and backspace repeat while held; dragging sideways across the bar moves the cursor.
 */
@Composable
private fun KeyBar(spec: String, onKey: (KeyBarKey) -> Unit, onText: (String) -> Unit) {
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
        .height(48.dp)
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
                        press(named, token)
                        if (named !in REPEATING) {
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        // Hold to repeat: 400ms delay, then every 50ms until released.
                        var delayMs = 400L
                        while (true) {
                            val released = withTimeoutOrNull(delayMs) { waitForUpOrCancellation() }
                            if (released != null || currentEvent.changes.none { it.pressed }) break
                            press(named, token)
                            delayMs = 50L
                        }
                    }
                },
        ) {
            Text(named?.label ?: token, style = Term.mono.copy(color = Term.fg, fontWeight = FontWeight.Bold))
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
                    Key(named, token, Modifier.widthIn(min = 44.dp).padding(horizontal = 8.dp))
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

private const val FILL_MAX = 10

@Composable
private fun Prompt(
    vm: TerminalViewModel,
    focus: FocusRequester,
    imeSuggestions: Boolean,
    onTapPrompt: () -> Unit,
) {
    val promptStyle = Term.mono.copy(fontSize = (Term.fontSize + 2).sp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTapPrompt)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("$ ", style = promptStyle.copy(color = Term.prompt, fontWeight = FontWeight.Bold))
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
