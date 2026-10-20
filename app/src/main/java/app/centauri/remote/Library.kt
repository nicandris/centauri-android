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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute()
                    .use { decodeScaled(it.body.bytes(), targetPx) }
            }.getOrNull()?.also { images.put(url, it) }
        }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** A pull-to-refresh list that loads on first show and keeps the last result while refreshing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> RefreshableList(
    load: suspend () -> List<T>, key: (T) -> Any, empty: String, pad: PaddingValues,
    header: LazyListScope.() -> Unit = {}, row: @Composable (T) -> Unit,
) {
    var items by remember { mutableStateOf<List<T>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        runCatching { load() }
            .onSuccess { items = it; error = null }
            .onFailure { error = it.message ?: "The printer didn't answer" }
    }
    LaunchedEffect(attempt) { reload() }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; reload(); refreshing = false } },
        modifier = Modifier.fillMaxSize().padding(pad),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            header()
            val list = items
            when {
                list == null && error != null -> item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Couldn't load: $error", color = MaterialTheme.colorScheme.error)
                        FilledTonalButton(onClick = { attempt++ }) { Text("Retry") }
                    }
                }
                list == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                }
                list.isEmpty() -> item {
                    Text(empty, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        load = { c.files() }, key = { it.path }, empty = "No files on the printer.", pad = pad,
        header = {
            if (st != null && !st.idle) item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Text(
                        "Printer is busy with ${jobName(st.print.file).title}. Files can be started once it's done.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    ) { f ->
        // One info request per visible row; LazyColumn keeps that bounded to what's on screen.
        val info by produceState(fileInfos.get(f.path), f.path) {
            if (value == null) value = runCatching { c.fileInfo(f.path) }.getOrNull()?.also { fileInfos.put(f.path, it) }
        }
        val n = jobName(f.path)
        val date = DateUtils.formatDateTime(ctx, f.created * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        ListItem(
            headlineContent = { Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(info?.let { hm(it.estS) }, info?.let { "%.0f g".format(it.estGrams) }, date).joinToString(" · "),
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
    val scope = rememberCoroutineScope()
    val info by produceState(cached, f.path) { if (value == null) value = runCatching { c.fileInfo(f.path) }.getOrNull() }
    var autoLevel by remember { mutableStateOf(false) }
    var timelapse by remember { mutableStateOf(false) }
    var plate by remember { mutableIntStateOf(st?.plate ?: 0) }
    var sending by remember { mutableStateOf(false) }
    val blocker = when {
        st == null -> "Not connected to the printer."
        !st.idle -> "The printer is busy."
        st.canvas -> "Canvas filament mapping isn't supported yet. Start this one from the printer."
        else -> null
    }
    val n = jobName(f.path)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start print?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(http, info?.thumbnail, Modifier.size(88.dp), 88)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(n.title, style = MaterialTheme.typography.titleMedium)
                        n.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        info?.let { Text("${hm(it.estS)} · ${"%.0f".format(it.estGrams)} g · ${it.layers} layers", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text("Build plate side", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Textured", "Smooth").forEachIndexed { i, label ->
                        SegmentedButton(plate == i, { plate = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { autoLevel = !autoLevel }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autoLevel, { autoLevel = it }); Text("Auto bed leveling")
                }
                Row(Modifier.fillMaxWidth().clickable { timelapse = !timelapse }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(timelapse, { timelapse = it }); Text("Record timelapse")
                }
                blocker?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = blocker == null && !sending, onClick = {
                sending = true
                scope.launch {
                    val ack = runCatching { c.startPrint(f.path, autoLevel, timelapse, plate) }.getOrDefault(-1)
                    Toast.makeText(ctx, if (ack == 0) "Print started" else "Printer refused to start (code $ack)", Toast.LENGTH_LONG).show()
                    sending = false
                    if (ack == 0) onDismiss()
                }
            }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun HistoryScreen(c: SdcpClient, http: OkHttpClient, pad: PaddingValues) {
    val ctx = LocalContext.current
    RefreshableList(load = { c.history() }, key = { it.taskId }, empty = "No prints yet.", pad = pad) { e ->
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
                    if (e.completed) "$date · $took" else "$date · stopped at layer ${e.layers} of ${e.totalLayers}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { RemoteImage(http, e.thumbnail, Modifier.size(56.dp), 56) },
            trailingContent = {
                Icon(
                    painterResource(if (e.completed) R.drawable.ic_check_circle else R.drawable.ic_cancel),
                    if (e.completed) "Completed" else "Cancelled",
                    tint = if (e.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            },
        )
    }
}
