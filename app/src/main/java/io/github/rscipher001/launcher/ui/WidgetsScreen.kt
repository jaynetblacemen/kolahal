package io.github.rscipher001.launcher.ui

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.navigationBarsPadding
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rscipher001.launcher.R
import io.github.rscipher001.launcher.apps.AppRepository
import io.github.rscipher001.launcher.apps.Profile
import io.github.rscipher001.launcher.apps.ProfileKind
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.terminal.Screen
import io.github.rscipher001.launcher.terminal.TerminalViewModel
import io.github.rscipher001.launcher.widgets.KolahalWidgetView
import io.github.rscipher001.launcher.widgets.PlacedWidget
import io.github.rscipher001.launcher.widgets.ProviderEntry
import io.github.rscipher001.launcher.widgets.WidgetHost
import io.github.rscipher001.launcher.widgets.WidgetLayout
import io.github.rscipher001.launcher.widgets.WidgetLocation
import io.github.rscipher001.launcher.widgets.WidgetOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- shared: one hosted widget --------------------------------------------------------------

/** Profile state for a widget: paused/locked profiles get a placeholder instead of the widget. */
private fun List<Profile>.pausedKind(serial: Long): ProfileKind? = firstOrNull { it.serial == serial }?.takeIf { it.quiet }?.kind

/**
 * Renders placed widget [w] at [height] via the shared AppWidgetHost. A long-press anywhere on it
 * calls [onLongPress]. When its profile is paused, [placeholder] is shown instead.
 */
@Composable
private fun HostedWidget(
    w: PlacedWidget,
    height: Dp,
    pausedKind: ProfileKind?,
    onLongPress: (() -> Unit)?,
    placeholder: @Composable (String) -> Unit,
) {
    val id = w.id ?: return
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val providersVersion by widgets.providersVersion.collectAsStateWithLifecycle()
    if (pausedKind != null) {
        placeholder(if (pausedKind == ProfileKind.PRIVATE) "private space locked" else "${pausedKind.label} profile paused")
        return
    }
    val info = remember(id, providersVersion) { widgets.info(id) }
    if (info == null) {
        placeholder("widget unavailable")
        return
    }
    val longPress by rememberUpdatedState(onLongPress)
    key(id) {
        AndroidView(
            factory = { ctx ->
                widgets.host.createView(ctx, id, info).apply {
                    (this as? KolahalWidgetView)?.onLongPress = { longPress?.invoke() }
                }
            },
            modifier = Modifier.fillMaxWidth().height(height),
        )
    }
}

// ---- home: under the terminal header ------------------------------------------------------------

/**
 * Widgets placed on home (`widgets.home`), directly under the header. Collapses to nothing with
 * the header ([visible] = false) so the log keeps its room; views stay alive while collapsed.
 * Long-press opens the Widgets page to edit them.
 */
@Composable
fun HomeWidgets(vm: TerminalViewModel, visible: Boolean) {
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val all by widgets.widgets.collectAsStateWithLifecycle()
    val version by vm.config.version.collectAsStateWithLifecycle()
    val enabled = remember(version) { vm.config[Keys.WIDGETS_HOME] }
    val cap = remember(version) { vm.config[Keys.WIDGETS_HEIGHT].dp }
    val home = remember(all) { WidgetOrder.inLocation(all, WidgetLocation.HOME) }
    if (!enabled || home.isEmpty()) return
    val profiles by remember { AppRepository.get(context).profiles }.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxWidth()
            // Folds and unfolds with the header animation instead of snapping.
            .animateContentSize()
            .then(if (visible) Modifier.heightIn(max = cap) else Modifier.height(0.dp))
            .clipToBounds()
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            home.forEach { w ->
                key(w.key) {
                    HostedWidget(
                        w = w,
                        height = minOf(w.heightDp.dp, cap),
                        pausedKind = profiles.pausedKind(w.profileSerial),
                        onLongPress = { vm.openScreen(Screen.WIDGETS) },
                    ) { why ->
                        Text(
                            "[$why] ${w.packageName}",
                            style = Term.mono.copy(color = Term.dim),
                            modifier = Modifier.fillMaxWidth().alpha(0.7f).padding(vertical = 6.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---- the Widgets page ---------------------------------------------------------------------------

/**
 * Widgets page (Material 3 Expressive): every placed widget, live, in two groups (on home / page
 * only). Long-press a widget or tap Edit for its toolbar: move, resize in 48dp steps, move to or
 * from home, remove. Entries restored from a backup show an "Add again" row.
 */
/**
 * The widgets page beside the terminal (`widgets.page`): every widget placed on the "Widgets page",
 * full width and scrolling, in the terminal's colours. Add / edit jump to the GUI pages.
 */
@Composable
fun WidgetPanel(vm: TerminalViewModel) {
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val all by widgets.widgets.collectAsStateWithLifecycle()
    val panel = remember(all) { WidgetOrder.inLocation(all, WidgetLocation.PANEL) }
    val profiles by remember { AppRepository.get(context).profiles }.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(Term.bg)
            .cutoutSafePadding()
            .navigationBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text("Widgets", style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            Text(
                "+ Add",
                style = Term.mono.copy(color = Term.prompt),
                modifier = Modifier
                    .clickable { widgets.addLocation = WidgetLocation.PANEL; vm.openScreen(Screen.WIDGET_PICKER) }
                    .heightIn(min = 48.dp)
                    .wrapContentHeight()
                    .padding(horizontal = 12.dp),
            )
            Text(
                "Edit",
                style = Term.mono.copy(color = Term.dim),
                modifier = Modifier
                    .clickable { vm.openScreen(Screen.WIDGETS) }
                    .heightIn(min = 48.dp)
                    .wrapContentHeight()
                    .padding(start = 12.dp),
            )
        }
        if (panel.isEmpty()) {
            Text(
                "No widgets here yet · tap + Add, or long-press a widget on home and move it to this page",
                style = Term.mono.copy(color = Term.dim),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            )
            return@Column
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            panel.forEach { w ->
                key(w.key) {
                    HostedWidget(
                        w = w,
                        height = w.heightDp.dp,
                        pausedKind = profiles.pausedKind(w.profileSerial),
                        onLongPress = { vm.openScreen(Screen.WIDGETS) },
                    ) { why ->
                        Text(
                            "[$why] ${w.packageName}",
                            style = Term.mono.copy(color = Term.dim),
                            modifier = Modifier.fillMaxWidth().alpha(0.7f).padding(vertical = 6.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WidgetsScreen(vm: TerminalViewModel, onReAdd: (ProviderEntry, PlacedWidget) -> Unit) {
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val all by widgets.widgets.collectAsStateWithLifecycle()
    val providersVersion by widgets.providersVersion.collectAsStateWithLifecycle()
    val profiles by remember { AppRepository.get(context).profiles }.collectAsStateWithLifecycle()
    val configVersion = vm.config.version.collectAsStateWithLifecycle()
    val useSystemColors by remember { derivedStateOf { configVersion.value; vm.config[Keys.GUI_COLORS] == "system" } }
    val homeOn by remember { derivedStateOf { configVersion.value; vm.config[Keys.WIDGETS_HOME] } }
    var editing by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<PlacedWidget?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { widgets.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler { if (editing || selected != null) { editing = false; selected = null } else vm.closeScreen() }

    val home = remember(all) { WidgetOrder.inLocation(all, WidgetLocation.HOME) }
    val panel = remember(all) { WidgetOrder.inLocation(all, WidgetLocation.PANEL) }
    val pending = remember(all) { WidgetOrder.sorted(all.filter { it.pending }) }
    val labels = remember(all, providersVersion) {
        all.associate { w -> w.key to (w.id?.let { widgets.info(it) }?.loadLabel(context.packageManager) ?: w.provider.substringAfterLast('.')) }
    }

    GuiTheme(useSystemColors) {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("Widgets") },
                    subtitle = {
                        val n = home.size + panel.size
                        Text(if (n == 0) "Home-screen widgets" else "$n placed · ${home.size} on home")
                    },
                    navigationIcon = { BackButton(vm::closeScreen) },
                    actions = {
                        if (home.isNotEmpty() || panel.isNotEmpty()) {
                            IconToggleButton(checked = editing, onCheckedChange = { editing = it; selected = null }) {
                                Icon(painterResource(if (editing) R.drawable.ic_check else R.drawable.ic_edit), if (editing) "Done" else "Edit widgets")
                            }
                        }
                    },
                    scrollBehavior = scroll,
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { widgets.addLocation = WidgetLocation.PANEL; vm.openScreen(Screen.WIDGET_PICKER) },
                    icon = { Icon(painterResource(R.drawable.ic_add), null) },
                    text = { Text("Add widget") },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp)
                    .padding(horizontal = 16.dp),
            ) {
                if (home.isEmpty() && panel.isEmpty() && pending.isEmpty()) {
                    Text(
                        "No widgets yet. Add one here, then long-press it to move it onto the home screen under the clock.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                    ActionGroup(
                        listOf(
                            Action(R.drawable.ic_widgets, "Add a widget", "Clock, weather, calendar, music… from any app") {
                                widgets.addLocation = WidgetLocation.PANEL
                                vm.openScreen(Screen.WIDGET_PICKER)
                            },
                        )
                    )
                }
                val groups = listOf(
                    Triple("On home", home, if (homeOn) "Shown under the header; hidden while the log is in use" else "widgets.home is off — they only show here"),
                    Triple("Widgets page", panel, null),
                )
                for ((title, list, note) in groups) {
                    if (list.isEmpty()) continue
                    GroupHeader(title)
                    if (note != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                            if (!homeOn) TextButton(onClick = { vm.setConfig(Keys.WIDGETS_HOME, "on") }) { Text("Turn on") }
                        }
                    }
                    list.forEachIndexed { i, w ->
                        key(w.key) {
                            WidgetCard(
                                w = w,
                                label = labels[w.key] ?: w.provider,
                                index = i,
                                count = list.size,
                                showTools = editing || selected == w.key,
                                pausedKind = profiles.pausedKind(w.profileSerial),
                                minHeight = remember(w.id, providersVersion) { w.id?.let { widgets.info(it) }?.let { widgets.minHeightDp(it) } ?: WidgetLayout.MIN_DP },
                                onLongPress = { selected = if (selected == w.key) null else w.key },
                                onMove = { widgets.move(w.key, it) },
                                onResize = { widgets.resize(w.key, it) },
                                onToggleHome = { widgets.setLocation(w.key, if (w.onHome) WidgetLocation.PANEL else WidgetLocation.HOME) },
                                onRemove = { confirmRemove = w },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (pending.isNotEmpty()) {
                    GroupHeader("Restored from backup")
                    Text(
                        "Widgets can't be copied between installs — add each one again with one tap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        pending.forEachIndexed { i, w ->
                            PendingRow(w, i, pending.size, providersVersion, onReAdd = onReAdd, onDismiss = { widgets.remove(w.key) })
                        }
                    }
                }
                if (home.isNotEmpty() || panel.isNotEmpty()) {
                    Text(
                        "Long-press a widget for move, resize and remove. Terminal: widgets ls · widget add home",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    )
                }
            }
        }

        confirmRemove?.let { w ->
            AlertDialog(
                onDismissRequest = { confirmRemove = null },
                icon = { Icon(painterResource(R.drawable.ic_delete), null) },
                title = { Text("Remove ${labels[w.key] ?: "widget"}?") },
                text = { Text("The widget is deleted; add it again from the picker any time.") },
                confirmButton = {
                    TextButton(
                        onClick = { confirmRemove = null; selected = null; widgets.remove(w.key) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun WidgetCard(
    w: PlacedWidget,
    label: String,
    index: Int,
    count: Int,
    showTools: Boolean,
    pausedKind: ProfileKind?,
    minHeight: Int,
    onLongPress: () -> Unit,
    onMove: (Int) -> Unit,
    onResize: (Int) -> Unit,
    onToggleHome: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            if (showTools) {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        "$label · ${w.heightDp}dp",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { onMove(-1) }, enabled = index > 0) { Icon(painterResource(R.drawable.ic_arrow_up), "Move up") }
                        IconButton(onClick = { onMove(1) }, enabled = index < count - 1) { Icon(painterResource(R.drawable.ic_arrow_down), "Move down") }
                        IconButton(onClick = { onResize(-WidgetLayout.STEP_DP) }, enabled = w.heightDp > minHeight) { Icon(painterResource(R.drawable.ic_remove), "Shorter") }
                        IconButton(onClick = { onResize(WidgetLayout.STEP_DP) }, enabled = w.heightDp < WidgetLayout.MAX_DP) { Icon(painterResource(R.drawable.ic_add), "Taller") }
                        if (w.onHome) {
                            FilledTonalIconButton(onClick = onToggleHome) { Icon(painterResource(R.drawable.ic_home), "Remove from home") }
                        } else {
                            IconButton(onClick = onToggleHome) { Icon(painterResource(R.drawable.ic_home), "Show on home") }
                        }
                        IconButton(onClick = onRemove) { Icon(painterResource(R.drawable.ic_delete), "Remove", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Box(Modifier.clip(if (showTools) RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp) else MaterialTheme.shapes.extraLarge)) {
                HostedWidget(w, w.heightDp.dp, pausedKind, onLongPress) { why ->
                    Box(
                        Modifier.fillMaxWidth().height(w.heightDp.dp).alpha(0.6f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            TextButton(onClick = onLongPress) { Text("Edit") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingRow(
    w: PlacedWidget,
    index: Int,
    count: Int,
    providersVersion: Int,
    onReAdd: (ProviderEntry, PlacedWidget) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val entry by produceState<ProviderEntry?>(null, w.provider, w.profileSerial, providersVersion) {
        value = withContext(Dispatchers.IO) { widgets.findProvider(w.provider, w.profileSerial) }
    }
    val found = entry
    val label = found?.info?.loadLabel(context.packageManager) ?: w.provider.substringAfterLast('.')
    SegmentedListItem(
        colors = segColors(),
        shapes = segShapes(index, count),
        leadingContent = { Icon(painterResource(R.drawable.ic_widgets), null, tint = MaterialTheme.colorScheme.primary) },
        overlineContent = { Text(w.packageName, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (found == null) "app not installed" else "${w.location} · ${w.heightDp}dp") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { found?.let { onReAdd(it, w) } }, enabled = found != null) { Text("Add again") }
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_delete), "Forget") }
            }
        },
    ) { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

// ---- picker --------------------------------------------------------------------------------------

private class ProviderGroup(
    val key: String,
    val label: String,
    val icon: ImageBitmap?,
    val work: ProfileKind?,
    val providers: List<ProviderEntry>,
)

private fun Drawable.toImage(maxPx: Int): ImageBitmap? = runCatching {
    val w = intrinsicWidth.takeIf { it > 0 } ?: maxPx
    val h = intrinsicHeight.takeIf { it > 0 } ?: maxPx
    val scale = minOf(1f, maxPx.toFloat() / maxOf(w, h))
    toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)).asImageBitmap()
}.getOrNull()

private fun loadGroups(context: Context, widgets: WidgetHost, profiles: List<Profile>, iconPx: Int): List<ProviderGroup> {
    val pm = context.packageManager
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    return widgets.providers().groupBy { it.packageName to it.serial }.map { (k, list) ->
        val (pkg, serial) = k
        val user = list.first().user
        val ai = runCatching { launcherApps.getApplicationInfo(pkg, 0, user) }.getOrNull()
        val label = ai?.let { pm.getApplicationLabel(it).toString() } ?: pkg
        val icon = ai?.let { runCatching { pm.getUserBadgedIcon(pm.getApplicationIcon(it), user) }.getOrNull() }?.toImage(iconPx)
        val kind = profiles.firstOrNull { it.serial == serial }?.kind?.takeIf { it != ProfileKind.PERSONAL }
        ProviderGroup("$pkg:$serial", label, icon, kind, list.sortedBy { it.info.loadLabel(pm).lowercase() })
    }.sortedWith(compareBy({ it.label.lowercase() }, { it.work?.ordinal ?: -1 }))
}

/**
 * Widget picker: installed providers from every profile, grouped by app (work/private badge),
 * each with its preview and default size. Picking starts the bind flow in MainActivity.
 */
@Composable
fun WidgetPickerScreen(vm: TerminalViewModel, onPick: (ProviderEntry) -> Unit) {
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val providersVersion by widgets.providersVersion.collectAsStateWithLifecycle()
    val profiles by remember { AppRepository.get(context).profiles }.collectAsStateWithLifecycle()
    val configVersion = vm.config.version.collectAsStateWithLifecycle()
    val useSystemColors by remember { derivedStateOf { configVersion.value; vm.config[Keys.GUI_COLORS] == "system" } }
    val iconPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val groups by produceState<List<ProviderGroup>?>(null, providersVersion, profiles) {
        value = withContext(Dispatchers.IO) { loadGroups(context, widgets, profiles, iconPx) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { widgets.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler { widgets.addLocation = WidgetLocation.PANEL; vm.closeScreen() }
    val toHome = widgets.addLocation == WidgetLocation.HOME

    GuiTheme(useSystemColors) {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("Add widget") },
                    subtitle = { Text(if (toHome) "Goes on home, under the header" else "Goes on the Widgets page") },
                    navigationIcon = { BackButton { widgets.addLocation = WidgetLocation.PANEL; vm.closeScreen() } },
                    scrollBehavior = scroll,
                )
            },
        ) { padding ->
            val shown = remember(groups, query) {
                val q = query.trim().lowercase()
                groups?.let { all ->
                    if (q.isEmpty()) all else all.mapNotNull { g ->
                        if (g.label.lowercase().contains(q)) g
                        else g.providers.filter { it.info.loadLabel(context.packageManager).lowercase().contains(q) }
                            .takeIf { it.isNotEmpty() }?.let { ProviderGroup(g.key, g.label, g.icon, g.work, it) }
                    }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search widgets") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                when {
                    shown == null -> Unit
                    shown.isEmpty() -> item(key = "empty") {
                        Text(
                            if (query.isBlank()) "No apps offer widgets." else "Nothing matches “$query”.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                    else -> shown.forEach { g ->
                        item(key = "g:${g.key}") { AppHeader(g) }
                        items(g.providers, key = { "p:${g.key}:${it.flat}" }) { p -> ProviderCard(p, widgets) { onPick(p) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(g: ProviderGroup) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp)) {
        if (g.icon != null) Image(g.icon, null, Modifier.size(32.dp)) else Icon(painterResource(R.drawable.ic_widgets), null, Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Text(g.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f, fill = false))
        if (g.work != null) {
            Spacer(Modifier.width(8.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                Text(g.work.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun ProviderCard(p: ProviderEntry, widgets: WidgetHost, onClick: () -> Unit) {
    val context = LocalContext.current
    val info = p.info
    val label = remember(info) { info.loadLabel(context.packageManager) }
    val description = remember(info) { if (Build.VERSION.SDK_INT >= 31) info.loadDescription(context)?.toString() else null }
    val maxPx = with(LocalDensity.current) { 280.dp.roundToPx() }
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching { info.loadPreviewImage(context, 0) }.getOrNull()?.toImage(maxPx)
        }
    }
    val icon by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) { runCatching { info.loadIcon(context, 0) }.getOrNull()?.toImage(maxPx / 3) }
    }
    val canLayoutPreview = Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0 && p.user == Process.myUserHandle()
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                val img = preview
                when {
                    img != null -> Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
                    canLayoutPreview -> LayoutPreview(info)
                    icon != null -> Image(icon!!, null, Modifier.size(56.dp))
                    else -> Icon(painterResource(R.drawable.ic_widgets), null, Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(widgets.sizeLabel(info), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
            }
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Android 12+ `previewLayout`, inflated as a non-interactive view (touches fall through to the card). */
@Composable
private fun LayoutPreview(info: AppWidgetProviderInfo) {
    AndroidView(
        factory = { ctx ->
            object : FrameLayout(ctx) {
                override fun onInterceptTouchEvent(ev: MotionEvent) = true
                override fun dispatchTouchEvent(ev: MotionEvent) = false
            }.apply {
                runCatching {
                    val view = AppWidgetHostView(ctx)
                    view.setAppWidget(0, info)
                    view.updateAppWidget(RemoteViews(info.provider.packageName, info.previewLayout))
                    addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
