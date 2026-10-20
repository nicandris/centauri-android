package app.centauri.remote

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

// SDCP over WebSocket, Centauri Carbon (CC1) only. Command ids and payloads mirror
// elegoo-homeassistant's sdcp/const.py and sdcp/transport/base.py; start print, file list
// and file info follow the printer's own web UI (it serves them at http://<printer>/).
// Everything in this file is pure (no I/O) so it can be unit-tested on the JVM.
const val CMD_STATUS = 0
const val CMD_ATTRIBUTES = 1
const val CMD_START = 128
const val CMD_PAUSE = 129
const val CMD_STOP = 130
const val CMD_RESUME = 131
const val CMD_RENAME_PRINTER = 192
const val CMD_FILE_LIST = 258
const val CMD_DELETE_FILES = 259
const val CMD_FILE_INFO = 260
const val CMD_HISTORY = 320
const val CMD_TASK_DETAILS = 321
const val CMD_DELETE_HISTORY = 322
const val CMD_VIDEO = 386
const val CMD_HOME = 402
const val CMD_CONTROL = 403

const val WS_PORT = 3030
const val DISCOVERY_PORT = 3000
const val VIDEO_PORT = 3031

/** FDM PrintInfo.Status codes the app acts on (full table in [printStatusLabel]). */
object PrintState {
    const val IDLE = 0
    const val PAUSING = 5
    const val PAUSED = 6
    const val STOPPING = 7
    const val STOPPED = 8
    const val COMPLETE = 9
    const val RESUMING = 12
    const val PRINTING = 13
    const val ERROR = 14
    const val UNLOAD_ERROR = 25
}

/** A failure talking to the printer, carrying a user-facing message. */
class PrinterException(@StringRes val messageRes: Int) : IOException()

/** Web-UI speed presets for SDCP firmware. */
val SPEED_PRESETS = listOf(
    50 to R.string.speed_silent, 100 to R.string.speed_balanced,
    130 to R.string.speed_sport, 160 to R.string.speed_ludicrous,
)

data class Printer(val name: String, val ip: String, val mainboardId: String, val connId: String)

data class PrinterFile(val path: String, val sizeBytes: Long, val created: Long, val layers: Int)

/** [thumbnailRes] is a bundled image (the demo printer); real printers serve [thumbnail] as a URL. */
data class FileInfo(
    val thumbnail: String, val estS: Int, val estGrams: Double, val layers: Int,
    @DrawableRes val thumbnailRes: Int? = null,
)

data class HistoryEntry(
    val taskId: String, val path: String, val thumbnail: String,
    val begin: Long, val end: Long, val completed: Boolean, val layers: Int, val totalLayers: Int,
    @DrawableRes val thumbnailRes: Int? = null,
) {
    /** Unique even when the printer omits or repeats a task id. */
    val key get() = "$taskId#$begin"
}

data class PrintInfo(
    val status: Int, val layer: Int, val totalLayers: Int,
    val elapsedS: Int, val totalS: Int, val progress: Int,
    val file: String, val taskId: String, val speedPct: Int,
)

data class Status(
    val machine: Int,
    val nozzle: Double, val nozzleTarget: Double,
    val bed: Double, val bedTarget: Double,
    val chamber: Double,
    val modelFan: Int, val auxFan: Int, val boxFan: Int,
    val light: Boolean, val rgb: List<Int>,
    val canvas: Boolean, val plate: Int,
    val print: PrintInfo,
) {
    /** Idle, stopped or complete: nothing is running, so files can start and axes can home. */
    val idle get() = print.status == PrintState.IDLE || print.status == PrintState.STOPPED || print.status == PrintState.COMPLETE
}

fun parseStatus(s: JSONObject): Status {
    val fan = s.optJSONObject("CurrentFanSpeed") ?: JSONObject()
    val light = s.optJSONObject("LightStatus") ?: JSONObject()
    val p = s.optJSONObject("PrintInfo") ?: JSONObject()
    val rgb = light.optJSONArray("RgbLight")
    return Status(
        machine = s.optJSONArray("CurrentStatus")?.optInt(0) ?: 0,
        nozzle = s.optDouble("TempOfNozzle", 0.0), nozzleTarget = s.optDouble("TempTargetNozzle", 0.0),
        bed = s.optDouble("TempOfHotbed", 0.0), bedTarget = s.optDouble("TempTargetHotbed", 0.0),
        chamber = s.optDouble("TempOfBox", 0.0),
        modelFan = fan.optInt("ModelFan"), auxFan = fan.optInt("AuxiliaryFan"), boxFan = fan.optInt("BoxFan"),
        light = light.optInt("SecondLight") == 1,
        rgb = rgb?.let { a -> List(a.length()) { a.optInt(it) } } ?: listOf(0, 0, 0),
        canvas = s.optInt("AmsConnectStatus") == 1, plate = s.optInt("PlatFormType"),
        print = PrintInfo(
            status = p.optInt("Status"), layer = p.optInt("CurrentLayer"), totalLayers = p.optInt("TotalLayer"),
            // FDM firmware reports ticks in seconds.
            elapsedS = p.optInt("CurrentTicks"), totalS = p.optInt("TotalTicks"), progress = p.optInt("Progress"),
            file = p.optString("Filename"), taskId = p.optString("TaskId"), speedPct = p.optInt("PrintSpeedPct", 100),
        ),
    )
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** CMD_FILE_LIST reply: files only (type 1; 0 is a folder), newest first. */
fun parseFiles(data: JSONObject): List<PrinterFile> =
    data.optJSONArray("FileList").objects()
        .filter { it.optInt("type") == 1 }
        .map { PrinterFile(it.optString("name"), it.optLong("FileSize"), it.optLong("CreateTime"), it.optInt("TotalLayers")) }
        .sortedByDescending { it.created }

/** CMD_FILE_INFO reply. */
fun parseFileInfo(data: JSONObject): FileInfo {
    val f = data.optJSONObject("FileInfo") ?: JSONObject()
    return FileInfo(f.optString("Thumbnail"), f.optInt("EstTime"), f.optDouble("EstWeight", 0.0), f.optInt("TotalLayers"))
}

/** CMD_TASK_DETAILS reply, newest first. */
fun parseHistory(data: JSONObject): List<HistoryEntry> =
    data.optJSONArray("HistoryDetailList").objects().map {
        HistoryEntry(
            it.optString("TaskId"), it.optString("TaskName"), it.optString("Thumbnail"),
            it.optLong("BeginTime"), it.optLong("EndTime"), it.optInt("TaskStatus") == 1,
            it.optInt("AlreadyPrintLayer"), it.optJSONObject("SliceInformation")?.optInt("total_layer_numbers") ?: 0,
        )
    }.sortedByDescending { it.begin }

/**
 * A discovery reply. The address is the packet's source, not the self-reported MainboardIP:
 * it's what actually answered, and a malformed field can't end up saved as the printer address.
 */
fun parseDiscovery(payload: String, source: InetAddress): Printer? {
    if (source !is Inet4Address) return null
    val j = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    val d = j.optJSONObject("Data") ?: j
    val a = d.optJSONObject("Attributes") ?: d
    val id = a.optString("MainboardID").ifEmpty { return null }
    return Printer(a.optString("Name"), source.hostAddress!!, id, j.optString("Id"))
}

/** A management reply (delete, rename printer): Ack 0 and no per-item failures in ErrData. */
fun ackOk(data: JSONObject) = data.optInt("Ack", -1) == 0 && (data.optJSONArray("ErrData")?.length() ?: 0) == 0

/** Directed broadcast address of an IPv4 interface, e.g. 192.168.178.64/24 → 192.168.178.255. */
fun directedBroadcast(address: Inet4Address, prefixLength: Int): String {
    val ip = ByteBuffer.wrap(address.address).int
    val hostBits = if (prefixLength >= 32) 0 else -1 ushr prefixLength
    return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip or hostBits).array()).hostAddress!!
}

/** ElegooSlicer names files ECC_<nozzle>_<model>_<filament>_<layer>_<time>.gcode. */
private val SLICED = Regex("""^ECC_[\d.]+_(.+)_([^_]+)_([\d.]+)_([\dhms]+)$""")

/** A sliced file's name split into what to show: the model as the title, the profile as detail. */
data class JobName(val title: String, val filament: String? = null, val layerMm: String? = null, val time: String? = null)

fun jobName(file: String): JobName {
    val base = file.substringAfterLast('/').removeSuffix(".gcode")
    val m = SLICED.matchEntire(base) ?: return JobName(base)
    val (model, filament, layer, time) = m.destructured
    return JobName(model, filament.trim(), layer, time)
}

fun JobName.detail(res: Resources): String? =
    if (filament == null) null else res.getString(R.string.job_detail, filament, layerMm, time)

/** Compact duration, "8h 58m" or "42m", in the app's language. */
fun formatDuration(res: Resources, seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) res.getString(R.string.duration_h_m, s / 3600, s % 3600 / 60)
    else res.getString(R.string.duration_m, s / 60)
}

fun printStatusIsError(code: Int) = code == PrintState.ERROR || code == PrintState.UNLOAD_ERROR

/** Pausing, stopping, resuming: the printer is mid-transition, so buttons wait. */
fun printStatusIsTransition(code: Int) =
    code == PrintState.PAUSING || code == PrintState.STOPPING || code == PrintState.RESUMING

/** Which status change is worth an alert, as its title; null when it isn't. */
@StringRes
fun alertFor(prev: Int, now: Int): Int? = when {
    now == prev -> null
    now == PrintState.COMPLETE -> R.string.alert_finished
    now == PrintState.ERROR -> R.string.alert_error
    now == PrintState.UNLOAD_ERROR -> R.string.alert_unload_error
    now == PrintState.PAUSED && (prev == PrintState.PAUSING || prev == PrintState.PRINTING) -> R.string.alert_paused
    now == PrintState.STOPPED -> R.string.alert_stopped
    else -> null
}

// FDM PrintInfo.Status table, from elegoo-homeassistant's ElegooPrintStatus.from_fdm_int.
private val PRINT_STATUS = mapOf(
    0 to R.string.status_idle, 1 to R.string.status_homing, 5 to R.string.status_pausing,
    6 to R.string.status_paused, 7 to R.string.status_stopping, 8 to R.string.status_stopped,
    9 to R.string.status_complete, 10 to R.string.status_checking_file, 11 to R.string.status_checking_printer,
    12 to R.string.status_resuming, 13 to R.string.status_printing, 14 to R.string.status_error,
    15 to R.string.status_auto_leveling, 16 to R.string.status_preheating, 17 to R.string.status_resonance_testing,
    18 to R.string.status_print_started, 19 to R.string.status_leveling_done, 20 to R.string.status_preheating_done,
    21 to R.string.status_homing_done, 22 to R.string.status_resonance_done, 23 to R.string.status_auto_feeding,
    24 to R.string.status_unloading, 25 to R.string.status_unload_error, 26 to R.string.status_unload_paused,
)
private val MACHINE_STATUS = mapOf(
    0 to R.string.status_idle, 1 to R.string.status_printing, 2 to R.string.machine_file_transfer,
    4 to R.string.machine_self_test, 5 to R.string.status_auto_leveling, 6 to R.string.machine_input_shaping,
    7 to R.string.status_stopping, 8 to R.string.status_stopped, 9 to R.string.status_homing,
    10 to R.string.machine_loading, 11 to R.string.machine_pid_tuning, 12 to R.string.machine_recovery,
)

fun printStatusLabel(res: Resources, code: Int): String =
    PRINT_STATUS[code]?.let(res::getString) ?: res.getString(R.string.status_unknown, code)

fun machineStatusLabel(res: Resources, code: Int): String =
    MACHINE_STATUS[code]?.let(res::getString) ?: res.getString(R.string.status_unknown, code)
