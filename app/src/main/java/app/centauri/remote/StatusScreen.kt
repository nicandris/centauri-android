package app.centauri.remote

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.math.abs

/** Compact duration: "8h 58m" or "42m". Units are universal enough not to localise. */
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
                        headlineContent = { Text(stringResource(R.string.chamber_light)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_lightbulb), null) },
                        trailingContent = { Switch(st.light, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.toggleable(st.light, role = Role.Switch, onValueChange = c::setLight),
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
        Text(stringResource(R.string.offline_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.offline_body, c.printer.ip),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = c::reconnect) { Text(stringResource(R.string.retry)) }
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
    val url by c.videoUrl.collectAsStateWithLifecycle()
    val st by c.status.collectAsStateWithLifecycle()
    val surface: @Composable (Modifier) -> Unit = { m ->
        Box(m.clickable { showControls = !showControls }, Alignment.Center) {
            CameraFrame(http, url, paused)
            if (showControls || paused) {
                FilledTonalIconButton(onClick = { paused = !paused }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause),
                        stringResource(if (paused) R.string.camera_play else R.string.camera_pause), Modifier.size(36.dp),
                    )
                }
            }
            st?.let {
                FilledIconToggleButton(
                    checked = it.light, onCheckedChange = c::setLight,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) { Icon(painterResource(R.drawable.ic_lightbulb), stringResource(R.string.chamber_light)) }
            }
            FilledTonalIconButton(onClick = onToggleFullscreen, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                Icon(
                    painterResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen),
                    stringResource(if (fullscreen) R.string.exit_fullscreen else R.string.fullscreen),
                )
            }
        }
    }
    if (fullscreen) surface(Modifier.fillMaxSize().background(Color.Black))
    else Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        surface(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black))
    }
}

/**
 * The live picture. Kept as its own composable so a new frame (several per second) only
 * recomposes this, not the surrounding card and its buttons.
 */
@Composable
private fun CameraFrame(http: OkHttpClient, url: String?, paused: Boolean) {
    val frame by produceState<Bitmap?>(null, url) {
        val u = url ?: return@produceState
        // The stream drops whenever the printer toggles video; keep reconnecting while visible.
        var wait = 1_000L
        while (true) {
            try {
                mjpegFrames(http, u).collect { value = it; wait = 1_000L }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(10_000L)
        }
    }
    val f = frame
    if (f != null) Image(f.asImageBitmap(), stringResource(R.string.camera), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    else if (!paused) CircularProgressIndicator()
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
    val ctx = LocalContext.current
    val p = st.print
    val s = p.status
    val name = jobName(p.file)
    val thumb by c.thumbnail.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (p.file.isEmpty() || s == PrintState.IDLE) {
                Text(
                    if (st.machine == 0) stringResource(R.string.ready_to_print) else machineStatusLabel(LocalResources.current, st.machine),
                    style = MaterialTheme.typography.headlineSmall,
                )
                FilledTonalButton(onClick = onChooseFile) { Text(stringResource(R.string.choose_file)) }
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
                        printStatusLabel(LocalResources.current, s), style = MaterialTheme.typography.labelLarge,
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
                PrintState.COMPLETE -> {
                    Text(stringResource(R.string.took, hm(p.elapsedS)), style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = onChooseFile) { Text(stringResource(R.string.print_another)) }
                }
                PrintState.STOPPED -> {
                    Text(stringResource(R.string.stopped_at_layer, p.layer, p.totalLayers), style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = onChooseFile) { Text(stringResource(R.string.choose_file)) }
                }
                else -> {
                    Text(stringResource(R.string.finishes_at, finishClock(ctx, st)), style = MaterialTheme.typography.headlineMedium)
                    val progressLabel = stringResource(R.string.print_progress)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { p.progress / 100f }, drawStopIndicator = {},
                            modifier = Modifier.weight(1f).semantics { contentDescription = progressLabel },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.progress_percent, p.progress), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        stringResource(R.string.layer_left, p.layer, p.totalLayers, hm((p.totalS - p.elapsedS).coerceAtLeast(0))),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p.speedPct != 100) Text(
                        stringResource(R.string.running_at_speed, p.speedPct),
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
            title = { Text(stringResource(cf.title)) },
            text = { Text(stringResource(cf.body, cf.job)) },
            confirmButton = {
                TextButton(
                    onClick = { confirm = null; cf.action() },
                    colors = if (cf.destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.textButtonColors(),
                ) { Text(stringResource(cf.confirmLabel)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun JobButtons(c: SdcpClient, s: Int, job: String, ask: (Confirm) -> Unit) {
    val busy = printStatusIsTransition(s)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledTonalButton(
            onClick = {
                if (s == PrintState.PAUSED) c.resume()
                else ask(Confirm(R.string.pause_title, R.string.pause_body, job, R.string.pause, false, c::pause))
            },
            enabled = s == PrintState.PAUSED || s == PrintState.PRINTING,
            modifier = Modifier.weight(1f),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(if (s == PrintState.PAUSED) R.string.resume else R.string.pause))
        }
        OutlinedButton(
            onClick = { ask(Confirm(R.string.stop_title, R.string.stop_body, job, R.string.stop_confirm, true, c::stopPrint)) },
            enabled = !busy,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.stop)) }
    }
}

private class Confirm(
    val title: Int, val body: Int, val job: String, val confirmLabel: Int, val destructive: Boolean, val action: () -> Unit,
)

@Composable
private fun TempsCard(st: Status) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Temp(stringResource(R.string.nozzle), st.nozzle, st.nozzleTarget, Modifier.weight(1f))
            Temp(stringResource(R.string.bed), st.bed, st.bedTarget, Modifier.weight(1f))
            Temp(stringResource(R.string.chamber), st.chamber, null, Modifier.weight(1f))
        }
    }
}

/** "250 °C" at target; the target appears only while heating or cooling toward it. */
@Composable
private fun Temp(label: String, actual: Double, target: Double?, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.temp_c, actual), style = MaterialTheme.typography.titleLarge)
        when {
            target == null -> {}
            target == 0.0 -> Text(
                stringResource(R.string.off), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            abs(actual - target) >= 3 -> Text(
                stringResource(R.string.temp_to, target), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
