package app.centauri.remote

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.text.format.Formatter
import androidx.core.net.toUri
import org.json.JSONObject

// What Files, the start form and History work out and say, kept out of the composables so it can be unit-tested.

/** CMD_FILE_LIST roots: the printer's own storage and a USB drive (SDCP v3 spec; USB untested, see Lab.USB_STORAGE). */
const val STORAGE_LOCAL = "/local"
const val STORAGE_USB = "/usb"

/** History TaskStatus (SDCP v3: 0 other, 1 completed, 2 exceptional, 3 stopped). */
const val TASK_COMPLETED = 1
private const val TASK_EXCEPTIONAL = 2

enum class Outcome { DONE, STOPPED, FAILED }

/**
 * How a job ended. The printer records a reason (runout, jam…) for a failure and none for a stop
 * from the touchscreen or this app, so a stopped job with a reason counts as failed.
 */
fun outcomeOf(taskStatus: Int, errorReason: Int) = when {
    taskStatus == TASK_COMPLETED -> Outcome.DONE
    taskStatus == TASK_EXCEPTIONAL || errorReason != 0 -> Outcome.FAILED
    else -> Outcome.STOPPED
}

enum class HistoryFilter { ALL, COMPLETED, FAILED, TIMELAPSES }

fun HistoryEntry.shownUnder(filter: HistoryFilter) = when (filter) {
    HistoryFilter.ALL -> true
    HistoryFilter.COMPLETED -> outcome == Outcome.DONE
    HistoryFilter.FAILED -> outcome == Outcome.FAILED
    HistoryFilter.TIMELAPSES -> timelapseUrl.isNotEmpty()
}

/** History's header: how many prints, how long they ran, how many finished, and grams by filament. */
data class HistoryTotals(val prints: Int, val seconds: Long, val done: Int, val grams: Map<String, Double>)

/**
 * Totals over [entries]. Grams count completed jobs only, from [gramsOf] (details already fetched:
 * no request per entry), by the filament in the file name; jobs whose name or grams are unknown are left out.
 */
fun historyTotals(entries: List<HistoryEntry>, gramsOf: (HistoryEntry) -> Double?): HistoryTotals {
    val grams = linkedMapOf<String, Double>()
    entries.filter { it.outcome == Outcome.DONE }.forEach { e ->
        val filament = jobName(e.path).filament ?: return@forEach
        val g = gramsOf(e) ?: return@forEach
        grams[filament] = (grams[filament] ?: 0.0) + g
    }
    return HistoryTotals(
        prints = entries.size,
        seconds = entries.sumOf { (it.end - it.begin).coerceAtLeast(0) },
        done = entries.count { it.outcome == Outcome.DONE },
        grams = grams,
    )
}

/** "12 prints · 34h 5m · 83% successful", then "Elegoo PLA+ 120 g · Elegoo Rapid PETG 40 g" when grams are known. */
fun HistoryTotals.lines(res: Resources): List<String> = listOfNotNull(
    res.getString(
        R.string.history_totals, res.getQuantityString(R.plurals.prints, prints, prints),
        formatDuration(res, seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), if (prints == 0) 0 else done * 100 / prints,
    ),
    grams.entries.joinToString(" · ") { (filament, g) -> res.getString(R.string.filament_grams, filament, res.getString(R.string.grams, g)) }
        .ifEmpty { null },
)

/**
 * The loaded tray holding the filament a file was sliced for ("Elegoo Rapid PETG" → the PETG tray).
 * The longest matching type wins, so "PLA+" beats "PLA"; null when none matches or the name has no filament.
 */
fun matchingTray(filament: String?, loaded: List<Tray>): Tray? {
    if (filament == null) return null
    return loaded.filter { it.type.isNotEmpty() && filament.contains(it.type, ignoreCase = true) }.maxByOrNull { it.type.length }
}

/** The file names a filament ([filament]) that none of the [loaded] trays holds. */
fun trayMismatch(filament: String?, loaded: List<Tray>) = filament != null && loaded.isNotEmpty() && matchingTray(filament, loaded) == null

/** The start form's last choices, remembered per printer. */
data class StartChoices(val plate: Int, val autoLevel: Boolean, val timelapse: Boolean) {
    fun encode() = "$plate,$autoLevel,$timelapse"

    /** "Textured · Auto leveling · Timelapse": what Start will use. */
    fun summary(res: Resources) = listOfNotNull(
        res.getString(if (plate == 1) R.string.plate_smooth else R.string.plate_textured),
        res.getString(R.string.auto_level_short).takeIf { autoLevel },
        res.getString(R.string.timelapse_short).takeIf { timelapse },
    ).joinToString(" · ")

    companion object {
        fun decode(s: String?): StartChoices? {
            val parts = s?.split(',')?.takeIf { it.size == 3 } ?: return null
            val plate = parts[0].toIntOrNull() ?: return null
            return StartChoices(plate, parts[1].toBoolean(), parts[2].toBoolean())
        }
    }
}

/** "12.3 GB free on the printer", from its attributes; null until it reports it. */
fun freeSpace(ctx: Context, attributes: JSONObject?): String? =
    (attributes?.opt("RemainingMemory") as? Number)?.let {
        ctx.getString(R.string.free_space, Formatter.formatShortFileSize(ctx, it.toLong()))
    }

/** Whether the printer reports a USB drive plugged in. */
fun usbConnected(attributes: JSONObject?) = attributes?.optInt("UsbDiskStatus") == 1

/** Hands a printer video (a timelapse) to the phone's player; false when no app can play it. */
fun playVideo(ctx: Context, url: String): Boolean = try {
    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(url.toUri(), "video/*"))
    true
} catch (_: ActivityNotFoundException) {
    false
}
