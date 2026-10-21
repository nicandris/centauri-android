package app.centauri.remote

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ProgressIndicatorDefaults
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import kotlin.math.abs

/** Wraps a printer command: if it couldn't be sent, say so instead of failing silently. */
@Composable
fun rememberCommand(): (Boolean) -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) { { sent -> if (!sent) Toast.makeText(ctx, R.string.not_sent, Toast.LENGTH_SHORT).show() } }
}

/** A spinner that TalkBack reads as [label]. */
@Composable
fun Spinner(
    modifier: Modifier = Modifier, label: String = stringResource(R.string.loading),
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) = CircularProgressIndicator(modifier.semantics { contentDescription = label }, strokeWidth = strokeWidth)

@Composable
fun StatusScreen(
    c: PrinterLink, http: OkHttpClient, connected: Boolean, st: Status?, pad: PaddingValues,
    videoPaused: Boolean, onVideoPausedChange: (Boolean) -> Unit,
    onFullscreen: () -> Unit, onChooseFile: () -> Unit, wide: Boolean = false,
) {
    val command = rememberCommand()
    // Give a fresh connection a few seconds before calling the printer unreachable.
    val offline by produceState(false, connected) {
        value = false
        if (!connected) { delay(4000); value = true }
    }
    val column: @Composable (Modifier, @Composable () -> Unit) -> Unit = { m, content ->
        Column(m.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
    val cards: @Composable (Status) -> Unit = { s ->
        JobCard(c, s, onChooseFile)
        TempsCard(s)
        CanvasCard(c)
        Card(Modifier.fillMaxWidth(), border = cardBorder()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.chamber_light)) },
                leadingContent = { Icon(painterResource(R.drawable.ic_lightbulb), null) },
                trailingContent = { Switch(s.light, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.toggleable(s.light, role = Role.Switch) { command(c.setLight(it)) },
            )
        }
    }
    val camera: @Composable () -> Unit = { CameraView(c, http, false, videoPaused, onVideoPausedChange, onFullscreen) }
    val base = Modifier.fillMaxSize().padding(pad)
    when {
        offline -> column(base) { Offline(c) }
        !connected || st == null -> column(base) {
            Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Spinner() }
        }
        // Wide windows: the camera on the left, everything else beside it.
        wide && c.hasCamera -> Row(base) {
            column(Modifier.weight(1f)) { camera() }
            column(Modifier.weight(1f)) { cards(st) }
        }
        else -> column(base) {
            if (c.hasCamera) camera()
            cards(st)
        }
    }
}

@Composable
private fun Offline(c: PrinterLink) {
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

/**
 * Live camera with tap-for-controls, light toggle and fullscreen. [paused] is hoisted so it
 * survives entering and leaving fullscreen.
 */
@Composable
fun CameraView(
    c: PrinterLink, http: OkHttpClient, fullscreen: Boolean,
    paused: Boolean, onPausedChange: (Boolean) -> Unit, onToggleFullscreen: () -> Unit,
) {
    val command = rememberCommand()
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
    // Only the light matters here; don't recompose the camera for every status push.
    val light by remember(c) { c.status.map { it?.light }.distinctUntilChanged() }.collectAsStateWithLifecycle(null)
    val controlsLabel = stringResource(R.string.camera_controls)
    val surface: @Composable (Modifier) -> Unit = { m ->
        Box(m.clickable(onClickLabel = controlsLabel) { showControls = !showControls }, Alignment.Center) {
            val still = c.stillFrame
            if (still != null) Image(
                painterResource(still), stringResource(R.string.camera), Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            ) else CameraFrame(http, url, paused)
            if (still == null && (showControls || paused)) {
                FilledTonalIconButton(onClick = { onPausedChange(!paused) }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause),
                        stringResource(if (paused) R.string.camera_play else R.string.camera_pause), Modifier.size(36.dp),
                    )
                }
            }
            light?.let { on ->
                FilledIconToggleButton(
                    checked = on, onCheckedChange = { command(c.setLight(it)) },
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
    else Card(Modifier.fillMaxWidth(), border = cardBorder(), shape = MaterialTheme.shapes.large) {
        surface(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black))
    }
}

/**
 * The live picture. Its own composable so a new frame (several per second) only recomposes
 * this. Pausing stops reading frames at once; the printer's video is released a moment later.
 */
@Composable
private fun CameraFrame(http: OkHttpClient, url: String?, paused: Boolean) {
    val frame by produceState<Bitmap?>(null, url, paused) {
        val u = url?.ifEmpty { null } ?: return@produceState
        if (paused) return@produceState // keep showing the last frame
        // The stream drops whenever the printer toggles video; keep reconnecting while visible.
        var wait = 1_000L
        while (true) {
            try {
                mjpegFrames(http, u).collect { value = it; wait = 1_000L }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // dropped stream or unusable URL from the printer: back off and retry
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(10_000L)
        }
    }
    val f = frame
    if (f != null) Image(f.asImageBitmap(), stringResource(R.string.camera), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    else if (url == "") Text(stringResource(R.string.camera_unavailable), color = Color.White)
    else if (!paused) Spinner()
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
private fun JobCard(c: PrinterLink, st: Status, onChooseFile: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val command = rememberCommand()
    val p = st.print
    val s = p.status
    val name = jobName(p.file)
    val thumb by c.thumbnail.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    Card(Modifier.fillMaxWidth(), border = cardBorder(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (p.file.isEmpty() || s == PrintState.IDLE) {
                Text(
                    if (st.machine == 0) stringResource(R.string.ready_to_print) else machineStatusLabel(res, st.machine),
                    style = MaterialTheme.typography.headlineSmall,
                )
                FilledTonalButton(onClick = onChooseFile) { Text(stringResource(R.string.choose_file)) }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val thumbModifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                val bundled = c.jobThumbnailRes
                val downloaded = thumb
                if (downloaded != null) Image(downloaded.asImageBitmap(), null, thumbModifier)
                else if (bundled != null) Image(painterResource(bundled), null, thumbModifier, contentScale = ContentScale.Fit)
                if (downloaded != null || bundled != null) Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        printStatusLabel(res, s), style = MaterialTheme.typography.labelLarge,
                        color = if (printStatusIsError(s)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(name.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    name.detail(res)?.let {
                        Text(
                            it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when (s) {
                PrintState.COMPLETE -> {
                    Text(stringResource(R.string.took, formatDuration(res, p.elapsedS)), style = MaterialTheme.typography.bodyLarge)
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
                        stringResource(R.string.layer_left, p.layer, p.totalLayers, formatDuration(res, p.totalS - p.elapsedS)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p.speedPct != 100) Text(
                        stringResource(R.string.running_at_speed, p.speedPct),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    )
                    JobButtons(c, s, name.title, command) { confirm = it }
                    // The printer's own screen offers these during the matching step only.
                    val labsOn by Hub.labs.collectAsStateWithLifecycle()
                    if (Lab.PRINT_STEPS in labsOn) when (s) {
                        PrintState.PREHEATING -> OutlinedButton(onClick = { command(c.skipPreheat()) }) {
                            Text(stringResource(R.string.skip_preheat))
                        }
                        PrintState.AUTO_FEEDING -> OutlinedButton(onClick = { command(c.stopFeed()) }) {
                            Text(stringResource(R.string.stop_feed))
                        }
                    }
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
                    onClick = { confirm = null; command(cf.action()) },
                    colors = if (cf.destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.textButtonColors(),
                ) { Text(stringResource(cf.confirmLabel)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun JobButtons(c: PrinterLink, s: Int, job: String, command: (Boolean) -> Unit, ask: (Confirm) -> Unit) {
    val busy = printStatusIsTransition(s)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledTonalButton(
            onClick = {
                if (s == PrintState.PAUSED) command(c.resume())
                else ask(Confirm(R.string.pause_title, R.string.pause_body, job, R.string.pause, false, c::pause))
            },
            enabled = s == PrintState.PAUSED || s == PrintState.PRINTING,
            modifier = Modifier.weight(1f),
        ) {
            if (busy) Spinner(Modifier.size(18.dp), printStatusLabel(LocalResources.current, s), 2.dp)
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
    val title: Int, val body: Int, val job: String, val confirmLabel: Int, val destructive: Boolean, val action: () -> Boolean,
)

@Composable
private fun TempsCard(st: Status) {
    Card(Modifier.fillMaxWidth(), border = cardBorder()) {
        Row(Modifier.padding(16.dp)) {
            Temp(stringResource(R.string.nozzle), st.nozzle, st.nozzleTarget, Modifier.weight(1f))
            Temp(stringResource(R.string.bed), st.bed, st.bedTarget, Modifier.weight(1f))
            Temp(stringResource(R.string.chamber), st.chamber, null, Modifier.weight(1f))
        }
    }
}

/** The Canvas's trays, the one feeding the nozzle outlined. Only shown while a Canvas is attached. */
@Composable
private fun CanvasCard(c: PrinterLink) {
    val canvas by c.canvas.collectAsStateWithLifecycle()
    // No card for a Canvas that reports no connected trays.
    val cv = canvas?.takeIf { it.trays.isNotEmpty() } ?: return
    Card(Modifier.fillMaxWidth(), border = cardBorder()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.canvas), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                cv.trays.forEach { t ->
                    val desc = trayDescription(t, cv.isActive(t))
                    Column(
                        Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = desc },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TraySwatch(t, cv.isActive(t), 36)
                        Text(t.label, style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (t.loaded) t.type.ifEmpty { "–" } else stringResource(R.string.tray_empty),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A tray's colour as a circle; empty trays are a hollow ring, the one in use gets a thick outline. */
@Composable
fun TraySwatch(t: Tray, active: Boolean, sizeDp: Int) {
    val ring = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape)
            .background(t.color?.takeIf { t.loaded }?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Transparent)
            .border(if (active) 3.dp else 1.dp, ring, CircleShape),
    )
}

/** What TalkBack reads for a tray: "A1, ELEGOO PLA+, in use" or "A4, empty". */
@Composable
fun trayDescription(t: Tray, active: Boolean): String {
    val what = if (!t.loaded) stringResource(R.string.tray_empty)
        else listOf(t.brand, t.name.ifEmpty { t.type }).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "–" }
    return listOfNotNull(t.label, what, if (active) stringResource(R.string.tray_in_use) else null).joinToString(", ")
}

/** GitHub-style 1 dp outline around the status cards. */
@Composable
private fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

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
