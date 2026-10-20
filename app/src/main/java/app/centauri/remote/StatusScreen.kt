package app.centauri.remote

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import kotlin.math.abs

fun hm(s: Int) = if (s >= 3600) "${s / 3600}h ${s % 3600 / 60}m" else "${s / 60}m"

@Composable
fun StatusScreen(
    c: SdcpClient, http: OkHttpClient, connected: Boolean, st: Status?, pad: PaddingValues,
    onFullscreen: () -> Unit, onChooseFile: () -> Unit,
) {
    // Give a fresh connection a few seconds before calling the printer unreachable.
    val offline by produceState(false, connected) {
        value = false
        if (!connected) { delay(4000); value = true }
    }
    Column(
        Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            offline -> Offline(c)
            !connected || st == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
            else -> {
                CameraView(c, http, fullscreen = false, onToggleFullscreen = onFullscreen)
                JobCard(c, st, onChooseFile)
                TempsCard(st)
                Card(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Chamber light") },
                        leadingContent = { Icon(painterResource(R.drawable.ic_lightbulb), null) },
                        trailingContent = { Switch(st.light, c::setLight) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
private fun Offline(c: SdcpClient) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Can't reach the printer", style = MaterialTheme.typography.titleLarge)
        Text(
            "${c.printer.ip} isn't answering. Check that the printer is on and the phone is on the same network.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = c::reconnect) { Text("Retry") }
    }
}

@Composable
fun CameraView(c: SdcpClient, http: OkHttpClient, fullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    var paused by rememberSaveable { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    // Holds a video slot only while visible and playing; pausing freezes the last frame.
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
    else Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        surface(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black))
    }
}

/** Landscape with hidden system bars (swipe to peek) while composed; restores both on exit. */
@Composable
fun ImmersiveLandscape() {
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
private fun JobCard(c: SdcpClient, st: Status, onChooseFile: () -> Unit) {
    val p = st.print
    val s = p.status
    val name = jobName(p.file)
    val thumb by c.thumbnail.collectAsState()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (p.file.isEmpty() || s == 0) {
                Text(
                    if (st.machine == 0) "Ready to print" else machineStatusLabel(st.machine),
                    style = MaterialTheme.typography.headlineSmall,
                )
                FilledTonalButton(onClick = onChooseFile) { Text("Choose a file") }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                thumb?.let {
                    Image(
                        it.asImageBitmap(), null,
                        Modifier.size(72.dp).clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        printStatusLabel(s), style = MaterialTheme.typography.labelLarge,
                        color = if (printStatusIsError(s)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(name.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    name.detail?.let {
                        Text(
                            it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when (s) {
                9 -> {
                    Text("Took ${hm(p.elapsedS)}", style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = onChooseFile) { Text("Print another") }
                }
                8 -> {
                    Text("Stopped at layer ${p.layer} of ${p.totalLayers}", style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = onChooseFile) { Text("Choose a file") }
                }
                else -> {
                    Text("Finishes ${finishClock(LocalContext.current, st)}", style = MaterialTheme.typography.headlineMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { p.progress / 100f }, modifier = Modifier.weight(1f), drawStopIndicator = {},
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("${p.progress}%", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Layer ${p.layer} of ${p.totalLayers} · ${hm((p.totalS - p.elapsedS).coerceAtLeast(0))} left",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p.speedPct != 100) Text(
                        "Running at ${p.speedPct}% speed",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    )
                    JobButtons(c, s, name.title) { confirm = it }
                }
            }
        }
    }
    confirm?.let { cf ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(cf.title) },
            text = { Text(cf.text) },
            confirmButton = {
                TextButton(
                    onClick = { confirm = null; cf.action() },
                    colors = if (cf.destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.textButtonColors(),
                ) { Text(cf.confirmLabel) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun JobButtons(c: SdcpClient, s: Int, job: String, ask: (Confirm) -> Unit) {
    val busy = printStatusIsTransition(s)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledTonalButton(
            onClick = {
                if (s == 6) c.resume()
                else ask(Confirm("Pause print?", "$job pauses where it is. You can resume it later.", "Pause", false, c::pause))
            },
            enabled = s == 6 || s == 13,
            modifier = Modifier.weight(1f),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (s == 6) "Resume" else "Pause")
        }
        OutlinedButton(
            onClick = { ask(Confirm("Stop print?", "$job is cancelled and can't be resumed.", "Stop print", true, c::stopPrint)) },
            enabled = !busy,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Stop") }
    }
}

private class Confirm(
    val title: String, val text: String, val confirmLabel: String, val destructive: Boolean, val action: () -> Unit,
)

@Composable
private fun TempsCard(st: Status) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Temp("Nozzle", st.nozzle, st.nozzleTarget, Modifier.weight(1f))
            Temp("Bed", st.bed, st.bedTarget, Modifier.weight(1f))
            Temp("Chamber", st.chamber, null, Modifier.weight(1f))
        }
    }
}

/** "250 °C" at target; the target appears only while heating or cooling toward it. */
@Composable
private fun Temp(label: String, actual: Double, target: Double?, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%.0f °C".format(actual), style = MaterialTheme.typography.titleLarge)
        when {
            target == null -> {}
            target == 0.0 -> Text("Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            abs(actual - target) >= 3 -> Text(
                "→ %.0f °C".format(target), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
