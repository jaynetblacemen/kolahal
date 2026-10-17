package io.github.rscipher001.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rscipher001.launcher.R
import io.github.rscipher001.launcher.data.Script
import io.github.rscipher001.launcher.data.ScriptLang
import io.github.rscipher001.launcher.script.ApiDoc
import io.github.rscipher001.launcher.script.ApiDocs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Monospace editor for scripts: line numbers, light syntax colouring, undo/redo, find/replace,
 * API completion and inline docs.
 * Ctrl+S saves, Ctrl+R saves and runs, Ctrl+Z / Ctrl+Shift+Z undo/redo, Ctrl+F find, Ctrl+H replace,
 * F3 / Shift+F3 next/previous match, Ctrl+G go to line, Tab accepts the first suggestion,
 * Esc closes the find bar, then saves and closes the editor (as does Back).
 * When opened from an error, [jumpToLine] is selected and briefly highlighted.
 */
@Composable
fun ScriptEditor(
    script: Script,
    jumpToLine: Int?,
    onSave: (String) -> Unit,
    onRun: (String) -> Unit,
    onClose: () -> Unit,
) {
    var value by rememberSaveable(script.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(script.code, selectionAtLine(script.code, jumpToLine)))
    }
    var saved by rememberSaveable(script.id) { mutableStateOf(script.code) }
    val dirty = value.text != saved
    val focus = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val history = remember(script.id) { UndoHistory(script.code) }
    var flashLine by remember(script.id) { mutableStateOf(jumpToLine) }
    var editorFocused by remember { mutableStateOf(false) }

    // Find / replace
    var findOpen by rememberSaveable(script.id) { mutableStateOf(false) }
    var replaceOpen by rememberSaveable(script.id) { mutableStateOf(false) }
    var query by rememberSaveable(script.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var replacement by rememberSaveable(script.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var ignoreCase by rememberSaveable(script.id) { mutableStateOf(true) }
    var matches by remember(script.id) { mutableStateOf(EditorLogic.Matches.EMPTY) }
    var current by remember(script.id) { mutableIntStateOf(-1) }

    var gotoOpen by remember { mutableStateOf(false) }
    var referenceOpen by remember { mutableStateOf(false) }

    // Scrolling to a match / line
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var viewportWidth by remember { mutableIntStateOf(0) }

    fun reveal(offset: Int) {
        val l = layout ?: return
        val o = offset.coerceIn(0, l.layoutInput.text.length)
        val padTop = with(density) { 12.dp.roundToPx() }
        val line = l.getLineForOffset(o)
        val top = l.getLineTop(line).toInt() + padTop
        val bottom = l.getLineBottom(line).toInt() + padTop
        if (top < vScroll.value || bottom > vScroll.value + viewportHeight) {
            scope.launch { vScroll.animateScrollTo((top - viewportHeight / 3).coerceAtLeast(0)) }
        }
        val x = l.getHorizontalPosition(o, true).toInt()
        val margin = with(density) { 48.dp.roundToPx() }
        if (x < hScroll.value || x > hScroll.value + viewportWidth - margin) {
            scope.launch { hScroll.animateScrollTo((x - viewportWidth / 3).coerceAtLeast(0)) }
        }
    }

    fun save() {
        if (!dirty) return
        onSave(value.text)
        saved = value.text
    }

    fun run() {
        saved = value.text
        onRun(value.text)
    }

    fun close() {
        save()
        onClose()
    }

    fun edit(new: TextFieldValue) {
        if (new.text != value.text) history.record(new.text)
        value = new
    }

    fun insert(s: String) {
        val sel = value.selection
        edit(TextFieldValue(value.text.replaceRange(sel.min, sel.max, s), TextRange(sel.min + s.length)))
    }

    fun undo() = history.undo()?.let { value = TextFieldValue(it, TextRange(it.length.coerceAtMost(value.selection.end))) }
    fun redo() = history.redo()?.let { value = TextFieldValue(it, TextRange(it.length.coerceAtMost(value.selection.end))) }

    fun focusEditor() {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    // ---- find / replace -------------------------------------------------------------------

    fun select(start: Int, length: Int) {
        val s = start.coerceIn(0, value.text.length)
        value = value.copy(selection = TextRange(s, (s + length).coerceAtMost(value.text.length)))
        reveal(s)
    }

    fun gotoMatch(delta: Int) {
        val m = matches
        if (m.size == 0) return
        val from = value.selection.min
        current = if (delta > 0) {
            m.indexAtOrAfter(from + 1)
        } else {
            // 0 means either "first match is at/after the cursor" or "none is": both wrap to the last.
            val i = m.indexAtOrAfter(from)
            if (i == 0) m.size - 1 else i - 1
        }
        select(m.starts[current], m.length)
    }

    fun openFind(withReplace: Boolean) {
        val sel = value.selection
        if (!sel.collapsed && sel.length <= 200) {
            val picked = value.text.substring(sel.min, sel.max)
            if ('\n' !in picked) query = TextFieldValue(picked)
        }
        query = query.copy(selection = TextRange(0, query.text.length))
        findOpen = true
        if (withReplace) replaceOpen = true
        scope.launch {
            delay(50)
            runCatching { findFocus.requestFocus() }
        }
    }

    fun closeFind() {
        findOpen = false
        replaceOpen = false
        focusEditor()
    }

    fun replaceOne() {
        val q = query.text
        if (q.isEmpty()) return
        val sel = value.selection
        val text = value.text
        val onMatch = sel.length == q.length && text.regionMatches(sel.min, q, 0, q.length, ignoreCase)
        if (!onMatch) {
            gotoMatch(1)
            return
        }
        val newText = text.replaceRange(sel.min, sel.max, replacement.text)
        val after = sel.min + replacement.text.length
        history.checkpoint()
        edit(TextFieldValue(newText, TextRange(after)))
        history.checkpoint()
        val next = newText.indexOf(q, after, ignoreCase).takeIf { it >= 0 } ?: newText.indexOf(q, 0, ignoreCase)
        if (next >= 0) select(next, q.length)
    }

    fun replaceAll() {
        val q = query.text
        if (q.isEmpty()) return
        val (newText, n) = EditorLogic.replaceAll(value.text, q, replacement.text, ignoreCase)
        if (n == 0) return
        history.checkpoint()
        edit(TextFieldValue(newText, TextRange(value.selection.min.coerceAtMost(newText.length))))
        history.checkpoint()
    }

    // Matches are found off the main thread, debounced while typing.
    var lastSearch by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(findOpen, query.text, ignoreCase, value.text) {
        if (!findOpen || query.text.isEmpty()) {
            matches = EditorLogic.Matches.EMPTY
            current = -1
            lastSearch = null
            return@LaunchedEffect
        }
        delay(if (value.text.length > 200_000) 250 else 80)
        val text = value.text
        val q = query.text
        val ic = ignoreCase
        val m = withContext(Dispatchers.Default) { EditorLogic.findMatches(text, q, ic) }
        matches = m
        val queryChanged = lastSearch != (q to ic)
        lastSearch = q to ic
        if (m.size == 0) {
            current = -1
        } else if (queryChanged) {
            // Incremental search: jump to the first match from the cursor.
            current = m.indexAtOrAfter(value.selection.min)
            select(m.starts[current], m.length)
        } else {
            current = m.indexAtOrAfter(value.selection.min)
        }
    }

    // ---- completion / docs ------------------------------------------------------------------

    val suggestions = remember(value.text, value.selection, editorFocused, script.lang) {
        if (!editorFocused || !value.selection.collapsed) emptyList()
        else EditorLogic.completions(value.text, value.selection.end, script.lang)
    }
    val inlineDoc = remember(value.text, value.selection.end, editorFocused, script.lang) {
        if (!editorFocused) null
        else EditorLogic.docAt(value.text, value.selection.end, script.lang)?.takeIf { it.kind != ApiDoc.Kind.KEYWORD }
    }

    fun accept(doc: ApiDoc) {
        val c = value.selection.end
        val p = EditorLogic.prefixAt(value.text, c) ?: return
        val next = value.text.getOrNull(c)
        val ins = EditorLogic.insertion(doc, next)
        var cursor = p.start + ins.length
        // Step over an existing `(` / `.` that the insertion relied on.
        if ((doc.kind == ApiDoc.Kind.FUNCTION && next == '(') || (doc.kind == ApiDoc.Kind.NAMESPACE && next == '.')) cursor++
        edit(TextFieldValue(value.text.replaceRange(p.start, c, ins), TextRange(cursor)))
    }

    fun gotoLine(line: Int) {
        val offset = EditorLogic.lineStart(value.text, line)
        value = value.copy(selection = TextRange(offset))
        flashLine = line
        reveal(offset)
        focusEditor()
    }

    BackHandler { close() }
    BackHandler(enabled = findOpen) { closeFind() }
    // Leaving the app (or the process being killed in the background) must not lose edits.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { save() }
    LaunchedEffect(script.id) { runCatching { focus.requestFocus() } }
    LaunchedEffect(flashLine) {
        if (flashLine != null) {
            delay(3_000)
            flashLine = null
        }
    }
    // Scroll an error jump into view once the text has been laid out.
    var revealedJump by remember(script.id) { mutableStateOf(jumpToLine == null) }
    LaunchedEffect(layout, viewportHeight) {
        if (!revealedJump && layout != null && viewportHeight > 0) {
            revealedJump = true
            reveal(value.selection.start)
        }
    }

    val highlights = remember(findOpen, matches, current) {
        if (!findOpen || matches.size == 0) null
        else {
            val c = current.coerceIn(0, matches.size - 1)
            val from = (c - EditorLogic.MAX_HIGHLIGHTS / 2).coerceAtLeast(0)
            val to = (from + EditorLogic.MAX_HIGHLIGHTS).coerceAtMost(matches.size)
            MatchHighlights(matches.starts.copyOfRange(from, to), matches.length, if (current >= 0) matches.starts[c] else -1)
        }
    }
    val highlighter = remember(script.lang, flashLine, Term.palette, highlights) { SyntaxHighlighter(script.lang, flashLine, highlights) }
    val lineCount = value.text.count { it == '\n' } + 1
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Term.bg)
            .cutoutSafePadding()
            .navigationBarsPadding()
            .imePadding()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.isCtrlPressed && e.key == Key.S -> { save(); true }
                    e.isCtrlPressed && e.key == Key.R -> { run(); true }
                    e.isCtrlPressed && e.key == Key.F -> { openFind(false); true }
                    e.isCtrlPressed && e.key == Key.H -> { openFind(true); true }
                    e.isCtrlPressed && e.key == Key.G -> { gotoOpen = true; true }
                    e.key == Key.F3 -> { if (findOpen) gotoMatch(if (e.isShiftPressed) -1 else 1) else openFind(false); true }
                    // Text fields in the find bar keep their own undo.
                    editorFocused && e.isCtrlPressed && e.isShiftPressed && e.key == Key.Z -> { redo(); true }
                    editorFocused && e.isCtrlPressed && e.key == Key.Z -> { undo(); true }
                    editorFocused && e.isCtrlPressed && e.key == Key.Y -> { redo(); true }
                    e.key == Key.Escape -> { if (findOpen) closeFind() else close(); true }
                    else -> false
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(Term.surface).padding(start = 12.dp),
        ) {
            Text(
                "${script.name}.${script.lang.ext}" + if (dirty) " *" else "",
                style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clickable { if (findOpen) closeFind() else openFind(false) },
            ) {
                Icon(painterResource(R.drawable.ic_search), "Find", tint = if (findOpen) Term.prompt else Term.fg, modifier = Modifier.size(20.dp))
            }
            EditorButton("?") { referenceOpen = true }
            EditorButton("save") { save() }
            EditorButton("run") { run() }
            EditorButton("close") { close() }
        }
        if (findOpen) {
            FindBar(
                query = query,
                onQuery = { query = it },
                replacement = replacement,
                onReplacement = { replacement = it },
                replaceOpen = replaceOpen,
                onToggleReplace = { replaceOpen = !replaceOpen },
                ignoreCase = ignoreCase,
                onToggleCase = { ignoreCase = !ignoreCase },
                count = when {
                    query.text.isEmpty() -> ""
                    matches.size == 0 -> "0/0"
                    else -> "${current + 1}/${matches.size}${if (matches.capped) "+" else ""}"
                },
                noMatch = query.text.isNotEmpty() && matches.size == 0 && lastSearch != null,
                focusRequester = findFocus,
                onNext = { gotoMatch(1) },
                onPrev = { gotoMatch(-1) },
                onReplace = ::replaceOne,
                onReplaceAll = ::replaceAll,
                onClose = ::closeFind,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewportHeight = it.height }
                // Tapping empty space below the code focuses the editor.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    value = value.copy(selection = TextRange(value.text.length))
                    focusEditor()
                }
                .verticalScroll(vScroll)
                .padding(vertical = 12.dp)
        ) {
            Row {
                // Line numbers share the field's style, so they stay aligned while scrolling.
                Text(
                    numbers,
                    style = Term.mono.copy(color = Term.dim, textAlign = TextAlign.End),
                    modifier = Modifier.widthIn(min = 28.dp).padding(start = 6.dp, end = 10.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .onSizeChanged { viewportWidth = it.width }
                        .horizontalScroll(hScroll)
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = ::edit,
                        textStyle = Term.mono,
                        cursorBrush = SolidColor(Term.prompt),
                        visualTransformation = highlighter,
                        onTextLayout = { layout = it },
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                            // Plain text (not password) so non-Latin layouts and voice input work in code.
                            keyboardType = KeyboardType.Text,
                        ),
                        modifier = Modifier
                            .widthIn(min = 600.dp)
                            .padding(end = 12.dp)
                            .focusRequester(focus)
                            .onFocusChanged { editorFocused = it.isFocused }
                            .testTag("editor")
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) {
                                    val first = suggestions.firstOrNull()
                                    if (first != null && !e.isShiftPressed) accept(first) else insert("  ")
                                    true
                                } else false
                            },
                    )
                }
            }
        }
        if (inlineDoc != null) {
            Text(
                "${inlineDoc.signature}  ${inlineDoc.doc}",
                style = Term.mono.copy(color = Term.dim, fontSize = Term.mono.fontSize * 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Term.bg)
                    .padding(horizontal = 12.dp, vertical = 3.dp),
            )
        }
        if (suggestions.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().background(Term.surface.copy(alpha = 0.6f)).testTag("completions")) {
                items(suggestions.size) { i ->
                    val d = suggestions[i]
                    val label = d.member + when (d.kind) {
                        ApiDoc.Kind.FUNCTION -> "()"
                        ApiDoc.Kind.NAMESPACE -> "."
                        else -> ""
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .widthIn(min = 48.dp)
                            // Keep the editor (and the soft keyboard) focused while tapping.
                            .clickable { accept(d); runCatching { focus.requestFocus() } }
                            .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            label,
                            style = Term.mono.copy(
                                color = if (d.kind == ApiDoc.Kind.KEYWORD) Term.prompt else Term.fg,
                                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    }
                }
            }
        }
        val symbols = listOf("↶", "↷", "  ", "(", ")", "{", "}", "[", "]", "\"", "'", "=", ".", ",", ":", ";", "#", "-", "~", "<", ">", "Ln")
        LazyRow(Modifier.fillMaxWidth().background(Term.surface)) {
            items(symbols) { s ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .clickable {
                            when (s) {
                                "↶" -> undo()
                                "↷" -> redo()
                                "Ln" -> gotoOpen = true
                                else -> insert(s)
                            }
                        }
                        .padding(horizontal = 12.dp),
                ) {
                    Text(if (s == "  ") "⇥" else s, style = Term.mono.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }

    if (gotoOpen) {
        GotoLineDialog(lineCount, onDismiss = { gotoOpen = false }) { line ->
            gotoOpen = false
            gotoLine(line)
        }
    }
    if (referenceOpen) {
        ApiReferenceSheet(script.lang, onDismiss = { referenceOpen = false }) { doc ->
            referenceOpen = false
            insert(doc.name + if (doc.kind == ApiDoc.Kind.FUNCTION) "(" else "")
            focusEditor()
        }
    }
}

@Composable
private fun EditorButton(label: String, onClick: () -> Unit) {
    Text(
        "[$label]",
        style = Term.mono.copy(color = Term.fg),
        modifier = Modifier
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}

@Composable
private fun BarButton(label: String, color: Color = Term.fg, description: String? = null, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Text(label, style = Term.mono.copy(color = color, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun BarField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onAction: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    modifier: Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Term.mono,
        cursorBrush = SolidColor(Term.prompt),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onSearch = { onAction() }, onDone = { onAction() }),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.heightIn(min = 48.dp)) {
                if (value.text.isEmpty()) Text(placeholder, style = Term.mono.copy(color = Term.dim))
                inner()
            }
        },
        modifier = modifier.onPreviewKeyEvent(onKey),
    )
}

private fun KeyEvent.isEnter() = key == Key.Enter || key == Key.NumPadEnter

@Composable
private fun FindBar(
    query: TextFieldValue,
    onQuery: (TextFieldValue) -> Unit,
    replacement: TextFieldValue,
    onReplacement: (TextFieldValue) -> Unit,
    replaceOpen: Boolean,
    onToggleReplace: () -> Unit,
    ignoreCase: Boolean,
    onToggleCase: () -> Unit,
    count: String,
    noMatch: Boolean,
    focusRequester: FocusRequester,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Term.surface.copy(alpha = 0.7f)).testTag("findbar")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BarButton("Aa", if (ignoreCase) Term.dim else Term.prompt, if (ignoreCase) "Match case" else "Ignore case", onToggleCase)
            BarField(
                value = query,
                onValueChange = onQuery,
                placeholder = "find",
                imeAction = ImeAction.Search,
                onAction = onNext,
                onKey = { e ->
                    if (e.type == KeyEventType.KeyDown && e.isEnter()) {
                        if (e.isShiftPressed) onPrev() else onNext()
                        true
                    } else e.isEnter() // swallow Enter key-ups too
                },
                modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("find"),
            )
            Text(count, style = Term.mono.copy(color = if (noMatch) Term.error else Term.dim), modifier = Modifier.padding(horizontal = 4.dp))
            BarButton("↑", description = "Previous match", onClick = onPrev)
            BarButton("↓", description = "Next match", onClick = onNext)
            BarButton("⇄", if (replaceOpen) Term.prompt else Term.fg, "Replace", onToggleReplace)
            BarButton("×", description = "Close find", onClick = onClose)
        }
        if (replaceOpen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarField(
                    value = replacement,
                    onValueChange = onReplacement,
                    placeholder = "replace with",
                    imeAction = ImeAction.Done,
                    onAction = onReplace,
                    onKey = { e ->
                        if (e.type == KeyEventType.KeyDown && e.isEnter()) {
                            onReplace()
                            true
                        } else e.isEnter()
                    },
                    modifier = Modifier.weight(1f).padding(start = 12.dp).testTag("replace"),
                )
                EditorButton("replace", onReplace)
                EditorButton("all", onReplaceAll)
            }
        }
    }
}

@Composable
private fun GotoLineDialog(lineCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val line = text.trim().toIntOrNull()?.takeIf { it in 1..lineCount }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to line") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(9) },
                label = { Text("Line (1–$lineCount)") },
                singleLine = true,
                isError = text.isNotEmpty() && line == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { line?.let(onGo) }),
                modifier = Modifier
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.isEnter()) {
                            line?.let(onGo)
                            true
                        } else false
                    },
            )
        },
        confirmButton = { TextButton(onClick = { line?.let(onGo) }, enabled = line != null) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full API reference for the script's language; tapping an entry inserts it at the cursor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiReferenceSheet(lang: ScriptLang, onDismiss: () -> Unit, onPick: (ApiDoc) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val docs = remember(lang) { ApiDocs.forLang(lang).filter { it.kind != ApiDoc.Kind.KEYWORD } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Term.surface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        LazyColumn(Modifier.navigationBarsPadding().padding(vertical = 8.dp).testTag("apiref")) {
            item {
                Text(
                    "API reference · ${if (lang == ScriptLang.LUA) "Lua" else "JavaScript"}",
                    style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(docs) { d ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onPick(d) }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(d.signature, style = Term.mono.copy(color = if (d.kind == ApiDoc.Kind.NAMESPACE) Term.info else Term.fg))
                    Text(d.doc, style = Term.mono.copy(color = Term.dim, fontSize = Term.mono.fontSize * 0.85f))
                }
            }
            item {
                Text(
                    "keywords: " + ApiDocs.keywords(lang).joinToString(" "),
                    style = Term.mono.copy(color = Term.dim, fontSize = Term.mono.fontSize * 0.85f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Cursor at the start of [line] (1-based), or at the end of the text. */
private fun selectionAtLine(text: String, line: Int?): TextRange =
    if (line == null || line < 1) TextRange(text.length) else TextRange(EditorLogic.lineStart(text, line))

/** Snapshot-based undo: a new step after a pause in typing or at a newline. */
private class UndoHistory(initial: String) {
    private val undo = ArrayDeque<String>().apply { addLast(initial) }
    private val redo = ArrayDeque<String>()
    private var lastRecord = 0L

    fun record(text: String) {
        val now = System.currentTimeMillis()
        val newline = text.length > undo.last().length && text.count { it == '\n' } != undo.last().count { it == '\n' }
        if (now - lastRecord > 600 || newline || undo.size == 1) {
            undo.addLast(text)
            if (undo.size > 200) undo.removeFirst()
        } else {
            undo[undo.lastIndex] = text
        }
        lastRecord = now
        redo.clear()
    }

    /** The next [record] starts a new undo step (used around replace operations). */
    fun checkpoint() {
        lastRecord = 0
    }

    fun undo(): String? {
        if (undo.size <= 1) return null
        redo.addLast(undo.removeLast())
        lastRecord = 0
        return undo.last()
    }

    fun redo(): String? {
        val text = redo.removeLastOrNull() ?: return null
        undo.addLast(text)
        lastRecord = 0
        return text
    }
}

/** Find matches to draw: a window of start offsets around the current one. */
private class MatchHighlights(val starts: IntArray, val length: Int, val current: Int)

/** Colours comments, strings, numbers and keywords; flashes one line (error jump); marks find matches. */
private class SyntaxHighlighter(
    lang: ScriptLang,
    private val flashLine: Int?,
    private val matches: MatchHighlights?,
) : VisualTransformation {
    private val keywords = ApiDocs.keywords(lang).toSet()
    private val token = Regex(
        (if (lang == ScriptLang.LUA) "--[^\n]*" else "//[^\n]*") +
            "|\"(?:\\\\.|[^\"\\\\\n])*\"|'(?:\\\\.|[^'\\\\\n])*'|`[^`]*`|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b"
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        val styled = buildAnnotatedString {
            append(src)
            for (m in token.findAll(src)) {
                val t = m.value
                val style = when {
                    t.startsWith("--") || t.startsWith("//") -> SpanStyle(color = Term.dim, fontStyle = FontStyle.Italic)
                    t.startsWith("\"") || t.startsWith("'") || t.startsWith("`") -> SpanStyle(color = Term.script)
                    t[0].isDigit() -> SpanStyle(color = Term.palette.work)
                    t in keywords -> SpanStyle(color = Term.prompt, fontWeight = FontWeight.Bold)
                    else -> null
                }
                if (style != null) addStyle(style, m.range.first, m.range.last + 1)
            }
            if (flashLine != null) {
                val start = selectionAtLine(src, flashLine).start
                val end = src.indexOf('\n', start).let { if (it < 0) src.length else it }
                if (end > start) addStyle(SpanStyle(background = Term.error.copy(alpha = 0.25f)), start, end)
            }
            if (matches != null) {
                val other = SpanStyle(background = Term.info.copy(alpha = 0.3f))
                val cur = SpanStyle(background = Term.prompt.copy(alpha = 0.55f), color = Term.bg)
                // Offsets can be a beat stale while matches are recomputed after an edit.
                for (s in matches.starts) {
                    val e = s + matches.length
                    if (e <= src.length) addStyle(if (s == matches.current) cur else other, s, e)
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is SyntaxHighlighter && other.flashLine == flashLine &&
        other.keywords == keywords && other.matches === matches

    override fun hashCode() = 31 * (31 * keywords.hashCode() + (flashLine ?: 0)) + System.identityHashCode(matches)
}
