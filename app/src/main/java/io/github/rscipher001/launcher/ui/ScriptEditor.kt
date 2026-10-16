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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.rscipher001.launcher.data.Script
import io.github.rscipher001.launcher.data.ScriptLang
import kotlinx.coroutines.delay

/**
 * Monospace editor for scripts: line numbers, light syntax colouring, undo/redo.
 * Ctrl+S saves, Ctrl+R saves and runs, Ctrl+Z / Ctrl+Shift+Z undo/redo, Esc or Back saves and closes.
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
    val keyboard = LocalSoftwareKeyboardController.current
    val history = remember(script.id) { UndoHistory(script.code) }
    var flashLine by remember(script.id) { mutableStateOf(jumpToLine) }

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

    BackHandler { close() }
    LaunchedEffect(script.id) { runCatching { focus.requestFocus() } }
    LaunchedEffect(flashLine) {
        if (flashLine != null) {
            delay(3_000)
            flashLine = null
        }
    }

    val highlighter = remember(script.lang, flashLine, Term.palette) { SyntaxHighlighter(script.lang, flashLine) }
    val lineCount = value.text.count { it == '\n' } + 1
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Term.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.isCtrlPressed && e.key == Key.S -> { save(); true }
                    e.isCtrlPressed && e.key == Key.R -> { run(); true }
                    e.isCtrlPressed && e.isShiftPressed && e.key == Key.Z -> { redo(); true }
                    e.isCtrlPressed && e.key == Key.Z -> { undo(); true }
                    e.isCtrlPressed && e.key == Key.Y -> { redo(); true }
                    e.key == Key.Escape -> { close(); true }
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
                modifier = Modifier.weight(1f),
            )
            EditorButton("save") { save() }
            EditorButton("run") { run() }
            EditorButton("close") { close() }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Tapping empty space below the code focuses the editor.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    value = value.copy(selection = TextRange(value.text.length))
                    focusEditor()
                }
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            Row {
                // Line numbers share the field's style, so they stay aligned while scrolling.
                Text(
                    numbers,
                    style = Term.mono.copy(color = Term.dim, textAlign = TextAlign.End),
                    modifier = Modifier.widthIn(min = 28.dp).padding(start = 6.dp, end = 10.dp),
                )
                Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    BasicTextField(
                        value = value,
                        onValueChange = ::edit,
                        textStyle = Term.mono,
                        cursorBrush = SolidColor(Term.prompt),
                        visualTransformation = highlighter,
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
                            .testTag("editor")
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) { insert("  "); true } else false
                            },
                    )
                }
            }
        }
        val symbols = listOf("↶", "↷", "  ", "(", ")", "{", "}", "[", "]", "\"", "'", "=", ".", ",", ":", ";", "#", "-", "~", "<", ">")
        LazyRow(Modifier.fillMaxWidth().background(Term.surface)) {
            items(symbols) { s ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 40.dp)
                        .clickable {
                            when (s) {
                                "↶" -> undo()
                                "↷" -> redo()
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
}

@Composable
private fun EditorButton(label: String, onClick: () -> Unit) {
    Text(
        "[$label]",
        style = Term.mono.copy(color = Term.fg),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp),
    )
}

/** Cursor at the start of [line] (1-based), or at the end of the text. */
private fun selectionAtLine(text: String, line: Int?): TextRange {
    if (line == null || line < 1) return TextRange(text.length)
    var offset = 0
    repeat(line - 1) {
        val next = text.indexOf('\n', offset)
        if (next < 0) return TextRange(text.length)
        offset = next + 1
    }
    return TextRange(offset)
}

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

/** Colours comments, strings, numbers and keywords; optionally flashes one line (error jump). */
private class SyntaxHighlighter(lang: ScriptLang, private val flashLine: Int?) : VisualTransformation {
    private val keywords = when (lang) {
        ScriptLang.LUA -> setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
            "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
        )
        ScriptLang.JS -> setOf(
            "async", "await", "break", "case", "catch", "const", "continue", "default", "do", "else", "false",
            "finally", "for", "function", "if", "in", "let", "new", "null", "of", "return", "switch", "this",
            "throw", "true", "try", "typeof", "undefined", "var", "while",
        )
    }
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
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is SyntaxHighlighter && other.flashLine == flashLine && other.keywords == keywords
    override fun hashCode() = 31 * keywords.hashCode() + (flashLine ?: 0)
}
