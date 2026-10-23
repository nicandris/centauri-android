package app.centauri.remote

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.ZonedDateTime
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Wraps a printer command: a confirming buzz when it went out; if it couldn't be sent, a reject buzz and say so. */
@Composable
fun rememberCommand(): (Boolean) -> Unit {
    val snack = LocalSnackbar.current
    val haptic = LocalHapticFeedback.current
    val notSent = stringResource(R.string.not_sent)
    return remember(snack, haptic, notSent) {
        { sent ->
            haptic.performHapticFeedback(if (sent) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
            if (!sent) snack.say(notSent)
        }
    }
}

/** A spinner that TalkBack reads as [label]. */
@Composable
fun Spinner(
    modifier: Modifier = Modifier, label: String = stringResource(R.string.loading),
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) = CircularProgressIndicator(modifier.semantics { contentDescription = label }, strokeWidth = strokeWidth)

@Composable
fun StatusScreen(
    c: PrinterLink, http: OkHttpClient, connected: Boolean, offline: Boolean, st: Status?, pad: PaddingValues,
    videoPaused: Boolean, onVideoPausedChange: (Boolean) -> Unit,
    onFullscreen: () -> Unit, onChooseFile: () -> Unit, onChangePrinter: () -> Unit, labs: Set<Lab>, wide: Boolean = false,
) {
    val column: @Composable (Modifier, @Composable () -> Unit) -> Unit = { m, content ->
        Column(m.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
    val cards: @Composable (Status) -> Unit = { s ->
        JobCard(c, http, s, onChooseFile, labs)
        TempsCard(c, s)
        CanvasCard(c)
    }
    val camera: @Composable () -> Unit = { CameraView(c, http, false, videoPaused, onVideoPausedChange, onFullscreen) }
    val base = Modifier.fillMaxSize().padding(pad)
    when {
        offline -> column(base) { Offline(c, onChangePrinter) }
        !connected || st == null -> column(base) { Connecting(c) }
        // Wide windows: the camera on the left, everything else beside it.
        wide -> Row(base) {
            column(Modifier.weight(1f)) { camera() }
            column(Modifier.weight(1f)) { ClockWarning(c); cards(st) }
        }
        else -> column(base) {
            ClockWarning(c)
            camera()
            cards(st)
        }
    }
}

/** The first seconds of a connection: which printer it's trying, so the wait has context. */
@Composable
private fun Connecting(c: PrinterLink) {
    val name by c.name.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spinner()
        Text(
            stringResource(R.string.connecting_to, c.displayName(LocalResources.current, name), c.printer.ip),
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A one-line note while the printer's clock is stuck at 1970 (it couldn't reach a time server), with the details on request. */
@Composable
private fun ClockWarning(c: PrinterLink) {
    val on by Hub.clockWarning.collectAsStateWithLifecycle()
    val clock by c.clock.collectAsStateWithLifecycle()
    var more by rememberSaveable { mutableStateOf(false) }
    if (!on || clock?.let(::clockUnset) != true) return
    val snack = LocalSnackbar.current
    val hidden = stringResource(R.string.clock_hidden)
    // A warning, not an error: Primer's attention yellow.
    val colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp)) {
            Text(
                stringResource(if (more) R.string.clock_warning else R.string.clock_warning_short), Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.align(Alignment.End)) {
                if (!more) TextButton(onClick = { more = true }, colors = colors) { Text(stringResource(R.string.learn_more)) }
                TextButton(onClick = { Hub.setClockWarning(false); snack.say(hidden) }, colors = colors) {
                    Text(stringResource(R.string.hide))
                }
            }
        }
    }
}

/** Unreachable: retry, look for the printer at a new address (DHCP), or pick another. */
@Composable
private fun Offline(c: PrinterLink, onChangePrinter: () -> Unit) {
    val res = LocalResources.current
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val searching by Hub.searching.collectAsStateWithLifecycle()
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
        if (c is SdcpClient) OutlinedButton(
            onClick = {
                val was = c.printer.ip
                scope.launch {
                    val p = Hub.relocate()
                    snack.say(
                        when {
                            p == null -> res.getString(R.string.find_none)
                            p.ip != was -> res.getString(R.string.find_moved, p.ip)
                            else -> res.getString(R.string.find_same)
                        },
                    )
                }
            },
            enabled = !searching,
        ) {
            if (searching) Spinner(Modifier.size(18.dp), stringResource(R.string.find_searching), 2.dp)
            else Text(stringResource(R.string.find_again))
        }
        TextButton(onClick = onChangePrinter) { Text(stringResource(R.string.menu_change_printer)) }
    }
}

/**
 * Live camera with tap-for-controls, light toggle, fullscreen, pinch or double-tap zoom and "Save
 * frame". [paused] is hoisted so it survives entering and leaving fullscreen.
 */
@Composable
fun CameraView(
    c: PrinterLink, http: OkHttpClient, fullscreen: Boolean,
    paused: Boolean, onPausedChange: (Boolean) -> Unit, onToggleFullscreen: () -> Unit,
) {
    val command = rememberCommand()
    val ctx = LocalContext.current
    val res = LocalResources.current
    val snack = LocalSnackbar.current
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
    val problem by c.videoProblem.collectAsStateWithLifecycle()
    // Only the light matters here; don't recompose the camera for every status push.
    val light by remember(c) { c.status.map { it?.light }.distinctUntilChanged() }.collectAsStateWithLifecycle(null)
    val controlsLabel = stringResource(R.string.camera_controls)
    // The newest frame, for "Save frame"; not state, so frames don't recompose the controls.
    val latest = remember { AtomicReference<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    fun zoomTo(s: Float, by: Offset) {
        scale = s.coerceIn(1f, 4f)
        pan = Offset(clampPan(by.x, scale, box.width.toFloat()), clampPan(by.y, scale, box.height.toFloat()))
    }
    val zoom = rememberTransformableState { change, move, _ -> zoomTo(scale * change, pan + move) }
    val surface: @Composable (Modifier) -> Unit = { m ->
        Box(
            m.onSizeChanged { box = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { zoomTo(if (scale > 1f) 1f else 2f, Offset.Zero) },
                    )
                }
                .transformable(zoom, canPan = { scale > 1f })
                .semantics { onClick(controlsLabel) { showControls = !showControls; true } },
            Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
                Alignment.Center,
            ) {
                val still = c.stillFrame
                if (still != null) Image(
                    painterResource(still), stringResource(R.string.camera), Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                ) else CameraFrame(http, url, paused, problem, latest::set)
            }
            val controls = showControls || paused
            if (c.stillFrame == null && controls) {
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
            // MediaStore needs no storage permission from Android 10; older phones go without.
            if (controls && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                FilledTonalIconButton(
                    onClick = {
                        val frame = latest.get() ?: c.stillFrame?.let { BitmapFactory.decodeResource(res, it) }
                        // Process-wide: leaving the camera mid-save must not leave a half-written picture.
                        Hub.scope.launch {
                            val ok = frame != null && saveFrame(ctx, frame, "centauri-${System.currentTimeMillis()}")
                            snack.say(res.getString(if (ok) R.string.frame_saved else R.string.frame_not_saved))
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                ) { Icon(painterResource(R.drawable.ic_download), stringResource(R.string.save_frame)) }
            }
            if (fullscreen && controls) FullscreenStrip(c, Modifier.align(Alignment.BottomCenter).padding(horizontal = 72.dp, vertical = 14.dp))
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

/** Fullscreen: the job's name, progress and finish over the picture, shown and hidden with the controls. */
@Composable
private fun FullscreenStrip(c: PrinterLink, modifier: Modifier) {
    val ctx = LocalContext.current
    val st by c.status.collectAsStateWithLifecycle()
    val p = st?.print?.takeIf { showsJob(it.file, it.status) } ?: return
    val parts = listOfNotNull(
        jobName(p.file).title.ifEmpty { null }, stringResource(R.string.progress_percent, p.progress),
        if (p.status == PrintState.PRINTING) finishText(ctx, p) else printStatusLabel(LocalResources.current, p.status),
    )
    Text(
        parts.joinToString(" · "),
        modifier.background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 6.dp),
        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** [finishWhen] for the job now, in the phone's 12/24 h format. */
private fun finishText(ctx: Context, p: PrintInfo) =
    finishWhen(ctx.resources, ZonedDateTime.now(), p.totalS - p.elapsedS) { DateFormat.getTimeFormat(ctx).format(Date.from(it.toInstant())) }

/**
 * The live picture. Its own composable so a new frame (several per second) only recomposes
 * this. Pausing stops reading frames at once; the printer's video is released a moment later.
 */
@Composable
private fun CameraFrame(http: OkHttpClient, url: String?, paused: Boolean, problem: Int?, onFrame: (Bitmap) -> Unit) {
    // Streams that keep failing (an HTTP error, nothing listening) say so rather than spin forever.
    var failures by remember(url) { mutableIntStateOf(0) }
    val frame by produceState<Bitmap?>(null, url, paused) {
        val u = url ?: return@produceState
        if (paused) return@produceState // keep showing the last frame
        // The stream drops whenever the printer toggles video; keep reconnecting while visible.
        var wait = 1_000L
        while (true) {
            try {
                mjpegFrames(http, u).collect { value = it; onFrame(it); wait = 1_000L; failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // dropped stream or unusable URL from the printer: back off and retry
                failures++
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(10_000L)
        }
    }
    val f = frame
    if (f != null) Image(f.asImageBitmap(), stringResource(R.string.camera), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    else if (problem != null) Text(stringResource(problem), color = Color.White)
    else if (failures >= 2 && !paused) Text(stringResource(R.string.camera_stream_failed), color = Color.White)
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
private fun JobCard(c: PrinterLink, http: OkHttpClient, st: Status, onChooseFile: () -> Unit, labs: Set<Lab>) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val command = rememberCommand()
    val p = st.print
    val s = p.status
    val name = jobName(p.file)
    val thumb by c.thumbnail.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var reprint by rememberSaveable { mutableStateOf<String?>(null) }
    val askStop = { confirm = Confirm(R.string.stop_title, R.string.stop_body, name.title, R.string.stop_confirm, true, c::stopPrint) }
    // Why it stopped (runout, jam, came loose…) is only in the job's history details.
    val reason by produceState<Int?>(null, p.taskId, s) {
        value = if ((printStatusIsError(s) || s == PrintState.PAUSED) && p.taskId.isNotEmpty())
            orNull { c.taskDetails(p.taskId) }?.errorReason?.takeIf { it != 0 } else null
    }
    // Announced when the state changes (Printing → Paused → Complete), not on every percent.
    val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    val progress: @Composable () -> Unit = {
        val progressLabel = stringResource(R.string.print_progress)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { p.progress / 100f }, drawStopIndicator = {},
                modifier = Modifier.weight(1f).semantics { contentDescription = progressLabel },
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.progress_percent, p.progress), style = MaterialTheme.typography.titleMedium)
        }
    }
    Card(Modifier.fillMaxWidth(), border = cardBorder(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!showsJob(p.file, s)) {
                Text(
                    if (st.machine == 0) stringResource(R.string.ready_to_print) else machineStatusLabel(res, st.machine),
                    polite, style = MaterialTheme.typography.headlineSmall,
                )
                // The machine can be busy (starting a print, levelling…) before the job shows up.
                if (st.machine !in MACHINE_STARTING_JOB) FilledTonalButton(onClick = onChooseFile) { Text(stringResource(R.string.choose_file)) }
                if (st.machine == 0) RecentPrints(c) { reprint = it }
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
                        printStatusLabel(res, s), polite, style = MaterialTheme.typography.labelLarge,
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
            when {
                s == PrintState.COMPLETE -> {
                    Text(stringResource(R.string.took, formatDuration(res, p.elapsedS)), style = MaterialTheme.typography.bodyLarge)
                    FinalFrame(p.taskId)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { reprint = localPath(p.file) }) { Text(stringResource(R.string.history_print_again)) }
                        OutlinedButton(onClick = onChooseFile) { Text(stringResource(R.string.print_another)) }
                    }
                }
                s == PrintState.STOPPED -> {
                    Text(stringResource(R.string.stopped_at_layer, p.layer, p.totalLayers), style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = onChooseFile) { Text(stringResource(R.string.choose_file)) }
                }
                printStatusIsError(s) -> ErrorHero(reason, askStop)
                // No finish time while paused: it would only creep forward with the clock.
                s == PrintState.PAUSED -> {
                    Text(
                        stringResource(R.string.paused_at, p.layer, p.totalLayers, formatDuration(res, p.totalS - p.elapsedS)),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    reason?.let { errorReasonLabel(res, it) }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                    progress()
                    JobButtons(c, s, name.title, command) { confirm = it }
                }
                else -> {
                    printStep(s)?.let { StartSteps(it) }
                    Text(finishText(ctx, p), style = MaterialTheme.typography.headlineMedium)
                    progress()
                    Text(
                        stringResource(R.string.layer_left, p.layer, p.totalLayers, formatDuration(res, p.totalS - p.elapsedS)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p.speedPct != 100) Text(
                        stringResource(R.string.running_at_speed, p.speedPct),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    )
                    if (s == PrintState.PRINTING && p.totalS - p.elapsedS >= RELATIVE_ETA_S) FinishActions(name.title, p.totalS - p.elapsedS)
                    JobButtons(c, s, name.title, command) { confirm = it }
                    // The printer's own screen offers these during the matching step only.
                    if (Lab.PRINT_STEPS in labs) when (s) {
                        PrintState.PREHEATING -> OutlinedButton(onClick = { command(c.skipPreheat()) }) {
                            Text(stringResource(R.string.skip_preheat))
                            LabsBadge()
                        }
                        PrintState.AUTO_FEEDING -> OutlinedButton(onClick = { command(c.stopFeed()) }) {
                            Text(stringResource(R.string.stop_feed))
                            LabsBadge()
                        }
                    }
                }
            }
        }
    }
    confirm?.let { cf ->
        ConfirmDialog(
            title = stringResource(cf.title), body = stringResource(cf.body, cf.job), confirm = stringResource(cf.confirmLabel),
            onConfirm = { command(cf.action()) }, destructive = cf.destructive,
        ) { confirm = null }
    }
    reprint?.let { ReprintDialog(c, http, it, st, labs) { reprint = null } }
}

/** Marks an untested feature switched on in Labs. */
@Composable
private fun LabsBadge() {
    Spacer(Modifier.width(8.dp))
    Badge(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) { Text(stringResource(R.string.menu_labs)) }
}

/** The printer stopped on an error: why, what to do about it, and Stop. */
@Composable
private fun ErrorHero(reason: Int?, onStop: () -> Unit) {
    val res = LocalResources.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                reason?.let { errorReasonLabel(res, it) } ?: stringResource(R.string.alert_error),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(whatToDo(reason)), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = onStop, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.stop)) }
        }
    }
}

/** Heat → Level → Print while a job starts, the current step highlighted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartSteps(step: Int) {
    val labels = listOf(R.string.step_heat, R.string.step_level, R.string.step_print).map { stringResource(it) }
    val desc = stringResource(R.string.step_of, step + 1, labels.size, labels[step])
    FlowRow(
        Modifier.semantics(mergeDescendants = true) { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val reached = i <= step
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .background(if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                    Alignment.Center,
                ) {
                    Text(
                        "${i + 1}", style = MaterialTheme.typography.labelMedium,
                        color = if (reached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge,
                    color = if (i == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** For long prints: put the finish in the calendar or set an alarm for it, through the phone's own apps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinishActions(job: String, remainingS: Int) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val snack = LocalSnackbar.current
    val open = { make: (String, ZonedDateTime) -> Intent ->
        try {
            ctx.startActivity(make(res.getString(R.string.calendar_title, job), ZonedDateTime.now().plusSeconds(remainingS.toLong())))
        } catch (_: ActivityNotFoundException) {
            snack.say(res.getString(R.string.no_app))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = { open { title, end -> calendarIntent(title, end.toInstant().toEpochMilli()) } },
            label = { Text(stringResource(R.string.add_calendar)) },
        )
        AssistChip(onClick = { open(::alarmIntent) }, label = { Text(stringResource(R.string.set_alarm)) })
    }
}

/** Idle: how the last print ended, and the last few jobs as one-tap reprints. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentPrints(c: PrinterLink, onReprint: (String) -> Unit) {
    val res = LocalResources.current
    val history by produceState<List<HistoryEntry>?>(null, c) { value = orNull { c.history() } }
    val list = history?.takeIf { it.isNotEmpty() } ?: return
    Text(lastOutcome(res, list.first()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.history_print_again), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        recentJobs(list).forEach { e ->
            val title = jobName(e.path).title
            val desc = stringResource(R.string.reprint_desc, title)
            AssistChip(
                onClick = { onReprint(e.path) }, modifier = Modifier.semantics { contentDescription = desc },
                label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** The camera frame the monitor took as [taskId] finished, if it took one. */
@Composable
private fun FinalFrame(taskId: String) {
    val frame by Hub.finishFrame.collectAsStateWithLifecycle()
    val f = frame?.takeIf { it.first == taskId }?.second ?: return
    Image(
        f.asImageBitmap(), stringResource(R.string.final_frame),
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).background(Color.Black),
        contentScale = ContentScale.Fit,
    )
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
            // Paused: Resume is the thing to do, so it gets the strongest colour.
            colors = if (s == PrintState.PAUSED) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
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

/** Nozzle, bed and chamber; tapping the nozzle or bed sets its target. */
@Composable
private fun TempsCard(c: PrinterLink, st: Status) {
    var editing by remember { mutableStateOf<Boolean?>(null) } // true: nozzle, false: bed
    Card(Modifier.fillMaxWidth(), border = cardBorder()) {
        Row(Modifier.padding(8.dp)) {
            Temp(stringResource(R.string.nozzle), st.nozzle, st.nozzleTarget, Modifier.weight(1f)) { editing = true }
            Temp(stringResource(R.string.bed), st.bed, st.bedTarget, Modifier.weight(1f)) { editing = false }
            Temp(stringResource(R.string.chamber), st.chamber, null, Modifier.weight(1f), onClick = null)
        }
    }
    editing?.let { nozzle -> TargetTempDialog(c, st, nozzle) { editing = null } }
}

/** The Canvas's trays, the one feeding the nozzle outlined; tap one for its details. Only shown while a Canvas is attached. */
@Composable
private fun CanvasCard(c: PrinterLink) {
    val canvas by c.canvas.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<Tray?>(null) }
    // No card for a Canvas that reports no connected trays.
    val cv = canvas?.takeIf { it.trays.isNotEmpty() } ?: return
    val detailsLabel = stringResource(R.string.tray_details)
    Card(Modifier.fillMaxWidth(), border = cardBorder()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.canvas), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                cv.trays.forEach { t ->
                    val desc = trayDescription(t, cv.isActive(t))
                    Column(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
                            .clickable(onClickLabel = detailsLabel, role = Role.Button) { details = t }
                            .semantics(mergeDescendants = true) { contentDescription = desc }.padding(vertical = 4.dp),
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
    details?.let { t -> TraySheet(t, cv.isActive(t)) { details = null } }
}

/** What the printer knows about one tray; read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraySheet(t: Tray, active: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TraySwatch(t, active, 48)
                Text(
                    listOfNotNull(t.label, if (active) stringResource(R.string.tray_in_use) else null).joinToString(" · "),
                    Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleLarge,
                )
            }
            val facts = if (!t.loaded) listOf(R.string.tray_status to stringResource(R.string.tray_empty))
            else listOf(
                R.string.tray_brand to t.brand, R.string.tray_filament to t.name, R.string.tray_type to t.type,
                R.string.tray_colour to (t.color?.let { "#%06X".format(it) } ?: ""),
            ).filter { it.second.isNotEmpty() }
            facts.forEach { (label, value) ->
                Row(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(
                        stringResource(label), Modifier.weight(2f), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(value, Modifier.weight(3f), style = MaterialTheme.typography.bodyLarge)
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

/**
 * "250 °C" at target; the target appears only while heating or cooling toward it. TalkBack reads it
 * in one go ("Nozzle 220 degrees, heading to 250"); [onClick] makes it a button that sets the target.
 */
@Composable
private fun Temp(label: String, actual: Double, target: Double?, modifier: Modifier, onClick: (() -> Unit)?) {
    val spoken = when {
        target == 0.0 -> stringResource(R.string.temp_spoken_off, label, actual)
        target != null && abs(actual - target) >= 3 -> stringResource(R.string.temp_spoken_to, label, actual, target)
        else -> stringResource(R.string.temp_spoken, label, actual)
    }
    val changeLabel = stringResource(R.string.change_target)
    val tap = onClick?.let { Modifier.clip(MaterialTheme.shapes.medium).clickable(onClickLabel = changeLabel, role = Role.Button, onClick = it) }
    Column(modifier.then(tap ?: Modifier).semantics(mergeDescendants = true) { contentDescription = spoken }.padding(8.dp)) {
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
