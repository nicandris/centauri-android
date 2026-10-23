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
// and file info follow the printer's own web UI (it serves them at http://<printer>/); Canvas
// (Cmd 324, slot_map) follows Elegoo's own elegoo-link client.
// Everything in this file is pure (no I/O) so it can be unit-tested on the JVM.
const val CMD_STATUS = 0
const val CMD_ATTRIBUTES = 1
const val CMD_START = 128
const val CMD_PAUSE = 129
const val CMD_STOP = 130
const val CMD_RESUME = 131
const val CMD_STOP_FEED = 132
const val CMD_SKIP_PREHEAT = 133
const val CMD_RENAME_PRINTER = 192
const val CMD_FILE_LIST = 258
const val CMD_DELETE_FILES = 259
const val CMD_FILE_INFO = 260
const val CMD_HISTORY = 320
const val CMD_TASK_DETAILS = 321
const val CMD_DELETE_HISTORY = 322
const val CMD_CANVAS = 324
const val CMD_VIDEO = 386
const val CMD_MOVE = 401
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
    const val PREHEATING = 16
    const val AUTO_FEEDING = 23
    const val UNLOAD_ERROR = 25
}

/** A failure talking to the printer, carrying a user-facing message. */
class PrinterException(@StringRes val messageRes: Int) : IOException()

/** Jog steps offered in the app, mm. */
val JOG_STEPS = listOf(0.1, 1.0, 10.0)

/** A jog distance as people write it: "1", "10", "0,1" in Dutch; no trailing ".0". */
fun formatMm(mm: Double, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(mm)

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
    /** Why the job ended or paused (ErrorStatusReason); 0 when nothing went wrong. */
    val errorReason: Int = 0,
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

/**
 * True for a time stamped before the printer's clock was set: it boots at 1970 and stays there
 * when it can't reach a time server, so everything it records until then is dated January 1970.
 */
fun clockUnset(epochS: Long) = epochS < 1_000_000_000L

// ponytail: unset-clock entries are assumed newer than dated ones (the clock is lost on the
// latest boot); an older unsynced boot would sort too high. Use the printer's own order if it gets one.
private fun <T> List<T>.newestFirst(time: (T) -> Long) =
    sortedWith(compareByDescending<T> { clockUnset(time(it)) }.thenByDescending(time))

/** CMD_FILE_LIST reply: files only (type 1; 0 is a folder), newest first. */
fun parseFiles(data: JSONObject): List<PrinterFile> =
    data.optJSONArray("FileList").objects()
        .filter { it.optInt("type") == 1 }
        .map { PrinterFile(it.optString("name"), it.optLong("FileSize"), it.optLong("CreateTime"), it.optInt("TotalLayers")) }
        .newestFirst { it.created }

/** CMD_FILE_INFO reply. */
fun parseFileInfo(data: JSONObject): FileInfo {
    val f = data.optJSONObject("FileInfo") ?: JSONObject()
    return FileInfo(f.optString("Thumbnail"), f.optInt("EstTime"), f.optDouble("EstWeight", 0.0), f.optInt("TotalLayers"))
}

/** CMD_TASK_DETAILS reply, newest first; a repeated entry (same [HistoryEntry.key]) counts once. */
fun parseHistory(data: JSONObject): List<HistoryEntry> =
    data.optJSONArray("HistoryDetailList").objects().map {
        HistoryEntry(
            it.optString("TaskId"), it.optString("TaskName"), it.optString("Thumbnail"),
            it.optLong("BeginTime"), it.optLong("EndTime"), it.optInt("TaskStatus") == 1,
            it.optInt("AlreadyPrintLayer"), it.optJSONObject("SliceInformation")?.optInt("total_layer_numbers") ?: 0,
            errorReason = it.optInt("ErrorStatusReason"),
        )
    }.distinctBy { it.key }.newestFirst { it.begin }

/** One Canvas tray. [color] is 0xRRGGBB, null when the tray reports none. */
data class Tray(
    val canvasId: Int, val trayId: Int, val loaded: Boolean,
    val type: String, val name: String, val brand: String, val color: Int?,
) {
    /** The printer's own label: A1–A4 for the first unit, B1–B4 for a second. */
    val label get() = "${'A' + canvasId}${trayId + 1}"

    /** Same physical slot, whatever filament the printer last read into it. */
    fun sameSlot(other: Tray?) = other != null && canvasId == other.canvasId && trayId == other.trayId
}

/** Cmd 324: every Canvas unit's trays, and which one feeds the nozzle. */
data class Canvas(val trays: List<Tray>, val activeCanvas: Int, val activeTray: Int) {
    fun isActive(t: Tray) = t.canvasId == activeCanvas && t.trayId == activeTray
}

/** The printer pads empty tray fields with dashes or "?"; treat those as blank. */
private fun JSONObject.text(key: String) = optString(key).trim().takeUnless { it.startsWith("—") || it == "?" || it == "-" } ?: ""

/**
 * CMD_CANVAS reply, following Elegoo's own client (elegoo-link's handleCanvasStatus). The CC2
 * wraps the same fields in "canvas_info"; accept both. Trays of disconnected units are left out.
 */
fun parseCanvas(data: JSONObject): Canvas {
    val c = data.optJSONObject("canvas_info") ?: data
    val trays = c.optJSONArray("canvas_list").objects().filter { it.optInt("connected") == 1 }.flatMap { unit ->
        unit.optJSONArray("tray_list").objects().map {
            Tray(
                canvasId = unit.optInt("canvas_id"), trayId = it.optInt("tray_id"), loaded = it.optInt("status") == 1,
                type = it.text("filament_type"), name = it.text("filament_name"), brand = it.text("brand"),
                color = it.optString("filament_color").removePrefix("#").takeIf { h -> h.length == 6 }?.toIntOrNull(16),
            )
        }
    }
    return Canvas(trays, c.optInt("active_canvas_id", -1), c.optInt("active_tray_id", -1))
}

/**
 * Cmd 128's slot_map: which tray feeds each gcode tool. The CC1 can't tell us a file's colours, so
 * only T0 is mapped (single-colour files); null leaves it to the printer.
 */
fun slotMap(tray: Tray?): JSONArray = JSONArray().apply {
    if (tray != null) put(JSONObject().put("t", 0).put("canvas_id", tray.canvasId).put("tray_id", tray.trayId))
}

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

// History ErrorStatusReason, from elegoo-homeassistant's ElegooErrorStatusReason.
private val ERROR_REASON = mapOf(
    1 to R.string.reason_temperature, 3 to R.string.reason_runout, 6 to R.string.reason_jam,
    7 to R.string.reason_leveling, 12 to R.string.reason_usb_removed,
    13 to R.string.reason_homing, 14 to R.string.reason_homing, 17 to R.string.reason_homing, 23 to R.string.reason_homing,
    18 to R.string.reason_detached, 20 to R.string.reason_motion, 24 to R.string.reason_file,
    33 to R.string.reason_thermistor, 34 to R.string.reason_thermistor, 45 to R.string.reason_runout_soon,
)

/** What went wrong, in words; null when nothing did. Unknown codes are shown by number. */
fun errorReasonLabel(res: Resources, code: Int): String? = when (code) {
    0 -> null
    else -> ERROR_REASON[code]?.let(res::getString) ?: res.getString(R.string.reason_unknown, code)
}

fun printStatusLabel(res: Resources, code: Int): String =
    PRINT_STATUS[code]?.let(res::getString) ?: res.getString(R.string.status_unknown, code)

fun machineStatusLabel(res: Resources, code: Int): String =
    MACHINE_STATUS[code]?.let(res::getString) ?: res.getString(R.string.status_unknown, code)

/** Label and value of each printer fact worth showing, from its attributes reply; missing ones are left out. */
fun printerDetails(res: Resources, d: JSONObject): List<Pair<String, String>> {
    val a = d.optJSONObject("Attributes") ?: d
    fun s(k: String) = a.optString(k).trim()
    fun n(k: String) = a.opt(k) as? Number
    val viewers = n("NumberOfVideoStreamConnected")?.let { used ->
        n("MaximumVideoStreamAllowed")?.let { res.getString(R.string.detail_of, used.toInt(), it.toInt()) }
    }
    return listOf(
        R.string.detail_model to listOf(s("BrandName"), s("MachineName")).filter { it.isNotEmpty() }.joinToString(" "),
        R.string.detail_firmware to s("FirmwareVersion"),
        R.string.detail_protocol to s("ProtocolVersion"),
        R.string.detail_build_volume to s("XYZsize").takeIf { it.isNotEmpty() }?.let { it.replace("x", " × ") + " mm" },
        R.string.detail_network to s("NetworkStatus").let { if (it == "wlan") res.getString(R.string.detail_wifi) else it },
        R.string.detail_ip to s("MainboardIP"),
        R.string.detail_mac to s("MainboardMAC"),
        R.string.detail_mainboard_id to s("MainboardID"),
        R.string.detail_free_space to n("RemainingMemory")?.let { formatBytes(it.toLong()) },
        R.string.detail_usb to n("UsbDiskStatus")?.let { res.getString(if (it.toInt() == 1) R.string.connected else R.string.not_connected) },
        R.string.detail_camera_viewers to viewers,
    ).mapNotNull { (label, value) -> value?.takeIf { it.isNotEmpty() }?.let { res.getString(label) to it } }
}

/** Free space as MB or GB, one decimal; the printer reports it in bytes. */
fun formatBytes(b: Long): String = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "%.1f MB".format(b / 1e6)
