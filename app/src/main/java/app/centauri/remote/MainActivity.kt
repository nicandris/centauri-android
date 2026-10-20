package app.centauri.remote

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
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
    Files("Files", R.drawable.ic_folder),
    History("History", R.drawable.ic_history),
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
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    if (fullscreen) {
        ImmersiveLandscape()
        BackHandler { fullscreen = false }
        CameraView(c, http, fullscreen = true) { fullscreen = false }
        return
    }
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
                    TempsCard(c, it)
                    SpeedCard(c, it)
                    FansCard(c, it)
                    ControlsCard(c, it)
                } ?: CircularProgressIndicator()
                Tab.Camera -> {
                    CameraView(c, http, fullscreen = false) { fullscreen = true }
                    st?.let { JobCard(c, it) }
                }
                Tab.Files -> FilesScreen(c, http, st)
                Tab.History -> HistoryScreen(c, http)
                Tab.Printer -> PrinterCard(c, connected, onForget)
            }
        }
    }
}

@Composable
fun CameraView(c: SdcpClient, http: OkHttpClient, fullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    var paused by rememberSaveable { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    // Holds one of the printer's few video slots only while this tab is on screen and playing;
    // pausing releases it and leaves the last frame frozen.
    LifecycleResumeEffect(c, paused) {
        // Capture now: by the time cleanup runs, `paused` already holds the new value.
        val playing = !paused
        if (playing) c.video(true)
        onPauseOrDispose { if (playing) c.video(false) }
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
    val surface: @Composable (Modifier) -> Unit = { m ->
        Box(m.clickable { showControls = !showControls }, Alignment.Center) {
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
            FilledTonalIconButton(onClick = onToggleFullscreen, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                Icon(
                    painterResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen),
                    if (fullscreen) "Exit fullscreen" else "Fullscreen",
                )
            }
        }
    }
    if (fullscreen) surface(Modifier.fillMaxSize().background(Color.Black))
    else Card(Modifier.fillMaxWidth()) { surface(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
}

/** Landscape with hidden system bars (swipe to peek) while composed; restores both on exit. */
@Composable
private fun ImmersiveLandscape() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            bars.show(WindowInsetsCompat.Type.systemBars())
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

fun hm(s: Int) = if (s >= 3600) "${s / 3600}h ${s % 3600 / 60}m" else "${s / 60}m"

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
fun TempsCard(c: SdcpClient, st: Status) {
    var editing by remember { mutableStateOf<TempTarget?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Temperatures · tap nozzle or bed to set", style = MaterialTheme.typography.labelSmall)
            Row {
                Stat("Nozzle", "%.0f / %.0f °C".format(st.nozzle, st.nozzleTarget),
                    Modifier.weight(1f).clickable { editing = TempTarget("Nozzle", st.nozzleTarget.toInt(), 320, c::setNozzle) })
                Stat("Bed", "%.0f / %.0f °C".format(st.bed, st.bedTarget),
                    Modifier.weight(1f).clickable { editing = TempTarget("Bed", st.bedTarget.toInt(), 110, c::setBed) })
                Stat("Chamber", "%.0f °C".format(st.chamber), Modifier.weight(1f))
            }
        }
    }
    editing?.let { TempDialog(it) { editing = null } }
}

private class TempTarget(val label: String, val current: Int, val max: Int, val set: (Int) -> Unit)

@Composable
private fun TempDialog(t: TempTarget, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(t.current.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in 0..t.max }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${t.label} target") },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(3) },
                label = { Text("°C, 0–${t.max} (0 = off)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(enabled = value != null, onClick = { t.set(value!!); onDismiss() }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SpeedCard(c: SdcpClient, st: Status) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Print speed · ${st.print.speedPct}%", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SPEED_PRESETS.forEachIndexed { i, (pct, label) ->
                    SegmentedButton(
                        selected = st.print.speedPct == pct, onClick = { c.setSpeed(pct) },
                        shape = SegmentedButtonDefaults.itemShape(i, SPEED_PRESETS.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
fun FansCard(c: SdcpClient, st: Status) {
    // A dragged value wins until the printer has had a few seconds to report it back.
    var drag by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    LaunchedEffect(drag) { if (drag != null) { delay(4000); drag = null } }
    val v = drag ?: Triple(st.modelFan, st.auxFan, st.boxFan)
    val send = { t: Triple<Int, Int, Int> -> c.setFans(t.first, t.second, t.third) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Fans", style = MaterialTheme.typography.labelLarge)
            FanRow("Model", v.first, { drag = v.copy(first = it) }) { drag?.let(send) }
            FanRow("Aux", v.second, { drag = v.copy(second = it) }) { drag?.let(send) }
            FanRow("Box", v.third, { drag = v.copy(third = it) }) { drag?.let(send) }
        }
    }
}

@Composable
private fun FanRow(label: String, value: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp))
        Slider(value.toFloat(), { onChange(it.roundToInt()) }, Modifier.weight(1f), valueRange = 0f..100f, onValueChangeFinished = onDone)
        Text("$value%", Modifier.width(48.dp), textAlign = TextAlign.End)
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
                Text("Home", Modifier.weight(1f))
                listOf("X", "Y", "Z", "All").forEach { a ->
                    TextButton(onClick = { c.home(if (a == "All") "XYZ" else a) }, enabled = st.idle) { Text(a) }
                }
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
