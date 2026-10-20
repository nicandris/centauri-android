package app.centauri.remote

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    source: Any, load: suspend () -> List<T>, key: (T) -> Any, empty: Int, pad: PaddingValues,
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
    LaunchedEffect(source, attempt) { reload() }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; reload(); refreshing = false } },
        modifier = Modifier.fillMaxSize().padding(pad),
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
fun FilesScreen(c: PrinterLink, http: OkHttpClient, st: Status?, pad: PaddingValues) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    var picked by remember { mutableStateOf<PrinterFile?>(null) }
    RefreshableList(
        source = c, load = { c.files() }, key = { it.path }, empty = R.string.no_files, pad = pad,
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
            modifier = Modifier.clickable { picked = f },
        )
    }
    picked?.let { f -> StartPrintDialog(c, http, f, fileInfos.get(c.infoKey(f.path)), st) { picked = null } }
}

@Composable
private fun StartPrintDialog(c: PrinterLink, http: OkHttpClient, f: PrinterFile, cached: FileInfo?, st: Status?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val info by produceState(cached, f.path) { if (value == null) value = orNull { c.fileInfo(f.path) } }
    var autoLevel by remember { mutableStateOf(false) }
    var timelapse by remember { mutableStateOf(false) }
    var plate by remember { mutableIntStateOf(st?.plate ?: 0) }
    var sending by remember { mutableStateOf(false) }
    val blocker = when {
        st == null -> R.string.blocked_offline
        !st.idle -> R.string.blocked_busy
        st.canvas -> R.string.blocked_canvas
        else -> null
    }
    val n = jobName(f.path)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_title)) },
        text = {
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
                        SegmentedButton(plate == i, { plate = i }, SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(stringResource(label))
                        }
                    }
                }
                CheckRow(R.string.auto_level, autoLevel) { autoLevel = it }
                CheckRow(R.string.timelapse, timelapse) { timelapse = it }
                blocker?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = blocker == null && !sending, onClick = {
                sending = true
                scope.launch {
                    val msg = try {
                        val ack = c.startPrint(f.path, autoLevel, timelapse, plate)
                        if (ack == 0) null else res.getString(R.string.print_refused, ack)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.userMessage(res)
                    }
                    Toast.makeText(ctx, msg ?: res.getString(R.string.print_started), Toast.LENGTH_LONG).show()
                    sending = false
                    if (msg == null) onDismiss()
                }
            }) { Text(stringResource(R.string.start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
fun HistoryScreen(c: PrinterLink, http: OkHttpClient, pad: PaddingValues) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    RefreshableList(source = c, load = { c.history() }, key = { it.key }, empty = R.string.no_history, pad = pad) { e ->
        val date = DateUtils.formatDateTime(
            ctx, e.begin * 1000,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
        val took = formatDuration(res, (e.end - e.begin).toInt())
        ListItem(
            headlineContent = { Text(jobName(e.name).title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    if (e.completed) stringResource(R.string.history_completed, date, took)
                    else stringResource(R.string.history_cancelled, date, e.layers, e.totalLayers),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { RemoteImage(http, e.thumbnail, Modifier.size(56.dp), 56, e.thumbnailRes) },
            trailingContent = {
                Icon(
                    painterResource(if (e.completed) R.drawable.ic_check_circle else R.drawable.ic_cancel),
                    stringResource(if (e.completed) R.string.completed else R.string.cancelled),
                    tint = if (e.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            },
        )
    }
}
