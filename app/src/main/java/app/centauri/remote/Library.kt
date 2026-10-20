package app.centauri.remote

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.util.LruCache
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

// Thumbnails are small once downsampled; budget the cache in bytes, not entries.
private val images = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}
// Keyed by printer + path so switching printers can't show another printer's details.
private val fileInfos = LruCache<String, FileInfo>(256)
// The printer answers one request at a time; a fast scroll mustn't queue dozens of them.
private val fileInfoPermits = Semaphore(2)

private fun PrinterLink.infoKey(path: String) = "${printer.ip}|$path"

/** Runs [block], turning printer/network failures into null while letting cancellation through. */
private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

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
    val bmp by produceState(url?.let { images.get(it) }, url) {
        if (value != null || url.isNullOrEmpty()) return@produceState
        // fetchBytes cancels the HTTP call when this row leaves the screen.
        value = orNull { withContext(Dispatchers.IO) { decodeScaled(http.fetchBytes(url), targetPx) } }
            ?.also { images.put(url, it) }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/**
 * A pull-to-refresh list that loads when shown (and again if the printer changes). A failed
 * refresh keeps the previous list and says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> RefreshableList(
    source: Any, load: suspend () -> List<T>, key: (T) -> Any, empty: Int, modifier: Modifier, version: Int = 0,
    header: LazyListScope.() -> Unit = {}, row: @Composable (T) -> Unit,
) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    var items by remember(source) { mutableStateOf<List<T>?>(null) }
    var failed by remember(source) { mutableStateOf<String?>(null) }
    var loading by remember(source) { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        if (loading) return // a pull while a load is in flight would just race it
        loading = true
        try {
            items = load(); failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.userMessage(res)
            if (items != null) Toast.makeText(ctx, failed, Toast.LENGTH_SHORT).show()
        } finally {
            loading = false
        }
    }
    // [version] bumps after a change (a delete) so the list reloads, keeping the old one meanwhile.
    LaunchedEffect(source, attempt, version) { reload() }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; reload(); refreshing = false } },
        modifier = modifier,
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            header()
            val list = items
            val error = failed
            when {
                list == null && error != null -> item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.load_failed, error), color = MaterialTheme.colorScheme.error)
                        FilledTonalButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
                    }
                }
                list == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                }
                list.isEmpty() -> item {
                    Text(stringResource(empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> items(list, key = key) { row(it) }
            }
        }
    }
}

@Composable
fun FilesScreen(c: PrinterLink, http: OkHttpClient, st: Status?, pad: PaddingValues, wide: Boolean) {
    var picked by remember { mutableStateOf<PrinterFile?>(null) }
    var deleting by remember { mutableStateOf<PrinterFile?>(null) }
    val change = rememberChange()
    Box(Modifier.padding(pad)) {
        ListDetail(
            wide,
            list = { m ->
                FileList(c, http, st, m, change.version, onPick = { picked = it }, onDelete = { deleting = it }, selected = picked.takeIf { wide })
            },
            detail = {
                StartPrintPane(c, http, picked, picked?.let { fileInfos.get(c.infoKey(it.path)) }, st) { picked = null }
            },
        )
    }
    if (!wide) picked?.let { f -> StartPrintDialog(c, http, f, fileInfos.get(c.infoKey(f.path)), st) { picked = null } }
    deleting?.let { f ->
        ConfirmDialog(
            title = stringResource(R.string.file_delete_title, jobName(f.path).title),
            body = stringResource(R.string.file_delete_body),
            confirm = stringResource(R.string.file_delete),
            onConfirm = { change.run { c.deleteFiles(listOf(f.path)) }; if (picked == f) picked = null },
        ) { deleting = null }
    }
}

/** The selected row (tablet list/detail) gets a tinted background. */
@Composable
private fun rowColors(selected: Boolean) = ListItemDefaults.colors(
    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
)

@Composable
private fun FileList(
    c: PrinterLink, http: OkHttpClient, st: Status?, modifier: Modifier, version: Int,
    onPick: (PrinterFile) -> Unit, onDelete: (PrinterFile) -> Unit, selected: PrinterFile?,
) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    RefreshableList(
        source = c, load = { c.files() }, key = { it.path }, empty = R.string.no_files, modifier = modifier, version = version,
        header = {
            if (st != null && !st.idle) item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Text(
                        stringResource(R.string.busy_banner, jobName(st.print.file).title),
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    ) { f ->
        val infoKey = c.infoKey(f.path)
        val info by produceState(fileInfos.get(infoKey), infoKey) {
            if (value == null) value = orNull { fileInfoPermits.withPermit { c.fileInfo(f.path) } }?.also { fileInfos.put(infoKey, it) }
        }
        val date = DateUtils.formatDateTime(ctx, f.created * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        val i = info
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
            trailingContent = {
                RowMenu(R.string.file_delete to { onDelete(f) })
            },
            colors = rowColors(f == selected),
            modifier = Modifier.clickable { onPick(f) },
        )
    }
}

/** Runs printer changes (deletes): reports failures, and bumps [version] so lists reload. */
private class Change(private val scope: CoroutineScope, private val ctx: Context, private val res: Resources) {
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
            error?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
            version++
        }
    }
}

@Composable
private fun rememberChange(): Change {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val res = LocalResources.current
    return remember(scope, ctx, res) { Change(scope, ctx, res) }
}

/** A row's ⋮ menu. */
@Composable
private fun RowMenu(vararg items: Pair<Int, () -> Unit>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options)) }
        DropdownMenu(open, { open = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem({ Text(stringResource(label)) }, { open = false; action() })
            }
        }
    }
}

/** The start-print form's state, shared by the phone dialog and the tablet detail pane. */
private class StartPrint(val file: PrinterFile, plate: Int) {
    var info by mutableStateOf<FileInfo?>(null)
    var autoLevel by mutableStateOf(false)
    var timelapse by mutableStateOf(false)
    var plate by mutableIntStateOf(plate)
    var sending by mutableStateOf(false)
}

@Composable
private fun rememberStartPrint(c: PrinterLink, f: PrinterFile, cached: FileInfo?, st: Status?): StartPrint {
    val form = remember(c, f.path) { StartPrint(f, st?.plate ?: 0).apply { info = cached } }
    LaunchedEffect(form) { if (form.info == null) form.info = orNull { c.fileInfo(f.path) } }
    return form
}

private fun blockerFor(st: Status?) = when {
    st == null -> R.string.blocked_offline
    !st.idle -> R.string.blocked_busy
    st.canvas -> R.string.blocked_canvas
    else -> null
}

/** Sends the start command; reports the outcome and calls [onStarted] when the printer took it. */
@Composable
private fun rememberStart(c: PrinterLink, form: StartPrint, onStarted: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    return {
        form.sending = true
        scope.launch {
            val msg = try {
                val ack = c.startPrint(form.file.path, form.autoLevel, form.timelapse, form.plate)
                if (ack == 0) null else res.getString(R.string.print_refused, ack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.userMessage(res)
            }
            Toast.makeText(ctx, msg ?: res.getString(R.string.print_started), Toast.LENGTH_LONG).show()
            form.sending = false
            if (msg == null) onStarted()
        }
    }
}

@Composable
private fun StartPrintFields(http: OkHttpClient, form: StartPrint, st: Status?) {
    val res = LocalResources.current
    val n = jobName(form.file.path)
    val info = form.info
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(http, info?.thumbnail, Modifier.size(88.dp), 88, info?.thumbnailRes)
            Column(Modifier.padding(start = 16.dp)) {
                Text(n.title, style = MaterialTheme.typography.titleMedium)
                n.detail(res)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
        }
        Text(stringResource(R.string.plate_side), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(R.string.plate_textured, R.string.plate_smooth).forEachIndexed { i, label ->
                SegmentedButton(form.plate == i, { form.plate = i }, SegmentedButtonDefaults.itemShape(i, 2)) {
                    Text(stringResource(label))
                }
            }
        }
        CheckRow(R.string.auto_level, form.autoLevel) { form.autoLevel = it }
        CheckRow(R.string.timelapse, form.timelapse) { form.timelapse = it }
        blockerFor(st)?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun StartPrintDialog(c: PrinterLink, http: OkHttpClient, f: PrinterFile, cached: FileInfo?, st: Status?, onDismiss: () -> Unit) {
    val form = rememberStartPrint(c, f, cached, st)
    val start = rememberStart(c, form, onDismiss)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_title)) },
        text = { StartPrintFields(http, form, st) },
        confirmButton = {
            TextButton(enabled = blockerFor(st) == null && !form.sending, onClick = start) { Text(stringResource(R.string.start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Tablet detail pane: the same form, inline beside the list. */
@Composable
private fun StartPrintPane(c: PrinterLink, http: OkHttpClient, f: PrinterFile?, cached: FileInfo?, st: Status?, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (f == null) {
            Text(stringResource(R.string.pick_a_file), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val form = rememberStartPrint(c, f, cached, st)
        val start = rememberStart(c, form, onDone)
        Text(stringResource(R.string.start_title), style = MaterialTheme.typography.headlineSmall)
        StartPrintFields(http, form, st)
        Button(onClick = start, enabled = blockerFor(st) == null && !form.sending) { Text(stringResource(R.string.start)) }
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

/** One tap target for the whole row, so TalkBack reads it as a single checkbox. */
@Composable
private fun CheckRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(stringResource(label), Modifier.padding(start = 12.dp))
    }
}

@Composable
fun HistoryScreen(c: PrinterLink, http: OkHttpClient, st: Status?, pad: PaddingValues, wide: Boolean) {
    var reprint by remember { mutableStateOf<HistoryEntry?>(null) }
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }
    val change = rememberChange()
    // The file may have been deleted since; the printer then refuses and the form says so.
    fun HistoryEntry.file() = PrinterFile(path, sizeBytes = 0, created = begin, layers = totalLayers)
    Box(Modifier.padding(pad)) {
        ListDetail(
            wide,
            list = { m ->
                HistoryList(
                    c, http, m, change.version, wide, onReprint = { reprint = it }, onDelete = { deleting = it },
                    selected = reprint.takeIf { wide },
                )
            },
            detail = {
                val e = reprint
                StartPrintPane(c, http, e?.file(), e?.let { fileInfos.get(c.infoKey(it.path)) }, st) { reprint = null }
            },
        )
    }
    if (!wide) reprint?.let { e -> StartPrintDialog(c, http, e.file(), fileInfos.get(c.infoKey(e.path)), st) { reprint = null } }
    deleting?.let { e ->
        ConfirmDialog(
            title = stringResource(R.string.history_delete_title, jobName(e.path).title),
            body = stringResource(R.string.history_delete_body),
            confirm = stringResource(R.string.history_delete),
            onConfirm = { change.run { c.deleteHistory(listOf(e.taskId)) }; if (reprint == e) reprint = null },
        ) { deleting = null }
    }
}

/** On a tablet a tap selects the entry for printing again; on a phone that's in the row menu. */
@Composable
private fun HistoryList(
    c: PrinterLink, http: OkHttpClient, modifier: Modifier, version: Int, wide: Boolean,
    onReprint: (HistoryEntry) -> Unit, onDelete: (HistoryEntry) -> Unit, selected: HistoryEntry?,
) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    RefreshableList(
        source = c, load = { c.history() }, key = { it.key }, empty = R.string.no_history, modifier = modifier, version = version,
    ) { e ->
        val date = DateUtils.formatDateTime(
            ctx, e.begin * 1000,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
        val took = formatDuration(res, (e.end - e.begin).toInt())
        ListItem(
            headlineContent = { Text(jobName(e.path).title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    if (e.completed) stringResource(R.string.history_completed, date, took)
                    else stringResource(R.string.history_cancelled, date, e.layers, e.totalLayers),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { RemoteImage(http, e.thumbnail, Modifier.size(56.dp), 56, e.thumbnailRes) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (e.completed) R.drawable.ic_check_circle else R.drawable.ic_cancel),
                        stringResource(if (e.completed) R.string.completed else R.string.cancelled),
                        tint = if (e.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    RowMenu(R.string.history_print_again to { onReprint(e) }, R.string.history_delete to { onDelete(e) })
                }
            },
            colors = rowColors(e == selected),
            modifier = if (wide) Modifier.clickable { onReprint(e) } else Modifier,
        )
    }
}
