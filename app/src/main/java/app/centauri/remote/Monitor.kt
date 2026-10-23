package app.centauri.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.graphics.scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.Date

private const val ONGOING_ID = 1
private const val ALERT_ID = 2
private const val TIME_LEFT_ID = 3
private const val FIRST_LAYER_ID = 4
private const val OFFLINE_GIVE_UP_MS = 5 * 60_000L
private const val SNAPSHOT_TIMEOUT_MS = 10_000L
private const val SNAPSHOT_MAX_WIDTH = 960
private const val SNAPSHOT_LIGHT_MS = 3 * 60_000L
private const val LIGHT_SETTLE_MS = 1_500L

/** The layer whose start means the first layer is down, for the first-layer check. */
const val FIRST_LAYER_CHECK = 3
/** "Time left" alert choices, in minutes before the end. */
val REMIND_CHOICES = listOf(5, 10, 15)

/**
 * The kinds of notification, each a switch in the app's Notifications screen and a channel the system's
 * settings can tune. [label] names both.
 */
enum class Notice(val channel: String, @StringRes val label: Int, val onByDefault: Boolean = true) {
    PROGRESS("progress", R.string.channel_progress),
    COMPLETED("finished", R.string.channel_finished),
    // "alerts" predates the per-event channels and carried every alert; errors keep it, and the user's settings with it.
    ERROR("alerts", R.string.channel_alerts),
    PAUSED("paused", R.string.channel_paused),
    STOPPED("stopped", R.string.channel_stopped),
    LOST("lost", R.string.channel_lost),
    TIME_LEFT("time_left", R.string.channel_time_left, onByDefault = false),
    FIRST_LAYER("first_layer", R.string.channel_first_layer, onByDefault = false);

    companion object {
        val defaults = entries.filter { it.onByDefault }.toSet()
    }
}

/** Which kind of notification a status-change alert ([alertFor]'s title) is. */
fun noticeFor(@StringRes alert: Int): Notice = when (alert) {
    R.string.alert_finished -> Notice.COMPLETED
    R.string.alert_paused -> Notice.PAUSED
    R.string.alert_stopped -> Notice.STOPPED
    else -> Notice.ERROR
}

const val ACTION_PAUSE = "app.centauri.remote.PAUSE"
const val ACTION_RESUME = "app.centauri.remote.RESUME"
const val ACTION_LIGHT = "app.centauri.remote.LIGHT"
const val ACTION_LOOKS_GOOD = "app.centauri.remote.LOOKS_GOOD"
// Live Update bar: preheat and levelling get a sliver each, the print the rest.
private const val LIVE_PREHEAT = 5
private const val LIVE_LEVEL = 5
private const val LIVE_PRINT = 90
// Android 16.0 reads this key; 36.1 made it public (Notification.EXTRA_REQUEST_PROMOTED_ONGOING) with a setter.
private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

/** Wall-clock time the job should end, in the phone's 12/24 h format. */
fun finishClock(ctx: Context, st: Status): String {
    val remaining = (st.print.totalS - st.print.elapsedS).coerceAtLeast(0)
    return DateFormat.getTimeFormat(ctx).format(Date(System.currentTimeMillis() + remaining * 1000L))
}

/** Whether the job in [then] still runs in [now]: same task, not ended or failed. */
fun stillRunning(then: Status, now: Status?) =
    now != null && !now.idle && now.print.status != PrintState.ERROR && now.print.taskId == then.print.taskId

/** What the UI does about the print monitor. */
enum class MonitorAction { STOP, NONE, ASK_PERMISSION, START }

/** Notifications off stop it; a running print starts it, asking for the notification permission first if needed. */
fun monitorAction(notify: Boolean, active: Boolean, needsPermission: Boolean) = when {
    !notify -> MonitorAction.STOP
    !active -> MonitorAction.NONE
    needsPermission -> MonitorAction.ASK_PERMISSION
    else -> MonitorAction.START
}

/**
 * What [PrintMonitorService] does with one status update; [timeLeft] and [firstLayer] fire once per job.
 * [finished]: the job just completed, whether or not its alert is switched on.
 */
data class MonitorStep(
    val alert: Int? = null, val stop: Boolean = false, val repost: Boolean = false,
    val timeLeft: Boolean = false, val firstLayer: Boolean = false, val finished: Boolean = false,
)

/**
 * The service's per-update decisions, free of Android so they can be unit-tested. Only the [notices]
 * the user has on fire; [remindBeforeS] is when the time-left alert goes.
 */
class MonitorTracker(
    private val notices: () -> Set<Notice> = { Notice.entries.toSet() }, private val remindBeforeS: () -> Int = { 0 },
) {
    private var prev: Int? = null
    private var shown: List<Any?>? = null
    /** The last job file seen; the printer clears it once the job ends, before the alert names it. */
    var file = ""
        private set
    private var task = ""
    // Armed only once the job is seen before that moment, so a monitor started late doesn't send stale alerts.
    private var timeLeftArmed = false
    private var firstLayerArmed = false

    /** [thumbnail]: whether the job's picture has arrived, which the ongoing notification shows. */
    fun next(st: Status?, conn: Boolean, thumbnail: Boolean = false): MonitorStep {
        val code = st?.print?.status
        // A new job starts nameless; don't let it inherit the last one's name.
        st?.print?.taskId?.takeIf { it.isNotEmpty() && it != task }?.let { task = it; file = "" }
        st?.print?.file?.takeIf { it.isNotEmpty() }?.let { file = it }
        val before = prev
        val on = notices()
        val change = if (code != null && before != null) alertFor(before, code) else null
        val alert = change?.takeIf { noticeFor(it) in on }
        if (code != null) prev = code
        val p = st?.print
        val remind = if (Notice.TIME_LEFT in on) remindBeforeS() else 0
        var timeLeft = false
        if (p != null && remind > 0 && p.totalS > 0) {
            if (p.totalS - p.elapsedS > remind) timeLeftArmed = true
            else if (timeLeftArmed && code == PrintState.PRINTING) { timeLeftArmed = false; timeLeft = true }
        }
        var firstLayer = false
        if (p != null) {
            if (p.layer < FIRST_LAYER_CHECK) firstLayerArmed = true
            else if (firstLayerArmed && code == PrintState.PRINTING) { firstLayerArmed = false; firstLayer = true }
        }
        val step = MonitorStep(
            alert, timeLeft = timeLeft, firstLayer = firstLayer && Notice.FIRST_LAYER in on, finished = change == R.string.alert_finished,
        )
        if (st != null && conn && st.idle) return step.copy(stop = true)
        // Status arrives every couple of seconds; only re-post when something visible changed,
        // so the notification isn't rate-limited.
        val visible = listOf(conn, code, p?.progress, p?.layer, p?.file, st?.light, thumbnail)
        if (visible == shown) return step
        shown = visible
        return step.copy(repost = true)
    }
}

/**
 * Keeps the printer connection while a print runs and mirrors it in the notification shade:
 * an ongoing progress notification, plus an alert when the job finishes, pauses, stops or fails.
 * Started by the UI when it sees an active job; stops itself once the printer is idle.
 */
class PrintMonitorService : Service() {
    private var client: PrinterLink? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = Hub.client.value
        createChannels(this)
        // startForeground must always be called promptly after startForegroundService.
        val first = ongoing(this, c, c?.status?.value, c?.connected?.value == true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(ONGOING_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ONGOING_ID, first)
        if (c == null || !Hub.notifications.value) { stopSelf(); return START_NOT_STICKY }
        if (client !== c) {
            if (client == null) holdWidgets()
            client?.stop()
            client = c.also { it.start() }
            watcher?.cancel()
            // A throw inside the watch would leave a frozen notification holding the connection: stop instead.
            watcher = Hub.scope.launch {
                runCatching { watch(c) }.onFailure { if (it is CancellationException) throw it; Log.w(Hub.TAG, "monitor failed", it); stopSelf() }
            }
        }
        // Not sticky: a restart after process death would come from the background, where
        // startForeground is forbidden. The UI starts the service again when it sees a job.
        return START_NOT_STICKY
    }

    private suspend fun watch(c: PrinterLink) = coroutineScope {
        val nm = getSystemService(NotificationManager::class.java)
        // Switching or forgetting the printer shuts this client down; stop watching a dead one.
        launch { Hub.client.first { it !== c }; stopSelf() }
        val track = MonitorTracker({ Hub.notices.value }, { Hub.remindBefore.value * 60 })
        var giveUp: Job? = null
        var last: Status? = null
        var lookup: Job? = null
        var stopping: Job? = null
        combine(c.status, c.connected, c.thumbnail) { st, conn, thumb -> Triple(st, conn, thumb != null) }.onEach { (st, conn, thumb) ->
            if (st != null) last = st
            // The last finish's camera frame is kept for its "Complete" card until the next job runs.
            if (st != null && !st.idle) Hub.finishFrame.value = null
            // An unreachable printer shouldn't hold a foreground service (and the battery) forever.
            if (conn) { giveUp?.cancel(); giveUp = null }
            else if (giveUp == null) giveUp = launch {
                delay(OFFLINE_GIVE_UP_MS)
                // Giving up mid-print: say so, or a failed print would go unnoticed. Tapping it reopens the
                // app, which restarts the monitor once it sees the printer again.
                // ponytail: no periodic background probe (WorkManager isn't a dependency); add one if users ask.
                last?.takeIf { !it.idle && Notice.LOST in Hub.notices.value }?.let { nm.notify(ALERT_ID, lostAlert(this@PrintMonitorService, c, it)) }
                stopSelf()
            }

            val step = track.next(st, conn, thumb)
            if (step.finished) setPrintAgainShortcut(this@PrintMonitorService, track.file)
            if (st != null && step.timeLeft) nm.notify(TIME_LEFT_ID, timeLeftAlert(this@PrintMonitorService, st, track.file))
            if (st != null && step.firstLayer) {
                val file = track.file
                launch {
                    val picture = snapshot(this@PrintMonitorService, c, Hub.http)
                    // The camera can take seconds: don't say "check it's sticking" after the job failed or stopped.
                    if (stillRunning(st, c.status.value)) nm.notify(FIRST_LAYER_ID, firstLayerAlert(this@PrintMonitorService, file, picture))
                }
            }
            // A first-layer alert's Stop would be about a job that has moved on.
            if (step.alert != null || step.stop) nm.cancel(FIRST_LAYER_ID)
            if (st != null) step.alert?.let { title ->
                val file = track.file
                var picture: Bitmap? = null
                var reason: String? = null
                val post = { quiet: Boolean ->
                    nm.notify(ALERT_ID, alert(this@PrintMonitorService, st, title, file, picture, reason, quiet))
                }
                post(false)
                // A camera frame, and why it stopped (runout, jam, came loose…, only in the job's history
                // details), arrive later and fill in the alert without buzzing again. A newer alert replaces them.
                lookup?.cancel()
                lookup = launch {
                    launch {
                        val lit = title == R.string.alert_finished || title == R.string.alert_error || title == R.string.alert_unload_error
                        if (lit && lightForSnapshot(c, st)) delay(LIGHT_SETTLE_MS) // the camera's exposure catches up
                        snapshot(this@PrintMonitorService, c, Hub.http)?.let {
                            picture = it
                            post(true)
                            // The Status screen's "Complete" card shows it too.
                            if (title == R.string.alert_finished) Hub.finishFrame.value = st.print.taskId to it
                        }
                    }
                    if (title != R.string.alert_finished && st.print.taskId.isNotEmpty()) {
                        val code = orNull { c.taskDetails(st.print.taskId) }?.errorReason ?: 0
                        errorReasonLabel(resources, code)?.let { reason = it; post(true) }
                    }
                }
            }
            // Stopping cancels this scope: let the picture and "why it stopped" finish first.
            if (step.stop) { if (stopping == null) stopping = launch { lookup?.join(); stopSelf() }; return@onEach }
            // A new print started meanwhile: keep watching.
            stopping?.cancel()
            stopping = null
            if (step.repost) {
                nm.notify(ONGOING_ID, ongoing(this@PrintMonitorService, c, st, conn, track.file))
                // While the app is on screen it paints the widgets itself (followOnWidgets).
                if (widgetHolders == 1) updateWidgets(this@PrintMonitorService, c, st, conn)
            }
        }.launchIn(this)
    }

    override fun onDestroy() {
        client?.let { releaseWidgets(this, it) }
        watcher?.cancel()
        client?.stop()
        client = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, PrintMonitorService::class.java))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, PrintMonitorService::class.java))
    }
}

/**
 * One channel per kind of event, so each can be silenced or tuned in the system's settings. Made at start-up
 * too ([Hub.init]): the system's settings page lists only channels that exist.
 */
internal fun createChannels(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    Notice.entries.forEach {
        val importance = if (it == Notice.PROGRESS) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH
        nm.createNotificationChannel(NotificationChannel(it.channel, ctx.getString(it.label), importance))
    }
}

/** Opens the Status tab: the notifications' and widgets' tap. */
internal fun openApp(ctx: Context): PendingIntent =
    PendingIntent.getActivity(ctx, 0, openIntent(ctx, Destination.STATUS), PendingIntent.FLAG_IMMUTABLE)

/** A notification button, by default handled by [PrintActionReceiver]; [auth] needs the phone unlocked. */
private fun action(ctx: Context, what: String, label: Int, icon: Int, auth: Boolean = false, intent: PendingIntent = actionIntent(ctx, what)) =
    NotificationCompat.Action.Builder(icon, ctx.getString(label), intent).setAuthenticationRequired(auth).build()

/** Stop opens the app's own confirmation ([Destination.STOP]), only with the phone unlocked: one tap in the shade can't end a print. */
private fun stopAction(ctx: Context) = NotificationCompat.Action.Builder(
    R.drawable.ic_cancel, ctx.getString(R.string.stop),
    PendingIntent.getActivity(ctx, 0, openIntent(ctx, Destination.STOP), PendingIntent.FLAG_IMMUTABLE),
).setAuthenticationRequired(true).build()

/** The app's confirmed Stop; once sent, the first-layer alert asking whether to stop is moot. */
fun confirmedStop(ctx: Context, c: PrinterLink): Boolean =
    c.stopPrint().also { if (it) ctx.getSystemService(NotificationManager::class.java).cancel(FIRST_LAYER_ID) }

/** Pause or Resume, the light, and Stop. */
private fun printActions(ctx: Context, st: Status) = listOfNotNull(
    when (st.print.status) {
        PrintState.PRINTING -> action(ctx, ACTION_PAUSE, R.string.pause, R.drawable.ic_pause)
        PrintState.PAUSED -> action(ctx, ACTION_RESUME, R.string.resume, R.drawable.ic_play)
        else -> null
    },
    action(ctx, ACTION_LIGHT, if (st.light) R.string.action_light_off else R.string.action_light_on, R.drawable.ic_lightbulb),
    stopAction(ctx).takeUnless { printStatusIsTransition(st.print.status) },
)

/**
 * Where a job is on the Live Update bar (0–100): preheating, then levelling, then the print itself. The
 * steps are the Status screen's ([printStep]): the bar fills a segment once the next step starts.
 */
fun liveProgress(p: PrintInfo): Int = when (p.status) {
    PrintState.HOMING, PrintState.HOMING_DONE, PrintState.CHECKING_FILE, PrintState.CHECKING_PRINTER -> 0
    PrintState.RESONANCE_TESTING, PrintState.RESONANCE_DONE -> LIVE_PREHEAT
    else -> when (printStep(p.status)) {
        0 -> 0
        1 -> LIVE_PREHEAT
        else -> LIVE_PREHEAT + LIVE_LEVEL + p.progress.coerceIn(0, 100) * LIVE_PRINT / 100
    }
}

/**
 * Android 16's Live Update: the same notification with a segmented progress bar and the time left as the
 * status-bar chip, asking to be promoted to the top of the shade and the lock screen.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private fun liveUpdate(ctx: Context, n: Notification, p: PrintInfo): Notification {
    val style = Notification.ProgressStyle()
        .setProgressSegments(listOf(LIVE_PREHEAT, LIVE_LEVEL, LIVE_PRINT).map { Notification.ProgressStyle.Segment(it) })
        .setProgress(liveProgress(p))
        .setProgressTrackerIcon(Icon.createWithResource(ctx, R.drawable.ic_printer))
    val b = Notification.Builder.recoverBuilder(ctx, n).setStyle(style)
    if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) b.setRequestPromotedOngoing(true)
    else b.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
    if (p.status == PrintState.PRINTING && p.totalS > 0) b.setShortCriticalText(formatDuration(ctx.resources, p.totalS - p.elapsedS))
    return b.build()
}

/**
 * The foreground service's notification: progress, buttons and (Android 16+) a Live Update. With [progress]
 * off it is a plain one-liner instead, since a foreground service can't run without a notification. [file] is
 * the job's, which an error can blank in the status.
 */
internal fun ongoing(
    ctx: Context, c: PrinterLink?, st: Status?, connected: Boolean, file: String = st?.print?.file.orEmpty(),
    progress: Boolean = Notice.PROGRESS in Hub.notices.value,
): Notification {
    val b = NotificationCompat.Builder(ctx, Notice.PROGRESS.channel)
        .setSmallIcon(R.drawable.ic_printer)
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp(ctx))
    val name = c?.displayName(ctx.resources) ?: ctx.getString(R.string.default_printer_name)
    if (!progress) return b.setContentTitle(name).setContentText(ctx.getString(R.string.notif_watching)).build()
    val p = st?.print
    if (!connected || p == null) return b.setContentTitle(name).setContentText(ctx.getString(R.string.notif_reconnecting))
        .setProgress(0, 0, true).build()
    val job = jobName(file).title.ifEmpty { printStatusLabel(ctx.resources, p.status) }
    b.setContentTitle(ctx.getString(R.string.notif_title, job, p.progress))
        .setContentText(
            if (p.status == PrintState.PRINTING) ctx.getString(R.string.notif_text, finishClock(ctx, st), p.layer, p.totalLayers)
            else printStatusLabel(ctx.resources, p.status)
        )
        .setProgress(100, p.progress, false)
    c?.thumbnail?.value?.let(b::setLargeIcon)
    printActions(ctx, st).forEach(b::addAction)
    val n = b.build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) liveUpdate(ctx, n, p) else n
}

/**
 * Lights a dark chamber for a finish or error photo (true when it did) and switches it back off after [SNAPSHOT_LIGHT_MS],
 * unless someone already did. Holds the connection meanwhile: the service stops once the printer is idle.
 */
internal fun lightForSnapshot(c: PrinterLink, st: Status): Boolean {
    if (st.light || !c.setLight(true)) return false
    c.start()
    Hub.scope.launch {
        try {
            delay(SNAPSHOT_LIGHT_MS)
            if (c.status.value?.light == true) c.setLight(false)
        } finally { c.stop() }
    }
    return true
}

/**
 * One camera frame for an alert; null when the camera refuses or doesn't answer in time. While the app's camera
 * view streams it is that view's frame: a printer allowing one viewer would refuse a second stream.
 */
internal suspend fun snapshot(ctx: Context, c: PrinterLink, http: OkHttpClient): Bitmap? {
    // The demo's bundled photo: half size is already under the cap, so it is cheap enough for this thread.
    c.stillFrame?.let { return BitmapFactory.decodeResource(ctx.resources, it, BitmapFactory.Options().apply { inSampleSize = 2 }) }
    val frame = Hub.liveFrame ?: run {
        c.video(true)
        try {
            orNull { withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { mjpegFrames(http, c.videoUrl.filterNotNull().first()).first() } }
        } finally {
            c.video(false)
        }
    }
    // Full-size frames can overflow the notification's binder transaction; scaling them is too slow for the UI thread.
    return frame?.takeIf { it.width > SNAPSHOT_MAX_WIDTH }
        ?.let { withContext(Dispatchers.Default) { it.scale(SNAPSHOT_MAX_WIDTH, it.height * SNAPSHOT_MAX_WIDTH / it.width) } }
        ?: frame
}

/**
 * A status-change alert; [reason] (once fetched) replaces the text. [file] is the job's, which the
 * status may already have cleared; [picture] is a camera frame. A [quiet] update doesn't buzz again.
 */
internal fun alert(
    ctx: Context, st: Status, title: Int, file: String = st.print.file, picture: Bitmap? = null, reason: String? = null,
    quiet: Boolean = reason != null,
): Notification {
    val job = jobName(file).title
    val text = when {
        reason != null -> ctx.getString(R.string.alert_reason_body, reason, job)
        title == R.string.alert_finished ->
            ctx.getString(R.string.alert_finished_body, job, formatDuration(ctx.resources, st.print.elapsedS))
        title == R.string.alert_error -> ctx.getString(R.string.alert_error_body, job)
        else -> job
    }
    val b = alertBuilder(ctx, ctx.getString(title), text, noticeFor(title).channel).setOnlyAlertOnce(quiet)
    picture?.let { b.setStyle(pictureStyle(it, text)) }
    return b.build()
}

// One copy of the picture: collapsed, it shows as the thumbnail.
private fun pictureStyle(picture: Bitmap, text: String) =
    NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(text).showBigPictureWhenCollapsed(true)

internal fun lostAlert(ctx: Context, c: PrinterLink, st: Status): Notification = alertBuilder(
    ctx, ctx.getString(R.string.alert_lost),
    ctx.getString(R.string.alert_lost_body, c.displayName(ctx.resources), jobName(st.print.file).title), Notice.LOST.channel,
).build()

/** "N minutes left", once per job when the user asked for it. */
internal fun timeLeftAlert(ctx: Context, st: Status, file: String): Notification = alertBuilder(
    ctx, ctx.getString(R.string.alert_time_left, formatDuration(ctx.resources, st.print.totalS - st.print.elapsedS)),
    ctx.getString(R.string.alert_time_left_body, jobName(file).title, finishClock(ctx, st)), Notice.TIME_LEFT.channel,
).build()

/** After the first layer: a camera frame to check it sticks, with Stop at hand. */
internal fun firstLayerAlert(ctx: Context, file: String, picture: Bitmap?): Notification {
    val text = ctx.getString(R.string.alert_first_layer_body, jobName(file).title)
    val b = alertBuilder(ctx, ctx.getString(R.string.alert_first_layer), text, Notice.FIRST_LAYER.channel)
        .addAction(action(ctx, ACTION_LOOKS_GOOD, R.string.action_looks_good, R.drawable.ic_check_circle))
        .addAction(stopAction(ctx))
    picture?.let { b.setStyle(pictureStyle(it, text)) }
    return b.build()
}

/**
 * Runs the notification buttons. Commands go to the shared printer connection; one that can't be sent
 * says so, as in the app. The printer's answer shows up in the ongoing notification.
 */
class PrintActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (intent.action == ACTION_LOOKS_GOOD) return nm.cancel(FIRST_LAYER_ID)
        val c = Hub.client.value
        val sent = c != null && when (intent.action) {
            ACTION_PAUSE -> c.pause()
            ACTION_RESUME -> c.resume()
            ACTION_LIGHT -> c.setLight(c.status.value?.light != true)
            else -> true
        }
        if (!sent) Toast.makeText(ctx, R.string.not_sent, Toast.LENGTH_SHORT).show()
    }
}

private fun alertBuilder(ctx: Context, title: String, text: String, channel: String) = NotificationCompat.Builder(ctx, channel)
    .setSmallIcon(R.drawable.ic_printer)
    .setContentTitle(title).setContentText(text)
    .setAutoCancel(true)
    .setContentIntent(openApp(ctx))
