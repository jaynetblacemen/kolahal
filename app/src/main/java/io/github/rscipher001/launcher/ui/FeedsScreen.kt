package io.github.rscipher001.launcher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rscipher001.launcher.R
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.feeds.FeedInfo
import io.github.rscipher001.launcher.feeds.FeedItem
import io.github.rscipher001.launcher.feeds.FeedRepository
import io.github.rscipher001.launcher.feeds.FeedScheduler
import io.github.rscipher001.launcher.feeds.FeedUrl
import io.github.rscipher001.launcher.feeds.exportOpml
import io.github.rscipher001.launcher.feeds.importOpml
import io.github.rscipher001.launcher.script.Files
import io.github.rscipher001.launcher.terminal.CommandProcessor
import io.github.rscipher001.launcher.terminal.TerminalViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** Per-feed refresh presets (minutes) for the connected button group. */
private val FEED_INTERVALS = listOf(15 to "15m", 30 to "30m", 60 to "1h", 180 to "3h", 360 to "6h", 720 to "12h", 1440 to "24h")

/** Items shown per feed (the database keeps at most [io.github.rscipher001.launcher.feeds.FeedDb.MAX_ITEMS_PER_FEED]). */
private const val ITEM_LIMIT = 300

private fun hostOf(url: String): String =
    runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

private fun FeedInfo.displayTitle(): String = title?.takeIf { it.isNotBlank() } ?: hostOf(url)

private fun relative(time: Long): String =
    if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) "just now" else relativeSpan(time)

private fun relativeSpan(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/**
 * Feed manager (Material 3 Expressive): subscriptions with unread counts, per-feed settings in a
 * bottom sheet, an item list per feed, and OPML import/export. Reads and writes go to
 * [FeedRepository] on [Dispatchers.IO]; lists re-query whenever [FeedRepository.version] changes
 * (the fetch worker runs in this process, so new items show up live).
 */
@Composable
fun FeedsScreen(vm: TerminalViewModel, onRequestNotifications: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { FeedRepository.get(context) }
    val repoVersion by repo.version.collectAsStateWithLifecycle()
    val configVersion = vm.config.version.collectAsStateWithLifecycle()
    val useSystemColors by remember { derivedStateOf { configVersion.value; vm.config[Keys.GUI_COLORS] == "system" } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val feeds by produceState<List<FeedInfo>?>(null, repoVersion) {
        value = withContext(Dispatchers.IO) { repo.all() }
    }
    var openFeedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sheetFeedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    BackHandler { if (openFeedId != null) openFeedId = null else vm.closeScreen() }

    /** Runs [block] off the main thread and shows its result (or failure) as a snackbar. */
    fun io(label: String, block: suspend () -> String?) {
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { "$label failed: ${it.message ?: it.javaClass.simpleName}" }
            }
            msg?.let { snackbar.showSnackbar(it) }
        }
    }

    val importOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io("import") {
            val added = repo.importOpml(Files.readText(context, uri))
            FeedScheduler.sync(context)
            if (added > 0) FeedScheduler.fetchNow(context)
            if (added == 0) "no new feeds in that file" else "imported $added new feeds — fetching…"
        }
    }
    val exportOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) io("export") {
            Files.writeText(context, uri, repo.exportOpml())
            "exported ${repo.feeds().size} feeds"
        }
    }

    GuiTheme(useSystemColors) {
        val open = openFeedId?.let { id -> feeds?.firstOrNull { it.id == id } }
        if (openFeedId != null && open != null) {
            FeedItemsPage(
                feed = open,
                repo = repo,
                version = repoVersion,
                snackbar = snackbar,
                onBack = { openFeedId = null },
                onRefresh = { io("refresh") { FeedScheduler.fetchNow(context, open.id); "fetching ${open.displayTitle()}…" } },
                onMarkAllRead = { io("mark read") { repo.markAllRead(open.id); null } },
                onOpenItem = { item -> openItem(context, scope, repo, item, snackbar) },
            )
        } else {
            FeedListPage(
                feeds = feeds,
                snackbar = snackbar,
                onBack = vm::closeScreen,
                onAdd = { adding = true },
                onRefreshAll = { io("refresh") { FeedScheduler.fetchNow(context); "refreshing all feeds…" } },
                onImport = { importOpml.launch(arrayOf("*/*")) },
                onExport = { exportOpml.launch("kolahal-feeds.opml") },
                onOpenFeed = { openFeedId = it.id },
                onFeedMenu = { sheetFeedId = it.id },
            )
        }

        val sheetFeed = sheetFeedId?.let { id -> feeds?.firstOrNull { it.id == id } }
        if (sheetFeed != null) {
            FeedSheet(
                feed = sheetFeed,
                onDismiss = { sheetFeedId = null },
                onRefresh = { io("refresh") { FeedScheduler.fetchNow(context, sheetFeed.id); "fetching ${sheetFeed.displayTitle()}…" } },
                onNotify = { on ->
                    if (on) onRequestNotifications()
                    io("notifications") { repo.setNotify(sheetFeed.id, on); null }
                },
                onEnabled = { on ->
                    io("update") { repo.setEnabled(sheetFeed.id, on); FeedScheduler.sync(context); null }
                },
                onInterval = { minutes -> io("update") { repo.setInterval(sheetFeed.id, minutes); null } },
                onMarkAllRead = { io("mark read") { repo.markAllRead(sheetFeed.id); "marked ${sheetFeed.displayTitle()} read" } },
                onRemove = {
                    sheetFeedId = null
                    if (openFeedId == sheetFeed.id) openFeedId = null
                    io("remove") { repo.remove(sheetFeed.id); FeedScheduler.sync(context); "removed ${sheetFeed.displayTitle()}" }
                },
            )
        }

        if (adding) AddFeedDialog(repo, onDismiss = { adding = false }) { title ->
            adding = false
            scope.launch { snackbar.showSnackbar("subscribed to $title — fetching…") }
        }
    }
}

/** Opens [item] in the browser and marks it read. */
private fun openItem(context: Context, scope: CoroutineScope, repo: FeedRepository, item: FeedItem, snackbar: SnackbarHostState) {
    val link = item.link
    if (link.isNullOrBlank()) {
        scope.launch { snackbar.showSnackbar("this item has no link") }
    } else {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("no app can open $link") }
            return
        }
    }
    if (!item.read) scope.launch(Dispatchers.IO) { repo.markRead(item.id) }
}

// ---- feed list ----------------------------------------------------------------------------------

@Composable
private fun FeedListPage(
    feeds: List<FeedInfo>?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRefreshAll: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onOpenFeed: (FeedInfo) -> Unit,
    onFeedMenu: (FeedInfo) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val unread = feeds?.filter { it.enabled }?.sumOf { it.unread } ?: 0
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Feeds") },
                subtitle = {
                    Text(
                        when {
                            feeds.isNullOrEmpty() -> "RSS · Atom"
                            else -> "${feeds.size} feed${if (feeds.size == 1) "" else "s"} · $unread unread"
                        }
                    )
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onRefreshAll, enabled = !feeds.isNullOrEmpty()) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh all")
                    }
                    IconButton(onClick = onImport) {
                        Icon(painterResource(R.drawable.ic_upload), contentDescription = "Import OPML")
                    }
                    IconButton(onClick = onExport, enabled = !feeds.isNullOrEmpty()) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = "Export OPML")
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(painterResource(R.drawable.ic_add), null) },
                text = { Text("Add feed") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                feeds == null -> Unit // first load; the list appears within a frame or two
                feeds.isEmpty() -> {
                    item(key = "empty") {
                        Text(
                            "No feeds yet. Tap “Add feed” or import an OPML file from another reader.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                    item(key = "import") {
                        ActionGroup(
                            listOf(
                                Action(R.drawable.ic_add, "Add a feed", "Paste a site or feed address (RSS or Atom)", onAdd),
                                Action(R.drawable.ic_upload, "Import OPML", "Subscriptions exported from another feed reader", onImport),
                            )
                        )
                    }
                }
                else -> {
                    item(key = "h:subs") { GroupHeader("Subscriptions") }
                    itemsIndexed(feeds, key = { _, f -> f.id }) { i, f ->
                        FeedRow(f, i, feeds.size, onClick = { onOpenFeed(f) }, onMenu = { onFeedMenu(f) })
                    }
                    item(key = "hint") {
                        Text(
                            "Tap a feed to read it. Terminal: feed add <url> · feed read · feed fetch",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedRow(f: FeedInfo, index: Int, count: Int, onClick: () -> Unit, onMenu: () -> Unit) {
    val failed = f.lastStatus?.startsWith("error") == true
    val status = buildList {
        add("every ${CommandProcessor.formatInterval(f.intervalMin)}")
        if (f.notify) add("notify")
        if (!f.enabled) add("off")
        f.lastFetch?.let { add(relative(it)) } ?: add("never fetched")
        f.lastStatus?.let { add(it) }
    }.joinToString(" · ")
    SegmentedListItem(
        onClick = onClick,
        shapes = segShapes(index, count),
        colors = segColors(),
        leadingContent = {
            Icon(
                painterResource(R.drawable.ic_rss),
                null,
                tint = if (f.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        },
        overlineContent = {
            Text(
                hostOf(f.url),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                status,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnreadBadge(f.unread, f.total)
                IconButton(onClick = onMenu) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Feed options")
                }
            }
        },
    ) {
        Text(
            f.displayTitle(),
            fontWeight = if (f.unread > 0) FontWeight.SemiBold else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "unread / total" pill; filled when anything is unread. */
@Composable
private fun UnreadBadge(unread: Int, total: Int) {
    val active = unread > 0
    Surface(
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            "$unread / $total",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ---- per-feed sheet -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FeedSheet(
    feed: FeedInfo,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onNotify: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onMarkAllRead: () -> Unit,
    onRemove: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmRemove by remember(feed.id) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
            Text(feed.displayTitle(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                feed.url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    "${feed.unread} unread of ${feed.total}",
                    feed.lastFetch?.let { "fetched ${relative(it)}" } ?: "never fetched",
                    feed.lastStatus,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (feed.lastStatus?.startsWith("error") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh) {
                    Icon(painterResource(R.drawable.ic_refresh), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Refresh now")
                }
                FilledTonalButton(onClick = onMarkAllRead, enabled = feed.unread > 0) {
                    Icon(painterResource(R.drawable.ic_done_all), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Mark all read")
                }
            }

            Spacer(Modifier.height(8.dp))
            SwitchRow("Notifications", "Post a notification for new items", feed.notify, onNotify)
            SwitchRow("Enabled", "Fetch this feed on its schedule", feed.enabled, onEnabled)

            Spacer(Modifier.height(12.dp))
            Text("Refresh every", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            IntervalGroup(feed.intervalMin, onInterval)
            if (FEED_INTERVALS.none { it.first == feed.intervalMin }) {
                Text(
                    "Currently every ${CommandProcessor.formatInterval(feed.intervalMin)} (set from the terminal)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Remove feed")
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(painterResource(R.drawable.ic_delete), null) },
            title = { Text("Remove ${feed.displayTitle()}?") },
            text = { Text("Unsubscribes and deletes its ${feed.total} stored items. Export OPML first to keep your list.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmRemove = false; onRemove() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** Full-width row (≥ 56dp) whose whole surface toggles the switch. */
@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        onClick = { onChange(!checked) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Connected button group of interval presets; narrow padding so seven fit on a phone. */
@Composable
private fun IntervalGroup(selected: Int, onPick: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FEED_INTERVALS.forEachIndexed { i, (minutes, label) ->
            ToggleButton(
                checked = minutes == selected,
                onCheckedChange = { onPick(minutes) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    FEED_INTERVALS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 2.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Clip, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ---- add dialog ---------------------------------------------------------------------------------

@Composable
private fun AddFeedDialog(repo: FeedRepository, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val normalized = remember(url) { FeedUrl.normalize(url) }
    val invalid = url.isNotBlank() && normalized == null

    fun submit() {
        if (normalized == null || busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (repo.byUrl(normalized) != null) throw IllegalArgumentException("already subscribed to $normalized")
                    val feed = repo.add(url)
                    FeedScheduler.sync(context)
                    FeedScheduler.fetchNow(context, feed.id)
                    feed.shortName
                }
            }
            busy = false
            result.onSuccess(onAdded).onFailure { error = it.message ?: "couldn't add that feed" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_rss), null) },
        title = { Text("Add feed") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("Feed URL") },
                placeholder = { Text("example.com/feed.xml") },
                singleLine = true,
                isError = invalid || error != null,
                supportingText = {
                    Text(
                        error ?: if (invalid) "Enter an http(s) address" else "RSS or Atom; https:// is added if missing"
                    )
                },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = normalized != null && !busy, onClick = ::submit) { Text("Subscribe") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- items of one feed --------------------------------------------------------------------------

@Composable
private fun FeedItemsPage(
    feed: FeedInfo,
    repo: FeedRepository,
    version: Int,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenItem: (FeedItem) -> Unit,
) {
    val items by produceState<List<FeedItem>?>(null, feed.id, version) {
        value = withContext(Dispatchers.IO) { repo.items(feedId = feed.id, limit = ITEM_LIMIT) }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(feed.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                subtitle = { Text("${feed.unread} unread · ${hostOf(feed.url)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh feed")
                    }
                    IconButton(onClick = onMarkAllRead, enabled = feed.unread > 0) {
                        Icon(painterResource(R.drawable.ic_done_all), contentDescription = "Mark all read")
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val list = items
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (list != null && list.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (feed.lastFetch == null) "Not fetched yet. Tap refresh." else "No items in this feed.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            }
            if (list != null) itemsIndexed(list, key = { _, it -> it.id }) { i, item ->
                ItemRow(item, i, list.size) { onOpenItem(item) }
            }
        }
    }
}

@Composable
private fun ItemRow(item: FeedItem, index: Int, count: Int, onClick: () -> Unit) {
    val date = item.publishedText?.takeIf { it.isNotBlank() } ?: relative(item.fetchedAt)
    SegmentedListItem(
        onClick = onClick,
        shapes = segShapes(index, count),
        colors = segColors(),
        leadingContent = {
            // Unread dot; a fixed-size slot keeps titles aligned when it's hidden.
            Box(Modifier.size(10.dp).then(
                if (!item.read) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape) else Modifier
            ))
        },
        supportingContent = {
            Text(
                date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        Text(
            item.title,
            fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
            color = if (item.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
