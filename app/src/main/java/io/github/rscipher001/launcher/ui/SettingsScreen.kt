package io.github.rscipher001.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import io.github.rscipher001.launcher.data.ConfigKey
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.terminal.Screen
import io.github.rscipher001.launcher.terminal.TerminalViewModel
import kotlin.math.roundToInt

/** Settings grouped for the GUI; anything not listed lands in "other". */
private val SECTIONS: List<Pair<String, List<ConfigKey<*>>>> = listOf(
    "appearance" to listOf(Keys.THEME, Keys.FONT_SIZE),
    "header" to listOf(
        Keys.HEADER_DATE, Keys.CLOCK_24H, Keys.HEADER_UNLOCKS, Keys.HEADER_BATTERY, Keys.HEADER_NETWORK,
        Keys.HEADER_ALARM, Keys.HEADER_PROFILES, Keys.HEADER_STATUS, Keys.HEADER_DEVICE,
    ),
    "log" to listOf(Keys.SHOW_TIMESTAMPS, Keys.LOG_DIM, Keys.LOG_SPACING),
    "launching" to listOf(Keys.LAUNCH_TOP, Keys.AUTO_LAUNCH, Keys.SUGGEST_IDLE, Keys.SUGGESTIONS),
    "keyboard & key bar" to listOf(Keys.KEYBAR_SHOW, Keys.KEYBAR, Keys.KEYBAR2, Keys.IME_SUGGESTIONS, Keys.FOCUS_ON_HOME),
)

/**
 * GUI for every `config` key plus backup import/export and links to the script manager.
 * Changes apply immediately (the terminal observes the same Config).
 */
@Composable
fun SettingsScreen(
    vm: TerminalViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    // Recompose on any setting change.
    val version by vm.config.version.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    BackHandler { vm.closeScreen() }
    val listed = SECTIONS.flatMap { it.second }.toSet()
    val other = Keys.ALL.filter { it !in listed }

    Column(
        Modifier
            .fillMaxSize()
            .background(Term.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        TopBar("settings", onClose = vm::closeScreen)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            notice?.let { item { Notice(it) } }
            item {
                Section("backup")
                Hint("Settings, aliases, hidden/renamed apps and all scripts in one JSON file — edit it on a computer and import it back.")
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("export…", onExportBackup)
                    Button("import…", onImportBackup)
                }
                Section("scripts")
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("script manager") { vm.openScreen(Screen.SCRIPTS) }
                    Button("setup checklist") { vm.closeScreen(); vm.runCommand("setup") }
                }
            }
            for ((title, keys) in SECTIONS + listOf("other" to other)) {
                if (keys.isEmpty()) continue
                item(key = "section:$title") { Section(title) }
                items(keys, key = { it.key }) { key -> SettingRow(vm, key, version) }
            }
        }
    }
}

@Composable
private fun SettingRow(vm: TerminalViewModel, key: ConfigKey<*>, version: Int) {
    val config = vm.config
    val changed = !config.isDefault(key)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(key.key, style = Term.mono.copy(color = Term.fg, fontWeight = FontWeight.Bold))
                Text(key.help, style = Term.mono.copy(color = Term.dim, fontSize = (Term.fontSize - 2).sp))
            }
            if (changed) {
                Text(
                    "reset",
                    style = Term.mono.copy(color = Term.link, fontSize = (Term.fontSize - 2).sp),
                    modifier = Modifier.clickable { vm.resetConfig(key) }.padding(8.dp),
                )
            }
            if (key is ConfigKey.Bool) {
                Switch(
                    checked = config[key],
                    onCheckedChange = { vm.setConfig(key, if (it) "on" else "off") },
                    colors = SwitchDefaults.colors(checkedTrackColor = Term.prompt, checkedThumbColor = Term.bg),
                )
            }
        }
        when (key) {
            is ConfigKey.Choice -> ChoiceChips(key.choices, config[key]) { vm.setConfig(key, it) }
            is ConfigKey.Num -> NumberSlider(key, config[key], version) { vm.setConfig(key, it.toString()) }
            is ConfigKey.Text -> TextSetting(config[key], version) { vm.setConfig(key, it) }
            is ConfigKey.Bool -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(choices: List<String>, selected: String, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        choices.forEach { c ->
            val on = c == selected
            Text(
                c,
                style = Term.mono.copy(color = if (on) Term.bg else Term.fg),
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .background(if (on) Term.prompt else Term.surface, RoundedCornerShape(4.dp))
                    .clickable { onPick(c) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun NumberSlider(key: ConfigKey.Num, value: Int, version: Int, onDone: (Int) -> Unit) {
    var current by remember(key.key, version) { mutableFloatStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onDone(current.roundToInt()) },
            valueRange = key.range.first.toFloat()..key.range.last.toFloat(),
            steps = (key.range.last - key.range.first - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = Term.prompt,
                activeTrackColor = Term.prompt,
                inactiveTrackColor = Term.surface,
                activeTickColor = Term.bg,
                inactiveTickColor = Term.dim,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(current.roundToInt().toString(), style = Term.mono.copy(color = Term.fg, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun TextSetting(value: String, version: Int, onDone: (String) -> Unit) {
    var text by remember(version) { mutableStateOf(value) }
    val dirty = text != value
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = Term.mono,
            cursorBrush = SolidColor(Term.prompt),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            modifier = Modifier
                .weight(1f)
                .border(1.dp, Term.dim.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
        if (dirty) {
            Spacer(Modifier.width(8.dp))
            Button("apply") { onDone(text) }
        }
    }
}

@Composable
fun TopBar(title: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(Term.surface).padding(start = 16.dp),
    ) {
        Text(title, style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold, fontSize = (Term.fontSize + 2).sp), modifier = Modifier.weight(1f))
        actions()
        Text(
            "[close]",
            style = Term.mono.copy(color = Term.fg),
            modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 14.dp),
        )
    }
}

@Composable
fun Section(title: String) {
    Text(
        "── $title ──",
        style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun Hint(text: String) {
    Text(
        text,
        style = Term.mono.copy(color = Term.dim, fontSize = (Term.fontSize - 2).sp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
fun Notice(text: String) {
    Text(
        text,
        style = Term.mono.copy(color = Term.info),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Term.surface, RoundedCornerShape(6.dp))
            .padding(12.dp),
    )
}

@Composable
fun Button(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = Term.mono.copy(color = Term.fg),
        modifier = Modifier
            .heightIn(min = 44.dp)
            .border(1.dp, Term.prompt.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    )
}
