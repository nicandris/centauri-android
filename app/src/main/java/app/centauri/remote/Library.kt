package app.centauri.remote

import android.content.res.Resources
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

// Thumbnails are small once downsampled; budget the cache in bytes, not entries. Keyed by URL
// and decoded size, so a bigger view doesn't get a small, blurry copy.
private val images = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}
// Keyed by printer + path so switching printers can't show another printer's details; each entry
// remembers the file's date + size, so a file re-sliced under the same name is looked up again.
private val fileInfos = LruCache<String, Pair<String, FileInfo>>(256)
// The printer answers one request at a time; a fast scroll mustn't queue dozens of them.
private val fileInfoPermits = Semaphore(2)

private fun PrinterLink.infoKey(path: String) = "${printer.ip}|$path"
private val PrinterFile.version get() = "$created|$sizeBytes"

/** Cached details for [path] at [version]; any version when null (History doesn't know a file's date or size). */
private fun PrinterLink.cachedInfo(path: String, version: String?) =
    fileInfos.get(infoKey(path))?.takeIf { version == null || it.first == version }?.second

/** Fresh file details may come with a new picture at the same URL. */
private fun forgetImages(url: String) = images.snapshot().keys.filter { it.startsWith("$url@") }.forEach(images::remove)

private fun Throwable.userMessage(res: Resources) =
    res.getString((this as? PrinterException)?.messageRes ?: R.string.printer_no_answer)

/**
 * Printer-hosted thumbnail (history/file PNGs), downsampled off the main thread and cached;
 * or a bundled [res] image when there is one (the demo printer).
 */
@Composable
fun RemoteImage(http: OkHttpClient, url: String?, modifier: Modifier, sizeDp: Int, @DrawableRes res: Int? = null) {
    if (res != null) {
        Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
            Image(painterResource(res), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        return
    }
    val targetPx = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val key = "$url@$targetPx"
    val bmp by produceState(url?.let { images.get(key) }, key) {
        // produceState keeps its value across keys: drop the previous URL's picture.
        value = url?.let { images.get(key) }
        if (value != null || url.isNullOrEmpty()) return@produceState
        // fetchBytes cancels the HTTP call when this row leaves the screen.
        value = orNull { withContext(Dispatchers.IO) { decodeScaled(http.fetchBytes(url), targetPx) } }
            ?.also { images.put(key, it) }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}


/**
 * A pull-to-refresh list that loads when shown (and again if [source] changes). A failed
 * refresh keeps the previous list and says so. [header] scrolls with the list and gets the whole
 * loaded list (before [arrange]); null while loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> RefreshableList(
    source: Any, load: suspend () -> List<T>, key: (T) -> Any, empty: Int, @DrawableRes emptyIcon: Int, modifier: Modifier,
    version: Int = 0, arrange: (List<T>) -> List<T> = { it }, arrangeKey: Any? = null,
    header: LazyListScope.(List<T>?) -> Unit = {}, row: @Composable (T) -> Unit,
) {
    val snack = LocalSnackbar.current
    val res = LocalResources.current
    var items by remember(source) { mutableStateOf<List<T>?>(null) }
    var failed by remember(source) { mutableStateOf<String?>(null) }
    var loading by remember(source) { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var again by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        // A reload asked for mid-load (a delete during a pull) runs once that load is done.
        if (loading) { again = true; return }
        loading = true
        try {
            do {
                again = false
                items = load(); failed = null
            } while (again)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.userMessage(res)
            if (items != null) failed?.let { snack.say(it) }
        } finally {
            loading = false
        }
    }
    // [version] bumps after a change (a delete) so the list reloads, keeping the old one meanwhile.
    LaunchedEffect(source, attempt, version) { reload() }
    // Sorted once per list or [arrangeKey] (order, search), not on every status push that recomposes this.
    val arranged = remember(items, arrangeKey) { items?.let(arrange) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; reload(); refreshing = false } },
        modifier = modifier,
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            val list = items
            val error = failed
            header(list)
            when {
                list == null && error != null -> item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.load_failed, error), color = MaterialTheme.colorScheme.error)
                        FilledTonalButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
                    }
                }
                list == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Spinner() }
                }
                list.isEmpty() -> item {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(painterResource(emptyIcon), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(empty), textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    val shown = arranged ?: list
                    if (shown.isEmpty()) item {
                        Text(stringResource(R.string.no_matches), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(shown, key = key) { row(it) }
                }
            }
        }
    }
}

/**
 * Search (job names, on this phone) and the sort menu shared by Files and History. A list item,
 * so it scrolls away with the list instead of holding space on small phones.
 */
private fun LazyListScope.listTools(query: String, onQuery: (String) -> Unit) = item(key = "tools") {
    val order by Hub.order.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp, 4.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            query, onQuery, Modifier.weight(1f), singleLine = true, shape = CircleShape,
            placeholder = { Text(stringResource(R.string.search)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.search_clear))
                }
            },
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.sort)) }
            DropdownMenu(menu, { menu = false }) {
                listOf(SortBy.NEWEST to R.string.sort_newest, SortBy.OLDEST to R.string.sort_oldest, SortBy.NAME to R.string.sort_name)
                    .forEach { (by, label) ->
                        DropdownMenuItem(
                            { Text(stringResource(label)) }, { Hub.setOrder(order.copy(by = by)); menu = false },
                            leadingIcon = { RadioButton(order.by == by, onClick = null) },
                        )
                    }
                HorizontalDivider()
                // By name ignores dates, so where the undated ones go doesn't apply there.
                DropdownMenuItem(
                    { Text(stringResource(R.string.sort_unknown_first)) },
                    { Hub.setOrder(order.copy(unknownFirst = !order.unknownFirst)) },
                    leadingIcon = { Checkbox(order.unknownFirst, onCheckedChange = null) },
                    enabled = order.by != SortBy.NAME,
                )
            }
        }
    }
}

@Composable
fun FilesScreen(
    c: PrinterLink, http: OkHttpClient, st: Status?, pad: PaddingValues, wide: Boolean, labs: Set<Lab>, onStarted: () -> Unit,
) {
    var picked by remember { mutableStateOf<PrinterFile?>(null) }
    var deleting by remember { mutableStateOf<List<String>>(emptyList()) }
    // Paths ticked for a bulk delete (long press); a tap then ticks instead of opening.
    var selection by remember(c) { mutableStateOf<Set<String>>(emptySet()) }
    var usb by rememberSaveable { mutableStateOf(false) }
    val storage = if (usb && Lab.USB_STORAGE in labs) STORAGE_USB else STORAGE_LOCAL
    val change = rememberChange()
    BackHandler(selection.isNotEmpty()) { selection = emptySet() }
    Box(Modifier.padding(pad)) {
        ListDetail(
            wide,
            list = { m ->
                FileList(
                    c, http, st, m, change.version, wide, storage, labs, onStorage = { usb = it == STORAGE_USB; selection = emptySet() },
                    selection, onSelection = { selection = it }, onPick = { picked = it },
                    onDeleteSelected = { deleting = selection.toList() }, selected = picked.takeIf { wide },
                )
            },
            detail = {
                StartPrintPane(
                    c, http, picked, picked?.let { c.cachedInfo(it.path, it.version) }, st, labs, onStarted,
                    onDelete = { f -> deleting = listOf(f.path) },
                ) { picked = null }
            },
        )
    }
    if (!wide) picked?.let { f ->
        StartPrintSheet(c, http, f, c.cachedInfo(f.path, f.version), st, labs, onStarted, onDelete = { deleting = listOf(f.path) }) {
            picked = null
        }
    }
    if (deleting.isNotEmpty()) {
        val paths = deleting
        ConfirmDialog(
            title = if (paths.size == 1) stringResource(R.string.file_delete_title, jobName(paths[0]).title)
            else pluralStringResource(R.plurals.files_delete_title, paths.size, paths.size),
            body = stringResource(R.string.file_delete_body),
            confirm = stringResource(R.string.file_delete),
            onConfirm = {
                change.run { c.deleteFiles(paths) }
                if (picked?.path in paths) picked = null
                selection = emptySet()
            },
        ) { deleting = emptyList() }
    }
}

/**
 * A row tap that TalkBack announces by what it does ("double tap to start"), with an optional
 * long press ([longLabel]). In list/detail ([selectable]) the row is also read as selected or not.
 */
@Composable
private fun Modifier.pickable(
    selected: Boolean, selectable: Boolean, label: Int, longLabel: Int? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
): Modifier {
    val action = stringResource(label)
    val longAction = longLabel?.let { stringResource(it) }
    return combinedClickable(onClickLabel = action, onLongClickLabel = longAction, onLongClick = onLongClick, onClick = onClick)
        .then(if (selectable) Modifier.semantics { this.selected = selected } else Modifier)
}

/** The selected row (tablet list/detail) gets a tinted background. */
@Composable
private fun rowColors(selected: Boolean) = ListItemDefaults.colors(
    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
)

@Composable
private fun FileList(
    c: PrinterLink, http: OkHttpClient, st: Status?, modifier: Modifier, version: Int, wide: Boolean,
    storage: String, labs: Set<Lab>, onStorage: (String) -> Unit,
    selection: Set<String>, onSelection: (Set<String>) -> Unit,
    onPick: (PrinterFile) -> Unit, onDeleteSelected: () -> Unit, selected: PrinterFile?,
) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    var query by rememberSaveable { mutableStateOf("") }
    val order by Hub.order.collectAsStateWithLifecycle()
    val attributes by c.attributes.collectAsStateWithLifecycle()
    val selecting = selection.isNotEmpty()
    fun toggle(path: String) = onSelection(if (path in selection) selection - path else selection + path)
    Column(modifier) {
        if (selecting) Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelection(emptySet()) }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.selection_clear))
            }
            Text(
                pluralStringResource(R.plurals.files_selected, selection.size, selection.size),
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onDeleteSelected) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.file_delete)) }
        }
        RefreshableList(
            source = c to storage, load = { c.files(storage) }, key = { it.path },
            empty = if (storage == STORAGE_USB) R.string.no_usb_files else R.string.no_files, emptyIcon = R.drawable.ic_folder,
            modifier = Modifier.weight(1f), version = version,
            arrange = { arrange(it, order, query, PrinterFile::created, PrinterFile::path) }, arrangeKey = order to query,
            header = {
                listTools(query) { query = it }
                if (Lab.USB_STORAGE in labs) item {
                    // A drive that isn't plugged in can't be picked, unless it was picked before it went away.
                    val usbOk = usbConnected(attributes) || storage == STORAGE_USB
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
                        listOf(STORAGE_LOCAL to R.string.storage_printer, STORAGE_USB to R.string.storage_usb).forEachIndexed { i, (root, label) ->
                            SegmentedButton(
                                storage == root, { onStorage(root) }, SegmentedButtonDefaults.itemShape(i, 2),
                                enabled = root == STORAGE_LOCAL || usbOk,
                            ) { Text(stringResource(label)) }
                        }
                    }
                }
                if (storage == STORAGE_LOCAL) freeSpace(ctx, attributes)?.let { free ->
                    item {
                        Text(
                            free, Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (st != null && !st.idle) item {
                    Card(
                        Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Text(
                            // The printer can be busy (starting, levelling…) before it names the job.
                            if (st.print.file.isEmpty()) stringResource(R.string.blocked_busy)
                            else stringResource(R.string.busy_banner, jobName(st.print.file).title),
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
        ) { f ->
            val info by produceState(c.cachedInfo(f.path, f.version), c, f.path, f.version) {
                if (value == null) value = orNull { fileInfoPermits.withPermit { c.fileInfo(f.path) } }
                    ?.also { fileInfos.put(c.infoKey(f.path), f.version to it); forgetImages(it.thumbnail) }
            }
            val date = if (clockUnset(f.created)) stringResource(R.string.date_unknown)
            else DateUtils.formatDateTime(ctx, f.created * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
            val i = info
            val ticked = f.path in selection
            ListItem(
                headlineContent = { Text(jobName(f.path).title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        if (i == null) date
                        else stringResource(R.string.file_row, formatDuration(res, i.estS), stringResource(R.string.grams, i.estGrams), date),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { RemoteImage(http, i?.thumbnail, Modifier.size(56.dp), 56, i?.thumbnailRes) },
                trailingContent = { if (selecting) Checkbox(ticked, onCheckedChange = null) },
                colors = rowColors(f == selected || ticked),
                modifier = if (selecting) Modifier.toggleable(ticked, role = Role.Checkbox) { toggle(f.path) }
                else Modifier.pickable(f == selected, wide, R.string.start, R.string.file_select, { toggle(f.path) }) { onPick(f) },
            )
        }
    }
}

/**
 * Runs printer changes (deletes): reports failures, and bumps [version] so lists reload. On the
 * process-wide scope, so leaving the screen mid-request doesn't swallow the outcome.
 */
private class Change(private val scope: CoroutineScope, private val snack: SnackbarHostState, private val res: Resources) {
    var version by mutableIntStateOf(0)
        private set

    fun run(action: suspend () -> Boolean) {
        scope.launch {
            val error = try {
                if (action()) null else res.getString(R.string.change_refused)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.userMessage(res)
            }
            error?.let { snack.say(it) }
            version++
        }
    }
}

@Composable
private fun rememberChange(): Change {
    val snack = LocalSnackbar.current
    val res = LocalResources.current
    return remember(snack, res) { Change(Hub.scope, snack, res) }
}

/** The start-print form's state, shared by the phone sheet and the tablet detail pane. */
private class StartPrint(val file: PrinterFile, plate: Int, val remembered: StartChoices?) {
    var info by mutableStateOf<FileInfo?>(null)
    var autoLevel by mutableStateOf(remembered?.autoLevel ?: false)
    var timelapse by mutableStateOf(remembered?.timelapse ?: false)
    var plate by mutableIntStateOf(plate)
    /** The Canvas tray to feed from; null lets the printer choose. */
    var tray by mutableStateOf<Tray?>(null)
    /** The user picked a tray, so the filament match no longer picks one for them. */
    var trayTouched by mutableStateOf(false)
    /** The options are open; with remembered choices they start folded into a summary line. */
    var editing by mutableStateOf(remembered == null)
    var sending by mutableStateOf(false)
}

@Composable
private fun rememberStartPrint(c: PrinterLink, f: PrinterFile, cached: FileInfo?, st: Status?): StartPrint {
    val form = remember(c, f.path) {
        val last = Hub.startChoices(c.printer.mainboardId)
        StartPrint(f, last?.plate ?: st?.plate ?: 0, last).apply { info = cached }
    }
    LaunchedEffect(form) { if (form.info == null) form.info = orNull { c.fileInfo(f.path) } }
    return form
}

private fun blockerFor(st: Status?) = when {
    st == null -> R.string.blocked_offline
    !st.idle -> R.string.blocked_busy
    else -> null
}

/**
 * Sends the start command; reports the outcome, remembers the choices and calls [onStarted] when
 * the printer took it.
 */
@Composable
private fun rememberStart(c: PrinterLink, form: StartPrint, onStarted: () -> Unit): () -> Unit {
    // The process-wide scope: the printer acts on a start even if the pane is left, so the outcome is still reported.
    val snack = LocalSnackbar.current
    val res = LocalResources.current
    val haptic = LocalHapticFeedback.current
    return {
        form.sending = true
        Hub.scope.launch {
            val msg = try {
                val ack = c.startPrint(form.file.path, form.autoLevel, form.timelapse, form.plate, form.tray)
                if (ack == 0) null else res.getString(R.string.print_refused, ack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.userMessage(res)
            }
            haptic.performHapticFeedback(if (msg == null) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
            snack.say(msg ?: res.getString(R.string.print_started))
            form.sending = false
            if (msg == null) {
                Hub.rememberStart(c.printer.mainboardId, StartChoices(form.plate, form.autoLevel, form.timelapse))
                onStarted()
            }
        }
    }
}

/** Thumbnail, name, slicer profile and the file's estimates. */
@Composable
private fun FileHeader(http: OkHttpClient, form: StartPrint, onDelete: (() -> Unit)?) {
    val res = LocalResources.current
    val n = jobName(form.file.path)
    val info = form.info
    Row(verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(http, info?.thumbnail, Modifier.size(88.dp), 88, info?.thumbnailRes)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(n.title, style = MaterialTheme.typography.titleMedium)
            // The estimates below already give the time.
            n.detail(res, withTime = info == null)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            info?.let {
                Text(
                    stringResource(
                        R.string.file_meta, formatDuration(res, it.estS), stringResource(R.string.grams, it.estGrams),
                        pluralStringResource(R.plurals.layers, it.layers, it.layers),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        onDelete?.let {
            IconButton(onClick = it, enabled = !form.sending) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.file_delete))
            }
        }
    }
}

/** The Canvas trays the form may feed from: none while the Lab is off or there's no Canvas. */
@Composable
private fun loadedTrays(c: PrinterLink, enabled: Boolean): List<Tray> {
    val canvas by c.canvas.collectAsStateWithLifecycle()
    return if (enabled) canvas?.trays.orEmpty().filter { it.loaded } else emptyList()
}

/**
 * The form, scrolling in [scrollArea], above a Start button that stays in view. While the printer
 * is busy it is read-only details (with Delete when [onDelete] is given).
 */
@Composable
private fun StartPrintBody(
    c: PrinterLink, http: OkHttpClient, form: StartPrint, st: Status?, labs: Set<Lab>, start: () -> Unit,
    onDelete: (() -> Unit)?, scrollArea: Modifier,
) {
    val busy = st != null && !st.idle
    val loaded = loadedTrays(c, Lab.CANVAS_TRAY in labs)
    val filament = jobName(form.file.path).filament
    Column(
        scrollArea.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!busy) Text(stringResource(R.string.start_title), style = MaterialTheme.typography.headlineSmall)
        FileHeader(http, form, onDelete)
        if (busy) Text(stringResource(R.string.blocked_busy), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else StartOptions(form, loaded, filament, st)
    }
    if (!busy) Button(
        onClick = start, enabled = blockerFor(st) == null && !form.sending,
        modifier = Modifier.fillMaxWidth().padding(24.dp, 16.dp).heightIn(min = 48.dp),
    ) {
        Text(stringResource(if (trayMismatch(filament, loaded)) R.string.start_anyway else R.string.start))
    }
}

@Composable
private fun StartOptions(form: StartPrint, loaded: List<Tray>, filament: String?, st: Status?) {
    val res = LocalResources.current
    if (form.editing) {
        Text(stringResource(R.string.plate_side), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(R.string.plate_textured, R.string.plate_smooth).forEachIndexed { i, label ->
                SegmentedButton(form.plate == i, { form.plate = i }, SegmentedButtonDefaults.itemShape(i, 2)) {
                    Text(stringResource(label))
                }
            }
        }
    } else Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            StartChoices(form.plate, form.autoLevel, form.timelapse).summary(res), Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { form.editing = true }) { Text(stringResource(R.string.start_options_edit)) }
    }
    TrayPicker(form, loaded, filament)
    if (form.editing) {
        CheckRow(R.string.auto_level, form.autoLevel) { form.autoLevel = it }
        CheckRow(R.string.timelapse, form.timelapse) { form.timelapse = it }
    }
    blockerFor(st)?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartPrintSheet(
    c: PrinterLink, http: OkHttpClient, f: PrinterFile, cached: FileInfo?, st: Status?, labs: Set<Lab>,
    onStarted: () -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit,
) {
    val form = rememberStartPrint(c, f, cached, st)
    val start = rememberStart(c, form) { onDismiss(); onStarted() }
    // Stays up while sending: leaving would cancel the request and its outcome would never show.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !form.sending })
    ModalBottomSheet(onDismissRequest = { if (!form.sending) onDismiss() }, sheetState = sheet) {
        StartPrintBody(c, http, form, st, labs, start, onDelete, Modifier.weight(1f, fill = false))
    }
}

/** The start form for a job printed before ("Print again"); if the file is gone, the printer says so. */
@Composable
internal fun ReprintDialog(
    c: PrinterLink, http: OkHttpClient, path: String, st: Status?, labs: Set<Lab>, onStarted: () -> Unit = {}, onDismiss: () -> Unit,
) = StartPrintSheet(c, http, PrinterFile(path, sizeBytes = 0, created = 0), c.cachedInfo(path, null), st, labs, onStarted, null, onDismiss)

/** Tablet detail pane: the same form, inline beside the list. */
@Composable
private fun StartPrintPane(
    c: PrinterLink, http: OkHttpClient, f: PrinterFile?, cached: FileInfo?, st: Status?, labs: Set<Lab>,
    onStarted: () -> Unit, onDelete: (PrinterFile) -> Unit, onDone: () -> Unit,
) {
    if (f == null) {
        Text(stringResource(R.string.pick_a_file), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        val form = rememberStartPrint(c, f, cached, st)
        val start = rememberStart(c, form) { onDone(); onStarted() }
        StartPrintBody(c, http, form, st, labs, start, { onDelete(f) }, Modifier.weight(1f))
    }
}

/** Phones show the list alone; wide windows put [detail] beside it. */
@Composable
private fun ListDetail(wide: Boolean, list: @Composable (Modifier) -> Unit, detail: @Composable () -> Unit) {
    if (!wide) return list(Modifier.fillMaxSize())
    Row(Modifier.fillMaxSize()) {
        list(Modifier.weight(1f).fillMaxHeight())
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
    }
}

/**
 * Which Canvas tray feeds the print: Automatic (no slot_map) or one of the [loaded] trays. Hidden
 * without a Canvas. Until the user picks, the tray holding the file's [filament] is picked; a picked
 * tray that empties meanwhile falls back to Automatic.
 */
@Composable
private fun TrayPicker(form: StartPrint, loaded: List<Tray>, filament: String?) {
    // Before the early return, so a Canvas that goes away also clears the choice. Matched by slot:
    // a fresh read of the same tray keeps it picked; an emptied or unplugged one drops to Automatic.
    LaunchedEffect(loaded) {
        form.tray = if (form.trayTouched) form.tray?.let { t -> loaded.firstOrNull(t::sameSlot) } else matchingTray(filament, loaded)
    }
    if (loaded.isEmpty()) return
    Text(stringResource(R.string.filament_source), style = MaterialTheme.typography.labelLarge)
    if (filament != null && trayMismatch(filament, loaded)) Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Text(stringResource(R.string.tray_mismatch, filament), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
    Column {
        RadioRow(form.tray == null, { form.tray = null; form.trayTouched = true }) { Text(stringResource(R.string.tray_auto)) }
        loaded.forEach { t ->
            val desc = trayDescription(t, false)
            RadioRow(t.sameSlot(form.tray), { form.tray = t; form.trayTouched = true }) {
                TraySwatch(t, false, 20)
                Text(desc, Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Text(stringResource(R.string.tray_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One tap target per option, read by TalkBack as a radio button. */
@Composable
private fun RadioRow(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        content()
    }
}

/** One tap target for the whole row, so TalkBack reads it as a single checkbox. */
@Composable
private fun CheckRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(stringResource(label), Modifier.padding(start = 12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    c: PrinterLink, http: OkHttpClient, st: Status?, pad: PaddingValues, wide: Boolean, labs: Set<Lab>, onStarted: () -> Unit,
) {
    var opened by remember { mutableStateOf<HistoryEntry?>(null) }
    var reprint by remember { mutableStateOf<HistoryEntry?>(null) }
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }
    val change = rememberChange()
    val timelapses = Lab.TIMELAPSES in labs
    @Composable
    fun Details(e: HistoryEntry) = HistoryDetails(
        e, http, timelapses,
        onPrintAgain = { if (!wide) opened = null; reprint = e },
        onDelete = { deleting = e },
    )
    Box(Modifier.padding(pad)) {
        ListDetail(
            wide,
            list = { m -> HistoryList(c, http, m, change.version, wide, timelapses, onOpen = { opened = it }, selected = opened.takeIf { wide }) },
            detail = {
                opened?.let { Details(it) }
                    ?: Text(stringResource(R.string.history_pick), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
    }
    if (!wide) opened?.let { e -> ModalBottomSheet(onDismissRequest = { opened = null }) { Details(e) } }
    // The file may have been deleted since; the printer then refuses and the form says so.
    reprint?.let { e -> ReprintDialog(c, http, e.path, st, labs, onStarted) { reprint = null } }
    deleting?.let { e ->
        ConfirmDialog(
            title = stringResource(R.string.history_delete_title, jobName(e.path).title),
            body = stringResource(R.string.history_delete_body),
            confirm = stringResource(R.string.history_delete),
            onConfirm = { change.run { c.deleteHistory(listOf(e.taskId)) }; if (opened == e) opened = null },
        ) { deleting = null }
    }
}

/** Icon and spoken label of how a job ended. */
@Composable
private fun OutcomeIcon(outcome: Outcome, described: Boolean = true) {
    val (icon, label, tint) = when (outcome) {
        Outcome.DONE -> Triple(R.drawable.ic_check_circle, R.string.completed, MaterialTheme.colorScheme.primary)
        Outcome.STOPPED -> Triple(R.drawable.ic_stop_circle, R.string.outcome_stopped, MaterialTheme.colorScheme.onSurfaceVariant)
        Outcome.FAILED -> Triple(R.drawable.ic_error, R.string.outcome_failed, MaterialTheme.colorScheme.error)
    }
    Icon(painterResource(icon), if (described) stringResource(label) else null, tint = tint)
}

@Composable
private fun formatWhen(epochS: Long): String =
    if (clockUnset(epochS)) stringResource(R.string.date_unknown)
    else DateUtils.formatDateTime(
        LocalContext.current, epochS * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

/** One entry in full: picture, times, layers, how it ended and why, and what can be done with it. */
@Composable
private fun HistoryDetails(
    e: HistoryEntry, http: OkHttpClient, timelapses: Boolean, onPrintAgain: () -> Unit, onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val snack = LocalSnackbar.current
    val n = jobName(e.path)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteImage(http, e.thumbnail, Modifier.size(160.dp).align(Alignment.CenterHorizontally), 160, e.thumbnailRes)
        Text(n.title, style = MaterialTheme.typography.headlineSmall)
        n.detail(res)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            // The text beside it says the same: TalkBack shouldn't read it twice.
            OutcomeIcon(e.outcome, described = false)
            Text(
                stringResource(
                    when (e.outcome) {
                        Outcome.DONE -> R.string.completed
                        Outcome.STOPPED -> R.string.outcome_stopped
                        Outcome.FAILED -> R.string.outcome_failed
                    },
                ),
                Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium,
            )
        }
        if (e.outcome == Outcome.FAILED) errorReasonLabel(res, e.errorReason)?.let { reason ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Text(reason, Modifier.padding(12.dp, 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        DetailLine(R.string.history_started, formatWhen(e.begin))
        DetailLine(R.string.history_ended, formatWhen(e.end))
        DetailLine(R.string.history_duration, formatDuration(res, (e.end - e.begin).toInt()))
        DetailLine(R.string.history_layers, stringResource(R.string.history_layers_of, e.layers, e.totalLayers))
        Button(onClick = onPrintAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.history_print_again)) }
        if (timelapses && e.timelapseUrl.isNotEmpty()) FilledTonalButton(
            onClick = { playVideo(ctx, e.timelapseUrl)?.let { snack.say(res.getString(it)) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(painterResource(R.drawable.ic_play), null)
            Text(stringResource(R.string.timelapse_play), Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.history_delete)) }
    }
}

/** "Started   12 Oct, 14:05", read by TalkBack as one line. */
@Composable
private fun DetailLine(label: Int, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(stringResource(label), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

/** A tap opens the entry's details: a sheet on phones, the pane beside the list on tablets. */
@Composable
private fun HistoryList(
    c: PrinterLink, http: OkHttpClient, modifier: Modifier, version: Int, wide: Boolean, timelapses: Boolean,
    onOpen: (HistoryEntry) -> Unit, selected: HistoryEntry?,
) {
    val res = LocalResources.current
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    // Switching the Lab off leaves no Timelapses chip to switch back with.
    val filter = if (picked == HistoryFilter.TIMELAPSES && !timelapses) HistoryFilter.ALL else picked
    val order by Hub.order.collectAsStateWithLifecycle()
    Column(modifier) {
        RefreshableList(
            source = c, load = { c.history() }, key = { it.key }, empty = R.string.no_history, emptyIcon = R.drawable.ic_history,
            modifier = Modifier.weight(1f), version = version,
            arrange = { list -> arrange(list.filter { it.shownUnder(filter) }, order, query, HistoryEntry::begin, HistoryEntry::path) },
            arrangeKey = Triple(order, query, filter),
            header = { all ->
                listTools(query) { query = it }
                if (!all.isNullOrEmpty()) item(key = "totals") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), Arrangement.spacedBy(8.dp)) {
                        listOfNotNull(
                            HistoryFilter.ALL to R.string.history_filter_all, HistoryFilter.COMPLETED to R.string.completed,
                            HistoryFilter.FAILED to R.string.outcome_failed,
                            (HistoryFilter.TIMELAPSES to R.string.history_filter_timelapses).takeIf { timelapses },
                        ).forEach { (f, label) ->
                            FilterChip(filter == f, { picked = f }, { Text(stringResource(label)) })
                        }
                    }
                    // Grams only from details already fetched (Files caches them): no request per entry.
                    val totals = remember(all) { historyTotals(all) { e -> c.cachedInfo(e.path, null)?.estGrams } }
                    Column(Modifier.padding(16.dp, 4.dp)) {
                        totals.lines(res).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
        ) { e ->
            val date = formatWhen(e.begin)
            val took = formatDuration(res, (e.end - e.begin).toInt())
            ListItem(
                headlineContent = { Text(jobName(e.path).title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(historyLine(res, e, date, took), maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { RemoteImage(http, e.thumbnail, Modifier.size(56.dp), 56, e.thumbnailRes) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (timelapses && e.timelapseUrl.isNotEmpty()) {
                            Icon(painterResource(R.drawable.ic_play), stringResource(R.string.timelapse_badge), tint = MaterialTheme.colorScheme.primary)
                        }
                        OutcomeIcon(e.outcome)
                    }
                },
                colors = rowColors(e == selected),
                modifier = Modifier.pickable(e == selected, wide, R.string.history_details) { onOpen(e) },
            )
        }
    }
}
