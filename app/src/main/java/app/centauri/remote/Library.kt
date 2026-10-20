package app.centauri.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val images = LruCache<String, Bitmap>(128)
private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

fun formatDate(epochS: Long) = if (epochS > 0) dateFmt.format(Date(epochS * 1000)) else "—"

/** Printer-hosted thumbnail (history/file PNGs), cached for the app's lifetime. */
@Composable
fun RemoteImage(http: OkHttpClient, url: String, modifier: Modifier) {
    val bmp by produceState(images.get(url), url) {
        if (value == null && url.isNotEmpty()) value = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute()
                    .use { BitmapFactory.decodeStream(it.body.byteStream()) }
            }.getOrNull()?.also { images.put(url, it) }
        }
    }
    Box(modifier, Alignment.Center) { bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth()) } }
}

/** Loads a list once per visit, with a retry button when the printer doesn't answer. */
@Composable
private fun <T> Loaded(load: suspend () -> List<T>, empty: String, content: @Composable (List<T>, reload: () -> Unit) -> Unit) {
    var nonce by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<T>>?>(null, nonce) { value = null; value = runCatching { load() } }
    val r = result
    when {
        r == null -> CircularProgressIndicator()
        r.isFailure -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Couldn't load: ${r.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { nonce++ }) { Text("Retry") }
        }
        r.getOrThrow().isEmpty() -> Text(empty)
        else -> content(r.getOrThrow()) { nonce++ }
    }
}

@Composable
fun FilesScreen(c: SdcpClient, http: OkHttpClient, st: Status?) {
    var picked by remember { mutableStateOf<PrinterFile?>(null) }
    Loaded({ c.files() }, "No files on the printer.") { files, reload ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${files.size} files", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = reload) { Text("Refresh") }
        }
        files.forEach { f ->
            Card(onClick = { picked = f }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(f.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatDate(f.created)} · ${"%.1f".format(f.sizeBytes / 1e6)} MB · ${f.layers} layers",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    picked?.let { StartPrintDialog(c, http, it, st) { picked = null } }
}

@Composable
private fun StartPrintDialog(c: SdcpClient, http: OkHttpClient, f: PrinterFile, st: Status?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by produceState<FileInfo?>(null, f.path) { value = runCatching { c.fileInfo(f.path) }.getOrNull() }
    var autoLevel by remember { mutableStateOf(false) }
    var timelapse by remember { mutableStateOf(false) }
    var plate by remember { mutableIntStateOf(st?.plate ?: 0) }
    var sending by remember { mutableStateOf(false) }
    val blocker = when {
        st == null -> "Not connected."
        !st.idle -> "The printer is busy."
        st.canvas -> "Canvas filament mapping isn't supported yet; start this one from the printer."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Print this file?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(f.name, fontWeight = FontWeight.Bold)
                info?.let { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemoteImage(http, i.thumbnail, Modifier.size(96.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Time: ${hm(i.estS)}")
                            Text("Filament: ${"%.0f".format(i.estGrams)} g")
                            Text("Layers: ${i.layers}")
                        }
                    }
                }
                Text("Build plate", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Textured", "Smooth").forEachIndexed { i, label ->
                        SegmentedButton(plate == i, { plate = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autoLevel, { autoLevel = it }); Text("Auto bed leveling")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(timelapse, { timelapse = it }); Text("Timelapse")
                }
                blocker?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = blocker == null && !sending, onClick = {
                sending = true
                scope.launch {
                    val ack = runCatching { c.startPrint(f.path, autoLevel, timelapse, plate) }.getOrDefault(-1)
                    Toast.makeText(ctx, if (ack == 0) "Print started" else "Printer refused (code $ack)", Toast.LENGTH_LONG).show()
                    sending = false
                    if (ack == 0) onDismiss()
                }
            }) { Text("Print") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun HistoryScreen(c: SdcpClient, http: OkHttpClient) {
    Loaded({ c.history() }, "No print history yet.") { entries, reload ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${entries.size} prints", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = reload) { Text("Refresh") }
        }
        entries.forEach { e ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(http, e.thumbnail, Modifier.size(64.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(e.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatDate(e.begin)} · ${hm((e.end - e.begin).toInt().coerceAtLeast(0))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (e.completed) "Completed" else "Cancelled at layer ${e.layers}/${e.totalLayers}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
