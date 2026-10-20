package app.centauri.remote

import android.app.Application
import android.content.Context
import android.os.Build
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
import androidx.compose.foundation.clickable
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            // Follow the phone: light/dark from the system, Material You colours on Android 12+.
            val scheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
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
    val c = vm.client
    if (c == null) Box(Modifier.fillMaxSize().safeDrawingPadding()) { SetupScreen(vm) }
    else {
        LifecycleStartEffect(c) {
            c.start()
            onStopOrDispose { c.stop() }
        }
        PrinterScreen(c, vm.http, vm::forget)
    }
}

enum class Tab(val label: String, val icon: Int) {
    Status("Status", R.drawable.ic_home),
    Camera("Camera", R.drawable.ic_videocam),
    Printer("Printer", R.drawable.ic_printer),
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
    var tab by rememberSaveable { mutableStateOf(Tab.Status) }
    Scaffold(bottomBar = {
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = tab == t, onClick = { tab = t },
                    icon = { Icon(painterResource(t.icon), null) }, label = { Text(t.label) },
                )
            }
        }
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(c.printer.name.ifEmpty { "Centauri Carbon" }, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (connected) "Connected · ${c.printer.ip}" else "Connecting to ${c.printer.ip}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            when (tab) {
                Tab.Status -> st?.let {
                    JobCard(c, it)
                    TempsCard(it)
                    ControlsCard(c, it)
                } ?: CircularProgressIndicator()
                Tab.Camera -> {
                    CameraView(c, http)
                    st?.let { JobCard(c, it) }
                }
                Tab.Printer -> PrinterCard(c, connected, onForget)
            }
        }
    }
}

@Composable
fun CameraView(c: SdcpClient, http: OkHttpClient) {
    var paused by rememberSaveable { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    // Holds one of the printer's few video slots only while this tab is on screen and playing;
    // pausing releases it and leaves the last frame frozen.
    LifecycleResumeEffect(c, paused) {
        if (!paused) c.video(true)
        onPauseOrDispose { if (!paused) c.video(false) }
    }
    LaunchedEffect(showControls, paused) {
        if (showControls && !paused) { delay(2500); showControls = false }
    }
    val url by c.videoUrl.collectAsState()
    val st by c.status.collectAsState()
    val frame by produceState<android.graphics.Bitmap?>(null, url) {
        val u = url ?: return@produceState
        // The stream drops whenever the printer toggles video; keep reconnecting while visible.
        while (true) {
            runCatching { mjpegFrames(http, u).collect { value = it } }
            delay(1000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { showControls = !showControls },
            Alignment.Center,
        ) {
            val f = frame
            if (f != null) Image(f.asImageBitmap(), "Camera", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (!paused) CircularProgressIndicator()
            if (showControls || paused) {
                FilledTonalIconButton(onClick = { paused = !paused }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause),
                        if (paused) "Play" else "Pause", Modifier.size(36.dp),
                    )
                }
            }
            st?.let {
                FilledIconToggleButton(
                    checked = it.light, onCheckedChange = c::setLight,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) { Icon(painterResource(R.drawable.ic_lightbulb), "Chamber light") }
            }
        }
    }
}

@Composable
fun PrinterCard(c: SdcpClient, connected: Boolean, onForget: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Name", c.printer.name.ifEmpty { "Centauri Carbon" })
            Stat("IP address", c.printer.ip)
            Stat("Connection", if (connected) "Connected" else "Disconnected")
            OutlinedButton(onClick = onForget) { Text("Change printer") }
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
    // Pause and stop both interrupt a running print, so each asks first.
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val s = st.print.status
    val paused = s == 5 || s == 6
    // Anything but idle / stopped / complete counts as a job in progress.
    val active = s !in setOf(0, 8, 9)
    val job = st.print.file.ifEmpty { "the current job" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paused) Button(onClick = c::resume, enabled = s == 6) { Text("Resume") }
                else Button(
                    onClick = { confirm = Confirm("Pause print?", "This pauses $job. You can resume it later.", "Pause", c::pause) },
                    enabled = s == 13,
                ) { Text("Pause") }
                OutlinedButton(
                    onClick = { confirm = Confirm("Stop print?", "This cancels $job. It can't be resumed.", "Stop print", c::stopPrint) },
                    enabled = active,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chamber light", Modifier.weight(1f))
                Switch(st.light, { c.setLight(it) })
            }
        }
    }
    confirm?.let { cf ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(cf.title) },
            text = { Text(cf.text) },
            confirmButton = { TextButton(onClick = { confirm = null; cf.action() }) { Text(cf.confirmLabel) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private class Confirm(val title: String, val text: String, val confirmLabel: String, val action: () -> Unit)
