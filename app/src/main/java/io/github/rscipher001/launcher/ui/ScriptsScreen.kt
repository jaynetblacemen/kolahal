package io.github.rscipher001.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rscipher001.launcher.data.Script
import io.github.rscipher001.launcher.data.ScriptLang
import io.github.rscipher001.launcher.script.ScriptMeta
import io.github.rscipher001.launcher.terminal.CommandProcessor
import io.github.rscipher001.launcher.terminal.TerminalViewModel
import java.text.DateFormat
import java.util.Date

private val INTERVALS = listOf(0 to "off", 15 to "15m", 30 to "30m", 60 to "1h", 180 to "3h", 360 to "6h", 720 to "12h", 1440 to "1d")

/**
 * Script manager: import `.lua` / `.js` files from the file manager, create scripts, and set
 * each script's timers, notifications and state without typing commands.
 */
@Composable
fun ScriptsScreen(
    vm: TerminalViewModel,
    onImportFiles: () -> Unit,
    onExportScript: (Script) -> Unit,
) {
    val scripts by vm.scriptList.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    BackHandler { vm.closeScreen() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Term.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        TopBar("scripts", onClose = vm::closeScreen)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            notice?.let { item { Notice(it) } }
            item {
                Section("add")
                Hint("Import picks one or more .lua / .js files from the file manager; the file name becomes the script name (importing again updates it).")
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("import files…", onImportFiles)
                }
                NewScript(vm)
                Section(if (scripts.isEmpty()) "no scripts yet" else "${scripts.size} scripts")
            }
            items(scripts, key = { it.id }) { s -> ScriptCard(vm, s, onExportScript) }
        }
    }
}

@Composable
private fun NewScript(vm: TerminalViewModel) {
    var name by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf(ScriptLang.LUA) }
    var command by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(name, "new script name", Modifier.weight(1f)) { name = it }
            Spacer(Modifier.width(8.dp))
            Button("create") { vm.newScript(name, lang, command) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Toggle("lua", lang == ScriptLang.LUA) { lang = ScriptLang.LUA }
            Spacer(Modifier.width(8.dp))
            Toggle("js", lang == ScriptLang.JS) { lang = ScriptLang.JS }
            Spacer(Modifier.width(16.dp))
            Toggle("@command template", command) { command = !command }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptCard(vm: TerminalViewModel, s: Script, onExport: (Script) -> Unit) {
    var confirmDelete by remember(s.id) { mutableStateOf(false) }
    var times by remember(s.id, s.atTimes) { mutableStateOf(s.atTimes.joinToString(", ")) }
    var timesError by remember(s.id) { mutableStateOf(false) }
    val isCommand = remember(s.code) { ScriptMeta.isCommand(s.code) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Term.surface, RoundedCornerShape(6.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${s.name}.${s.lang.ext}" + if (isCommand) "  · command" else "",
                    style = Term.mono.copy(color = scriptColor(s.name), fontWeight = FontWeight.Bold),
                )
                Text(
                    s.lastRun?.let { "last run ${df.format(Date(it))} · ${s.lastStatus.orEmpty()}" } ?: "never run",
                    style = Term.mono.copy(color = Term.dim, fontSize = (Term.fontSize - 2).sp),
                )
            }
            Text("on", style = Term.mono.copy(color = Term.dim, fontSize = (Term.fontSize - 2).sp))
            Switch(
                checked = s.enabled,
                onCheckedChange = { vm.setScriptEnabled(s, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Term.prompt, checkedThumbColor = Term.bg),
            )
        }

        Label("every")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            INTERVALS.forEach { (minutes, label) ->
                Toggle(label, s.intervalMin == minutes) { vm.setScriptInterval(s, minutes) }
            }
            if (s.intervalMin > 0 && INTERVALS.none { it.first == s.intervalMin }) {
                Toggle(CommandProcessor.formatInterval(s.intervalMin), true) {}
            }
        }

        Label("daily at (HH:mm, comma separated)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(times, "e.g. 08:00, 18:30", Modifier.weight(1f), error = timesError) {
                times = it
                timesError = false
            }
            if (times != s.atTimes.joinToString(", ")) {
                Spacer(Modifier.width(8.dp))
                Button("set") { timesError = !vm.setScriptTimes(s, times) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("notify", style = Term.mono.copy(color = Term.fg), modifier = Modifier.weight(1f))
            Switch(
                checked = s.notify,
                onCheckedChange = { vm.setScriptNotify(s, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Term.prompt, checkedThumbColor = Term.bg),
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Button("run") { vm.runScriptNow(s) }
            Button("edit") { vm.openEditor(s) }
            Button("export…") { onExport(s) }
            Text(
                if (confirmDelete) "tap again to delete" else "delete",
                style = Term.mono.copy(color = Term.error),
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .border(1.dp, Term.error.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .clickable { if (confirmDelete) vm.deleteScript(s) else confirmDelete = true }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = Term.mono.copy(color = Term.dim, fontSize = (Term.fontSize - 2).sp),
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = Term.mono.copy(color = if (on) Term.bg else Term.fg),
        modifier = Modifier
            .heightIn(min = 40.dp)
            .background(if (on) Term.prompt else Term.bg, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun Field(
    value: String,
    placeholder: String,
    modifier: Modifier,
    error: Boolean = false,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = Term.mono,
        cursorBrush = SolidColor(Term.prompt),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, style = Term.mono.copy(color = Term.dim))
            inner()
        },
        modifier = modifier
            .border(1.dp, if (error) Term.error else Term.dim.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}
