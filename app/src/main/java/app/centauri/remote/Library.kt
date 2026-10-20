package app.centauri.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.util.LruCache
import android.widget.Toast
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// Thumbnails are small once downsampled; budget the cache in bytes, not entries.
private val images = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}
private val fileInfos = LruCache<String, FileInfo>(256)

/** Decodes at the largest power-of-two downsample that still covers [targetPx], so rows draw cheap bitmaps. */
private fun decodeScaled(bytes: ByteArray, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.also { it.prepareToDraw() }
}

/** Printer-hosted thumbnail (history/file PNGs), downsampled off the main thread and cached. */
@Composable
fun RemoteImage(http: OkHttpClient, url: String?, modifier: Modifier, sizeDp: Int) {
    val targetPx = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val bmp by produceState(url?.let { images.get(it) }, url) {
        if (value == null && !url.isNullOrEmpty()) value = withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(url).build()).execute()
                    .use { decodeScaled(it.body.bytes(), targetPx) }
                    ?.also { images.put(url, it) }
            } catch (_: IOException) {
                null // no thumbnail is fine; the placeholder stays
            }
        }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** Runs [block], turning printer/network failures into null while letting cancellation through. */
private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/**
 * A pull-to-refresh list that loads when shown (and again if the printer changes) and keeps the
 * last result while refreshing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> RefreshableList(
    source: Any, load: suspend () -> List<T>, key: (T) -> Any, empty: Int, pad: PaddingValues,
    header: LazyListScope.() -> Unit = {}, row: @Composable (T) -> Unit,
) {
    var items by remember(source) { mutableStateOf<List<T>?>(null) }
    var failed by remember(source) { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val noAnswer = stringResource(R.string.printer_no_answer)
    suspend fun reload() {
        try {
            items = load(); failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: noAnswer
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
fun FilesScreen(c: SdcpClient, http: OkHttpClient, st: Status?, pad: PaddingValues) {
    val ctx = LocalContext.current
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
        // One info request per visible row; LazyColumn keeps that bounded to what's on screen.
        val info by produceState(fileInfos.get(f.path), f.path) {
            if (value == null) value = orNull { c.fileInfo(f.path) }?.also { fileInfos.put(f.path, it) }
        }
        val n = jobName(f.path)
        val date = DateUtils.formatDateTime(ctx, f.created * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        val grams = info?.let { stringResource(R.string.grams, it.estGrams) }
        ListItem(
            headlineContent = { Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(info?.let { hm(it.estS) }, grams, date).joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { RemoteImage(http, info?.thumbnail, Modifier.size(56.dp), 56) },
            modifier = Modifier.clickable { picked = f },
        )
    }
    picked?.let { f -> StartPrintDialog(c, http, f, fileInfos.get(f.path), st) { picked = null } }
}

@Composable
private fun StartPrintDialog(c: SdcpClient, http: OkHttpClient, f: PrinterFile, cached: FileInfo?, st: Status?, onDismiss: () -> Unit) {
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
                    RemoteImage(http, info?.thumbnail, Modifier.size(88.dp), 88)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(n.title, style = MaterialTheme.typography.titleMedium)
                        n.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        info?.let {
                            Text(
                                stringResource(
                                    R.string.file_meta, hm(it.estS), stringResource(R.string.grams, it.estGrams),
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
                    val ack = orNull { c.startPrint(f.path, autoLevel, timelapse, plate) } ?: -1
                    val msg = if (ack == 0) res.getString(R.string.print_started) else res.getString(R.string.print_refused, ack)
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    sending = false
                    if (ack == 0) onDismiss()
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
fun HistoryScreen(c: SdcpClient, http: OkHttpClient, pad: PaddingValues) {
    val ctx = LocalContext.current
    RefreshableList(source = c, load = { c.history() }, key = { it.taskId }, empty = R.string.no_history, pad = pad) { e ->
        val n = jobName(e.name)
        val date = DateUtils.formatDateTime(
            ctx, e.begin * 1000,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
        val took = hm((e.end - e.begin).toInt().coerceAtLeast(0))
        ListItem(
            headlineContent = { Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    if (e.completed) "$date · $took" else stringResource(R.string.history_cancelled, date, e.layers, e.totalLayers),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { RemoteImage(http, e.thumbnail, Modifier.size(56.dp), 56) },
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
