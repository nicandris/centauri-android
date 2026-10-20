package app.centauri.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Date

private const val CH_PROGRESS = "progress"
private const val CH_ALERTS = "alerts"
private const val ONGOING_ID = 1
private const val ALERT_ID = 2
private const val OFFLINE_GIVE_UP_MS = 5 * 60_000L

/** Wall-clock time the job should end, in the phone's 12/24 h format. */
fun finishClock(ctx: Context, st: Status): String {
    val remaining = (st.print.totalS - st.print.elapsedS).coerceAtLeast(0)
    return DateFormat.getTimeFormat(ctx).format(Date(System.currentTimeMillis() + remaining * 1000L))
}

/**
 * Keeps the printer connection while a print runs and mirrors it in the notification shade:
 * an ongoing progress notification, plus an alert when the job finishes, pauses, stops or fails.
 * Started by the UI when it sees an active job; stops itself once the printer is idle.
 */
class PrintMonitorService : Service() {
    private var client: SdcpClient? = null
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

    private suspend fun watch(c: SdcpClient) = coroutineScope {
        val nm = getSystemService(NotificationManager::class.java)
        var prev: Int? = null
        var giveUp: Job? = null
        combine(c.status, c.connected) { st, conn -> st to conn }.collect { (st, conn) ->
            // An unreachable printer shouldn't hold a foreground service (and the battery) forever.
            if (conn) { giveUp?.cancel(); giveUp = null }
            else if (giveUp == null) giveUp = launch { delay(OFFLINE_GIVE_UP_MS); stopSelf() }

            val code = st?.print?.status
            if (st != null && prev != null && code != prev) alert(this@PrintMonitorService, st, prev!!)?.let { nm.notify(ALERT_ID, it) }
            if (code != null) prev = code
            if (st != null && conn && st.idle) stopSelf()
            else nm.notify(ONGOING_ID, ongoing(this@PrintMonitorService, c, st, conn))
        }
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

private fun ongoing(ctx: Context, c: SdcpClient?, st: Status?, connected: Boolean): Notification {
    val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
        .setSmallIcon(R.drawable.ic_printer)
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp(ctx))
    val name = c?.printer?.name?.ifEmpty { null } ?: ctx.getString(R.string.default_printer_name)
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

private fun alert(ctx: Context, st: Status, prev: Int): Notification? {
    val job = jobName(st.print.file).title
    val (title, text) = when (st.print.status) {
        PrintState.COMPLETE -> R.string.alert_finished to ctx.getString(R.string.alert_finished_body, job, hm(st.print.elapsedS))
        PrintState.ERROR -> R.string.alert_error to ctx.getString(R.string.alert_error_body, job)
        PrintState.UNLOAD_ERROR -> R.string.alert_unload_error to job
        PrintState.PAUSED ->
            if (prev == PrintState.PAUSING || prev == PrintState.PRINTING) R.string.alert_paused to job else return null
        PrintState.STOPPED -> R.string.alert_stopped to job
        else -> return null
    }
    return NotificationCompat.Builder(ctx, CH_ALERTS)
        .setSmallIcon(R.drawable.ic_printer)
        .setContentTitle(ctx.getString(title)).setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(openApp(ctx))
        .build()
}
