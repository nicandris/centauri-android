package app.centauri.remote

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

class PrinterViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("printer", Context.MODE_PRIVATE)
    // Protocol-level ping every 30 s, same heartbeat as the HA client.
    val http: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()

    var client by mutableStateOf(load()?.let { SdcpClient(http, it, viewModelScope) })
        private set
    var found by mutableStateOf<List<Printer>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    private fun load(): Printer? {
        val ip = prefs.getString("ip", null) ?: return null
        return Printer(prefs.getString("name", "")!!, ip, prefs.getString("id", "")!!, prefs.getString("conn", "")!!)
    }

    fun search(target: String?) = viewModelScope.launch {
        searching = true
        found = runCatching { discover(target?.trim()?.ifEmpty { null } ?: "255.255.255.255") }.getOrDefault(emptyList())
        searching = false
    }

    fun pick(p: Printer) {
        prefs.edit().putString("ip", p.ip).putString("name", p.name).putString("id", p.mainboardId)
            .putString("conn", p.connId).apply()
        client = SdcpClient(http, p, viewModelScope)
    }

    fun forget() {
        client?.stop()
        prefs.edit().clear().apply()
        client = null
        found = emptyList()
    }

    override fun onCleared() { client?.stop() }
}

@Composable
fun App(vm: PrinterViewModel = viewModel()) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        val c = vm.client
        if (c == null) SetupScreen(vm) else {
            LifecycleStartEffect(c) {
                c.start()
                onStopOrDispose { c.stop() }
            }
            PrinterScreen(c, vm.http, vm::forget)
        }
    }
}

@Composable
fun SetupScreen(vm: PrinterViewModel) {
    var ip by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Find your printer", style = MaterialTheme.typography.headlineSmall)
        Text("Searches the local network. Leave the IP empty to broadcast, or type it if broadcast is blocked.")
        OutlinedTextField(ip, { ip = it }, label = { Text("Printer IP (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.search(ip) }, enabled = !vm.searching) { Text("Search") }
        if (vm.searching) CircularProgressIndicator()
        else if (vm.found.isEmpty()) Text("No printers found yet.", style = MaterialTheme.typography.bodySmall)
        vm.found.forEach { p ->
            Card(onClick = { vm.pick(p) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(p.name, fontWeight = FontWeight.Bold)
                    Text(p.ip, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun PrinterScreen(c: SdcpClient, http: OkHttpClient, onForget: () -> Unit) {
    val connected by c.connected.collectAsState()
    val st by c.status.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.printer.name.ifEmpty { "Centauri Carbon" }, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (connected) "Connected · ${c.printer.ip}" else "Connecting to ${c.printer.ip}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onForget) { Text("Change") }
        }
        CameraCard(c, http)
        st?.let {
            JobCard(c, it)
            TempsCard(it)
            ControlsCard(c, it)
        }
    }
}

@Composable
fun CameraCard(c: SdcpClient, http: OkHttpClient) {
    var on by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Camera", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Switch(on, { on = it })
            }
            if (on) {
                // Release the printer's video slot whenever the app leaves the foreground.
                LifecycleResumeEffect(c) {
                    c.video(true)
                    onPauseOrDispose { c.video(false) }
                }
                val url by c.videoUrl.collectAsState()
                val frame by produceState<android.graphics.Bitmap?>(null, url) {
                    val u = url ?: return@produceState
                    runCatching { mjpegFrames(http, u).collect { value = it } }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(top = 8.dp), Alignment.Center) {
                    frame?.let {
                        Image(it.asImageBitmap(), "Camera", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } ?: CircularProgressIndicator()
                }
            }
        }
    }
}

private fun hm(s: Int) = if (s >= 3600) "${s / 3600}h ${s % 3600 / 60}m" else "${s / 60}m"

@Composable
fun JobCard(c: SdcpClient, st: Status) {
    val p = st.print
    val thumb by c.thumbnail.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.file.isNotEmpty()) thumb?.let {
                Image(it.asImageBitmap(), "Thumbnail", Modifier.size(72.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (p.status == 0) machineStatusLabel(st.machine) else printStatusLabel(p.status),
                    fontWeight = FontWeight.Bold,
                )
                if (p.file.isNotEmpty()) {
                    Text(p.file, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    LinearProgressIndicator(progress = { p.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${p.progress}% · layer ${p.layer}/${p.totalLayers} · ${hm(p.elapsedS)} elapsed · " +
                            "${hm(maxOf(0, p.totalS - p.elapsedS))} left",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun TempsCard(st: Status) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Stat("Nozzle", "%.0f / %.0f °C".format(st.nozzle, st.nozzleTarget), Modifier.weight(1f))
                Stat("Bed", "%.0f / %.0f °C".format(st.bed, st.bedTarget), Modifier.weight(1f))
                Stat("Chamber", "%.0f °C".format(st.chamber), Modifier.weight(1f))
            }
            Row {
                Stat("Model fan", "${st.modelFan}%", Modifier.weight(1f))
                Stat("Aux fan", "${st.auxFan}%", Modifier.weight(1f))
                Stat("Box fan", "${st.boxFan}%", Modifier.weight(1f))
            }
            Stat("Print speed", "${st.print.speedPct}%")
        }
    }
}

@Composable
fun ControlsCard(c: SdcpClient, st: Status) {
    var confirmStop by remember { mutableStateOf(false) }
    val s = st.print.status
    val paused = s == 5 || s == 6
    // Anything but idle / stopped / complete counts as a job in progress.
    val active = s !in setOf(0, 8, 9)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paused) Button(onClick = c::resume, enabled = s == 6) { Text("Resume") }
                else Button(onClick = c::pause, enabled = s == 13) { Text("Pause") }
                OutlinedButton(
                    onClick = { confirmStop = true }, enabled = active,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chamber light", Modifier.weight(1f))
                Switch(st.light, { c.setLight(it) })
            }
        }
    }
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("Stop print?") },
        text = { Text("This cancels ${st.print.file.ifEmpty { "the current job" }}. It can't be resumed.") },
        confirmButton = { TextButton(onClick = { confirmStop = false; c.stopPrint() }) { Text("Stop print") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
    )
}
