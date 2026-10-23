package app.centauri.remote

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// Everything outside the app's own window: home-screen widgets, the Quick Settings tile, launcher shortcuts.

/**
 * Where a notification, widget, tile or launcher shortcut opens the app ([EXTRA_OPEN]). [STOP] is the
 * notifications' Stop: the Status tab with the Stop confirmation up, so the shade can't stop a print in one tap.
 */
enum class Destination { STATUS, CAMERA, FILES, REPRINT, STOP }

/** A [Destination] name; launcher shortcuts (res/xml/shortcuts.xml) spell it out too. */
const val EXTRA_OPEN = "app.centauri.remote.OPEN"
/** The file a [Destination.REPRINT] opens the start form for. */
const val EXTRA_FILE = "app.centauri.remote.FILE"
private const val PRINT_AGAIN_ID = "print_again"

fun destinationOf(i: Intent) = Destination.entries.firstOrNull { it.name == i.getStringExtra(EXTRA_OPEN) }

/** Its action is [to]'s name: PendingIntents that differ only in extras would be one and the same to the system. */
fun openIntent(ctx: Context, to: Destination): Intent =
    Intent(ctx, MainActivity::class.java).setAction(to.name).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_OPEN, to.name)

/** A button on the notification or the widget, run by [PrintActionReceiver]. */
fun actionIntent(ctx: Context, what: String): PendingIntent =
    PendingIntent.getBroadcast(ctx, 0, Intent(ctx, PrintActionReceiver::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE)

/**
 * What the widgets show: [pause] is the Pause or Resume button (action to label), when one applies; [light]
 * the light button's label, null when nothing holds the connection to send it.
 */
data class WidgetContent(
    val title: String, val line: String, val progress: Int?, val pause: Pair<String, Int>?, @StringRes val light: Int?,
)

/** Null while nothing is known, and the widget says to open the app. Without [controls] it has no buttons. */
fun widgetContent(
    res: Resources, name: String, st: Status?, connected: Boolean, finish: () -> String, controls: Boolean = true,
): WidgetContent? {
    if (st == null || !connected) return null
    val p = st.print
    val light = (if (st.light) R.string.action_light_off else R.string.action_light_on).takeIf { controls }
    if (!showsJob(p.file, p.status)) {
        // As the top bar says it: homing, levelling… before the job is named.
        val line = if (st.machine == 0) res.getString(R.string.ready_to_print) else machineStatusLabel(res, st.machine)
        return WidgetContent(name, line, null, null, light)
    }
    val line = listOfNotNull(
        printStatusLabel(res, p.status), res.getString(R.string.progress_percent, p.progress),
        res.getString(R.string.finishes_at, finish()).takeIf { p.status == PrintState.PRINTING && p.totalS > 0 },
    ).joinToString(" · ")
    val pause = when (p.status) {
        PrintState.PRINTING -> ACTION_PAUSE to R.string.pause
        PrintState.PAUSED -> ACTION_RESUME to R.string.resume
        else -> null
    }?.takeIf { controls }
    // An error can blank the file name; the state is the title then.
    return WidgetContent(jobName(p.file).title.ifEmpty { printStatusLabel(res, p.status) }, line, p.progress, pause, light)
}

/**
 * Redraws every widget. The app while on screen ([followOnWidgets]) and [PrintMonitorService] call it, so the
 * widgets cost nothing while neither runs.
 */
fun updateWidgets(ctx: Context, c: PrinterLink?, st: Status?, connected: Boolean, controls: Boolean = true) {
    val awm = ctx.getSystemService(AppWidgetManager::class.java) ?: return
    // A reconnect blip while something holds the connection keeps the last status, without buttons.
    val content = widgetContentOf(ctx, c, st, connected || widgetHolders > 0, controls && connected)
    listOf(PrintWidget::class.java to R.layout.widget_small, PrintWidgetLarge::class.java to R.layout.widget_large).forEach { (cls, layout) ->
        val ids = awm.getAppWidgetIds(ComponentName(ctx, cls))
        if (ids.isNotEmpty()) awm.updateAppWidget(ids, widgetViews(ctx, layout, content, c?.thumbnail?.value, c?.jobThumbnailRes))
    }
}

private fun widgetContentOf(ctx: Context, c: PrinterLink?, st: Status?, connected: Boolean, controls: Boolean = true) =
    st?.let { s -> widgetContent(ctx.resources, c?.displayName(ctx.resources) ?: "", s, connected, { finishClock(ctx, s) }, controls) }

// The app and the monitor each keep the widgets live while they hold the connection; the last to let go paints
// the ending without buttons, since a tap would find nothing to send it.
internal var widgetHolders = 0 // internal: tests that leave a service running reset it

fun holdWidgets() { widgetHolders++ }

/** The widgets' last word until the next print: how the job ended, or "open the app" when that's unknown. */
fun releaseWidgets(ctx: Context, c: PrinterLink) {
    if (--widgetHolders == 0) updateWidgets(ctx, c, c.status.value?.takeIf { it.idle }, c.connected.value, controls = false)
}

/** The app's side: widgets follow [c] while it is on screen (also with notifications off). Call the result on leaving. */
fun followOnWidgets(ctx: Context, c: PrinterLink): () -> Unit {
    holdWidgets()
    val following = combine(c.name, c.status, c.connected, c.thumbnail) { _, st, conn, thumb -> Triple(st, conn, thumb) }
        // Status arrives every couple of seconds; redraw only when the widget would change.
        .distinctUntilChangedBy { (st, conn, thumb) -> widgetContentOf(ctx, c, st, conn) to thumb }
        .onEach { (st, conn) -> updateWidgets(ctx, c, st, conn) }.launchIn(Hub.scope)
    return { following.cancel(); releaseWidgets(ctx, c) }
}

private fun widgetViews(ctx: Context, layout: Int, w: WidgetContent?, thumb: Bitmap?, thumbRes: Int?) =
    RemoteViews(ctx.packageName, layout).apply {
        fun shown(id: Int, on: Boolean) = setViewVisibility(id, if (on) View.VISIBLE else View.GONE)
        setOnClickPendingIntent(R.id.widget_root, openApp(ctx))
        setTextViewText(R.id.widget_title, w?.title ?: ctx.getString(R.string.widget_connect))
        setTextViewText(R.id.widget_line, w?.line.orEmpty())
        shown(R.id.widget_line, w != null)
        setProgressBar(R.id.widget_progress, 100, w?.progress ?: 0, false)
        shown(R.id.widget_progress, w?.progress != null)
        if (layout != R.layout.widget_large) return@apply
        val job = w?.progress != null
        when {
            job && thumb != null -> setImageViewBitmap(R.id.widget_thumb, thumb)
            job && thumbRes != null -> setImageViewResource(R.id.widget_thumb, thumbRes)
        }
        shown(R.id.widget_thumb, job && (thumb != null || thumbRes != null))
        val pause = w?.pause
        shown(R.id.widget_pause, pause != null)
        pause?.let { (action, label) ->
            setTextViewText(R.id.widget_pause, ctx.getString(label))
            setOnClickPendingIntent(R.id.widget_pause, actionIntent(ctx, action))
        }
        val light = w?.light
        shown(R.id.widget_light, light != null)
        light?.let { setTextViewText(R.id.widget_light, ctx.getString(it)) }
        setOnClickPendingIntent(R.id.widget_light, actionIntent(ctx, ACTION_LIGHT))
    }

/** The compact widget: job, progress, state and finish time. Tapping it opens Status. */
open class PrintWidget : AppWidgetProvider() {
    // Added to the home screen (or the launcher restarted): show what the app already knows, without connecting.
    override fun onUpdate(ctx: Context, awm: AppWidgetManager, ids: IntArray) {
        val c = Hub.client.value
        updateWidgets(ctx, c, c?.status?.value, c?.connected?.value == true)
    }
}

/** The larger widget adds the job's picture and Pause/Resume and Light buttons. */
class PrintWidgetLarge : PrintWidget()

/** The Quick Settings tile's subtitle: the state, with the progress while a job runs. */
fun tileSubtitle(res: Resources, st: Status?, connected: Boolean): String = when {
    st == null || !connected -> res.getString(R.string.not_connected)
    showsJob(st.print.file, st.print.status) -> res.getString(R.string.notif_title, printStatusLabel(res, st.print.status), st.print.progress)
    else -> printStatusLabel(res, st.print.status)
}

/**
 * Quick Settings: the printer's name and state; a tap switches the chamber light, or opens the app
 * while there is no connection. Follows the shared connection only while the panel shows it.
 */
class LightTile : TileService() {
    private var watching: Job? = null

    override fun onStartListening() {
        watching?.cancel()
        watching = Hub.scope.launch {
            Hub.client.collectLatest { c ->
                if (c == null) show(null, null, false)
                else combine(c.name, c.status, c.connected) { _, st, conn -> st to conn }.collect { (st, conn) -> show(c, st, conn) }
            }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
    }

    private fun show(c: PrinterLink?, st: Status?, connected: Boolean) {
        val tile = qsTile ?: return
        val name = c?.displayName(resources) ?: getString(R.string.app_name)
        tile.label = name
        tile.contentDescription = getString(R.string.tile_desc, name)
        tile.state = if (connected && st?.light == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = tileSubtitle(resources, st, connected)
        tile.updateTile()
    }

    override fun onClick() {
        val c = Hub.client.value
        if (c != null && c.connected.value) { c.setLight(c.status.value?.light != true); return }
        val open = openIntent(this, Destination.STATUS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
        // Lint misses the SDK check: only Android 13 takes this path.
        else @SuppressLint("StartActivityAndCollapseDeprecated") @Suppress("DEPRECATION") startActivityAndCollapse(open)
    }
}

/** The launcher's "Print again: benchy", set when a print completes; [file] is the job's file name or path. */
fun setPrintAgainShortcut(ctx: Context, file: String) {
    val title = jobName(file).title
    if (title.isEmpty()) return // a shortcut needs a label
    val open = openIntent(ctx, Destination.REPRINT).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_FILE, localPath(file))
    ShortcutManagerCompat.pushDynamicShortcut(
        ctx,
        ShortcutInfoCompat.Builder(ctx, PRINT_AGAIN_ID).setShortLabel(title)
            .setLongLabel(ctx.getString(R.string.shortcut_print_again, title))
            .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_printer)).setIntent(open).build(),
    )
}
