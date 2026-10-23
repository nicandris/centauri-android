package app.centauri.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.Date

private const val CH_PROGRESS = "progress"
private const val CH_ALERTS = "alerts"
private const val ONGOING_ID = 1
private const val ALERT_ID = 2
private const val OFFLINE_GIVE_UP_MS = 5 * 60_000L
private const val SNAPSHOT_TIMEOUT_MS = 10_000L
private const val SNAPSHOT_MAX_WIDTH = 960

/** Wall-clock time the job should end, in the phone's 12/24 h format. */
fun finishClock(ctx: Context, st: Status): String {
    val remaining = (st.print.totalS - st.print.elapsedS).coerceAtLeast(0)
    return DateFormat.getTimeFormat(ctx).format(Date(System.currentTimeMillis() + remaining * 1000L))
}

/** What the UI does about the print monitor. */
enum class MonitorAction { STOP, NONE, ASK_PERMISSION, START }

/** Notifications off stop it; a running print starts it, asking for the notification permission first if needed. */
fun monitorAction(notify: Boolean, active: Boolean, needsPermission: Boolean) = when {
    !notify -> MonitorAction.STOP
    !active -> MonitorAction.NONE
    needsPermission -> MonitorAction.ASK_PERMISSION
    else -> MonitorAction.START
}

/** What [PrintMonitorService] does with one status update. */
data class MonitorStep(val alert: Int? = null, val stop: Boolean = false, val repost: Boolean = false)

/** The service's per-update decisions, free of Android so they can be unit-tested. */
class MonitorTracker {
    private var prev: Int? = null
    private var shown: List<Any?>? = null
    /** The last job file seen; the printer clears it once the job ends, before the alert names it. */
    var file = ""
        private set

    fun next(st: Status?, conn: Boolean): MonitorStep {
        val code = st?.print?.status
        st?.print?.file?.takeIf { it.isNotEmpty() }?.let { file = it }
        val before = prev
        val alert = if (code != null && before != null) alertFor(before, code) else null
        if (code != null) prev = code
        if (st != null && conn && st.idle) return MonitorStep(alert, stop = true)
        // Status arrives every couple of seconds; only re-post when something visible changed,
        // so the notification isn't rate-limited.
        val visible = listOf(conn, code, st?.print?.progress, st?.print?.layer, st?.print?.file)
        if (visible == shown) return MonitorStep(alert)
        shown = visible
        return MonitorStep(alert, repost = true)
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
            client?.stop()
            client = c.also { it.start() }
            watcher?.cancel()
            watcher = Hub.scope.launch { watch(c) }
        }
        // Not sticky: a restart after process death would come from the background, where
        // Android 12+ forbids startForeground. The UI starts the service again when it sees a job.
        return START_NOT_STICKY
    }

    private suspend fun watch(c: PrinterLink) = coroutineScope {
        val nm = getSystemService(NotificationManager::class.java)
        // Switching or forgetting the printer shuts this client down; stop watching a dead one.
        launch { Hub.client.first { it !== c }; stopSelf() }
        val track = MonitorTracker()
        var giveUp: Job? = null
        var last: Status? = null
        var lookup: Job? = null
        var stopping: Job? = null
        combine(c.status, c.connected) { st, conn -> st to conn }.onEach { (st, conn) ->
            if (st != null) last = st
            // An unreachable printer shouldn't hold a foreground service (and the battery) forever.
            if (conn) { giveUp?.cancel(); giveUp = null }
            else if (giveUp == null) giveUp = launch {
                delay(OFFLINE_GIVE_UP_MS)
                // Giving up mid-print: say so, or a failed print would go unnoticed.
                last?.takeIf { !it.idle }?.let { nm.notify(ALERT_ID, lostAlert(this@PrintMonitorService, c, it)) }
                stopSelf()
            }

            val step = track.next(st, conn)
            if (st != null) step.alert?.let { title ->
                // Waits for the camera (seconds at most), so the alert buzzes once, with its picture.
                val picture = snapshot(this@PrintMonitorService, c, Hub.http)
                val file = track.file
                nm.notify(ALERT_ID, alert(this@PrintMonitorService, st, title, file, picture))
                // Why it stopped (runout, jam, came loose…) is only in the job's history details.
                if (title != R.string.alert_finished && st.print.taskId.isNotEmpty()) lookup = launch {
                    val code = orNull { c.taskDetails(st.print.taskId) }?.errorReason ?: 0
                    errorReasonLabel(resources, code)?.let {
                        nm.notify(ALERT_ID, alert(this@PrintMonitorService, st, title, file, picture, reason = it))
                    }
                }
            }
            // Stopping cancels this scope: let a "why it stopped" lookup finish first.
            if (step.stop) { if (stopping == null) stopping = launch { lookup?.join(); stopSelf() }; return@onEach }
            // A new print started meanwhile: keep watching.
            stopping?.cancel()
            stopping = null
            if (step.repost) nm.notify(ONGOING_ID, ongoing(this@PrintMonitorService, c, st, conn))
        }.launchIn(this)
    }

    override fun onDestroy() {
        watcher?.cancel()
        client?.stop()
        client = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, PrintMonitorService::class.java))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, PrintMonitorService::class.java))
    }
}

private fun createChannels(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CH_PROGRESS, ctx.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW),
    )
    nm.createNotificationChannel(
        NotificationChannel(CH_ALERTS, ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
    )
}

private fun openApp(ctx: Context) = PendingIntent.getActivity(
    ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE,
)

internal fun ongoing(ctx: Context, c: PrinterLink?, st: Status?, connected: Boolean): Notification {
    val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
        .setSmallIcon(R.drawable.ic_printer)
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp(ctx))
    val name = c?.displayName(ctx.resources) ?: ctx.getString(R.string.default_printer_name)
    val p = st?.print
    return when {
        !connected || p == null -> b.setContentTitle(name).setContentText(ctx.getString(R.string.notif_reconnecting))
            .setProgress(0, 0, true).build()
        else -> b.setContentTitle(ctx.getString(R.string.notif_title, jobName(p.file).title, p.progress))
            .setContentText(
                if (p.status == PrintState.PRINTING) ctx.getString(R.string.notif_text, finishClock(ctx, st), p.layer, p.totalLayers)
                else printStatusLabel(ctx.resources, p.status)
            )
            .setProgress(100, p.progress, false).build()
    }
}

/** One camera frame for an alert; null when the camera refuses or doesn't answer in time. */
internal suspend fun snapshot(ctx: Context, c: PrinterLink, http: OkHttpClient): Bitmap? {
    c.stillFrame?.let { return BitmapFactory.decodeResource(ctx.resources, it) }
    c.video(true)
    val frame = try {
        withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { mjpegFrames(http, c.videoUrl.filterNotNull().first()).first() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } finally {
        c.video(false)
    }
    // Full-size frames can overflow the notification's binder transaction.
    return frame?.takeIf { it.width > SNAPSHOT_MAX_WIDTH }
        ?.let { Bitmap.createScaledBitmap(it, SNAPSHOT_MAX_WIDTH, it.height * SNAPSHOT_MAX_WIDTH / it.width, true) }
        ?: frame
}

/**
 * A status-change alert; [reason] (once fetched) replaces the text and doesn't buzz again.
 * [file] is the job's, which the status may already have cleared; [picture] is a camera frame.
 */
internal fun alert(
    ctx: Context, st: Status, title: Int, file: String = st.print.file, picture: Bitmap? = null, reason: String? = null,
): Notification {
    val job = jobName(file).title
    val text = when {
        reason != null -> ctx.getString(R.string.alert_reason_body, reason, job)
        title == R.string.alert_finished ->
            ctx.getString(R.string.alert_finished_body, job, formatDuration(ctx.resources, st.print.elapsedS))
        title == R.string.alert_error -> ctx.getString(R.string.alert_error_body, job)
        else -> job
    }
    val b = alertBuilder(ctx, ctx.getString(title), text).setOnlyAlertOnce(reason != null)
    if (picture != null) b.setLargeIcon(picture)
        .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?).setSummaryText(text))
    return b.build()
}

internal fun lostAlert(ctx: Context, c: PrinterLink, st: Status): Notification = alertBuilder(
    ctx, ctx.getString(R.string.alert_lost),
    ctx.getString(R.string.alert_lost_body, c.displayName(ctx.resources), jobName(st.print.file).title),
).build()

private fun alertBuilder(ctx: Context, title: String, text: String) = NotificationCompat.Builder(ctx, CH_ALERTS)
    .setSmallIcon(R.drawable.ic_printer)
    .setContentTitle(title).setContentText(text)
    .setPriority(NotificationCompat.PRIORITY_HIGH)
    .setAutoCancel(true)
    .setContentIntent(openApp(ctx))
