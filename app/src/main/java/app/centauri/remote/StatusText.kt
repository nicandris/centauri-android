package app.centauri.remote

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Environment
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

// What the top bar and the Status screen say and do, kept out of the composables so it can be unit-tested.

/** The line under the printer's name on every tab: "Printing · 36 %", "Reconnecting…". */
fun connectionLine(res: Resources, connected: Boolean, st: Status?): String {
    val p = st?.print
    return when {
        p == null -> res.getString(R.string.conn_connecting)
        !connected -> res.getString(R.string.conn_reconnecting)
        !showsJob(p.file, p.status) -> if (st.machine == 0) res.getString(R.string.ready_to_print) else machineStatusLabel(res, st.machine)
        st.idle -> printStatusLabel(res, p.status)
        else -> res.getString(R.string.conn_progress, printStatusLabel(res, p.status), p.progress)
    }
}

/** Where a job is in its start-up, as an index into Heat → Level → Print; null outside those steps. */
fun printStep(status: Int): Int? = when (status) {
    PrintState.PREHEATING, PrintState.PREHEATING_DONE -> 0
    PrintState.AUTO_LEVELING, PrintState.LEVELING_DONE -> 1
    else -> null
}

/** What to do about an error or pause [reason] (ErrorStatusReason, null or 0 when unknown). */
@StringRes
fun whatToDo(reason: Int?): Int = when (reason) {
    3, 45 -> R.string.todo_runout
    6 -> R.string.todo_jam
    18 -> R.string.todo_detached
    1, 33, 34 -> R.string.todo_heat
    else -> R.string.todo_generic
}

/** Below this much time left, the finish is said as a duration ("in 42m") rather than a clock time. */
const val RELATIVE_ETA_S = 3600

/**
 * When the job ends: "Finishes in 42m" when close, else the time [clock] formats, with "tomorrow"
 * or the weekday once the end is past midnight.
 */
fun finishWhen(res: Resources, now: ZonedDateTime, remainingS: Int, clock: (ZonedDateTime) -> String): String {
    val s = remainingS.coerceAtLeast(0)
    if (s < RELATIVE_ETA_S) return res.getString(R.string.finishes_in, formatDuration(res, s.coerceAtLeast(60)))
    val end = now.plusSeconds(s.toLong())
    return when (ChronoUnit.DAYS.between(now.toLocalDate(), end.toLocalDate())) {
        0L -> res.getString(R.string.finishes_at, clock(end))
        1L -> res.getString(R.string.finishes_tomorrow, clock(end))
        else -> res.getString(
            R.string.finishes_on, end.dayOfWeek.getDisplayName(TextStyle.FULL, res.configuration.locales[0]), clock(end),
        )
    }
}

/** A calendar entry at the job's finish, for the calendar app to confirm. */
fun calendarIntent(title: String, endMs: Long): Intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, endMs)
    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs + 15 * 60_000L)
    .putExtra(CalendarContract.Events.TITLE, title)

/** The alarm intent sets a time of day, which rings within 24 h: a later finish can only go in the calendar. */
fun alarmFits(remainingS: Int) = remainingS < 24 * 3600

/** An alarm at the job's finish; the clock app shows it before saving. */
fun alarmIntent(message: String, end: ZonedDateTime): Intent = Intent(AlarmClock.ACTION_SET_ALARM)
    .putExtra(AlarmClock.EXTRA_HOUR, end.hour)
    .putExtra(AlarmClock.EXTRA_MINUTES, end.minute)
    .putExtra(AlarmClock.EXTRA_MESSAGE, message)

/** The last [n] different jobs, newest first, for one-tap reprints. */
fun recentJobs(history: List<HistoryEntry>, n: Int = 3): List<HistoryEntry> = history.distinctBy { it.path }.take(n)

/** "Last print: benchy · Completed", or why it failed ("· Filament ran out"). */
fun lastOutcome(res: Resources, e: HistoryEntry): String = res.getString(
    R.string.last_print, jobName(e.path).title,
    when (e.outcome) {
        Outcome.DONE -> res.getString(R.string.completed)
        Outcome.STOPPED -> res.getString(R.string.cancelled)
        Outcome.FAILED -> errorReasonLabel(res, e.errorReason) ?: res.getString(R.string.outcome_failed)
    },
)

/** How far a picture zoomed to [scale] may move along one axis of [size] and still cover it. */
fun clampPan(pan: Float, scale: Float, size: Float): Float {
    val max = (scale - 1f).coerceAtLeast(0f) * size / 2
    return pan.coerceIn(-max, max)
}

/** Saves [frame] as a JPEG in Pictures/Centauri (MediaStore needs no permission for that); false if it failed. */
suspend fun saveFrame(ctx: Context, frame: Bitmap, name: String): Boolean = withContext(Dispatchers.IO) {
    val cr = ctx.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Centauri")
        put(MediaStore.Images.Media.IS_PENDING, 1) // hidden from the gallery until written
    }
    val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
    val written = runCatching { cr.openOutputStream(uri)?.use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) } == true }
        .getOrDefault(false)
    if (written) cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    else cr.delete(uri, null, null)
    written
}

/** The status names the job's file without its folder (the demo, and possibly firmware); the printer's own files live under /local. */
fun localPath(file: String) = if (file.startsWith("/")) file else "/local/$file"

/** The print speed as the Control tab and the Status chip say it: "Sport · 130%", or "Running at 115% speed" off the presets. */
fun speedLabel(res: Resources, pct: Int): String =
    SPEED_PRESETS.firstOrNull { it.first == pct }?.let { res.getString(R.string.speed_option, res.getString(it.second), pct) }
        ?: res.getString(R.string.running_at_speed, pct)
