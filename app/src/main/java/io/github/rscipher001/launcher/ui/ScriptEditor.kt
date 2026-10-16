package io.github.rscipher001.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.rscipher001.launcher.data.Script

/**
 * Plain monospace editor for scripts. Ctrl+S saves, Ctrl+R saves and runs, Esc or Back saves and
 * closes. Edits survive activity recreation.
 */
@Composable
fun ScriptEditor(
    script: Script,
    onSave: (String) -> Unit,
    onRun: (String) -> Unit,
    onClose: () -> Unit,
) {
    var value by rememberSaveable(script.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(script.code))
    }
    var saved by rememberSaveable(script.id) { mutableStateOf(script.code) }
    val dirty = value.text != saved
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

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

    fun insert(s: String) {
        val sel = value.selection
        value = TextFieldValue(value.text.replaceRange(sel.min, sel.max, s), TextRange(sel.min + s.length))
    }

    fun focusEditor() {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    BackHandler { close() }
    LaunchedEffect(script.id) { runCatching { focus.requestFocus() } }

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
                    e.key == Key.Escape -> { close(); true }
                    else -> false
                }
            }
    ) {
        Row(Modifier.fillMaxWidth().background(Term.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "edit ${script.name}.${script.lang.ext}" + if (dirty) " *" else "",
                style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            EditorButton("save") { save() }
            Spacer(Modifier.width(12.dp))
            EditorButton("run") { run() }
            Spacer(Modifier.width(12.dp))
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
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = Term.mono,
                cursorBrush = SolidColor(Term.prompt),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    // Plain text (not password) so non-Latin layouts and voice input work in code.
                    keyboardType = KeyboardType.Text,
                ),
                modifier = Modifier
                    .widthIn(min = 600.dp)
                    .focusRequester(focus)
                    .testTag("editor")
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) { insert("  "); true } else false
                    },
            )
        }
        val symbols = listOf("  ", "(", ")", "{", "}", "[", "]", "\"", "'", "=", ".", ",", ":", ";", "#", "-", "~", "<", ">")
        LazyRow(Modifier.fillMaxWidth().background(Term.surface)) {
            items(symbols) { s ->
                Box(
                    Modifier
                        .clickable { insert(s) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
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
        modifier = Modifier.clickable(onClick = onClick),
    )
}
