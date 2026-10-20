package app.centauri.remote

import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.net.SocketTimeoutException
import java.util.UUID

// SDCP over WebSocket, Centauri Carbon (CC1) only. Command ids and payloads mirror
// elegoo-homeassistant's sdcp/const.py and sdcp/transport/base.py; start print, file list
// and file info follow the printer's own web UI (it serves them at http://<printer>/).
const val CMD_STATUS = 0
const val CMD_ATTRIBUTES = 1
const val CMD_START = 128
const val CMD_PAUSE = 129
const val CMD_STOP = 130
const val CMD_RESUME = 131
const val CMD_FILE_LIST = 258
const val CMD_FILE_INFO = 260
const val CMD_HISTORY = 320
const val CMD_TASK_DETAILS = 321
const val CMD_VIDEO = 386
const val CMD_HOME = 402
const val CMD_CONTROL = 403

private const val MIN_RETRY_MS = 1_000L
private const val MAX_RETRY_MS = 30_000L
private const val REQUEST_TIMEOUT_MS = 10_000L

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

/** Web-UI speed presets for SDCP firmware. */
val SPEED_PRESETS = listOf(
    50 to R.string.speed_silent, 100 to R.string.speed_balanced,
    130 to R.string.speed_sport, 160 to R.string.speed_ludicrous,
)

data class PrinterFile(val path: String, val sizeBytes: Long, val created: Long, val layers: Int) {
    val name get() = path.substringAfterLast('/')
}

/** ElegooSlicer names files ECC_<nozzle>_<model>_<filament>_<layer>_<time>.gcode. */
private val SLICED = Regex("""^ECC_[\d.]+_(.+)_([^_]+)_([\d.]+)_([\dhms]+)$""")

/** A sliced file's name split into what to show: model as the title, profile as detail. */
data class JobName(val title: String, val detail: String?)

fun jobName(file: String): JobName {
    val base = file.substringAfterLast('/').removeSuffix(".gcode")
    val m = SLICED.matchEntire(base) ?: return JobName(base, null)
    val (model, filament, layer, time) = m.destructured
    return JobName(model, "${filament.trim()} · $layer mm · $time")
}

fun printStatusIsError(code: Int) = code == PrintState.ERROR || code == PrintState.UNLOAD_ERROR

/** Pausing, stopping, resuming: the printer is mid-transition, so buttons wait. */
fun printStatusIsTransition(code: Int) = code == PrintState.PAUSING || code == PrintState.STOPPING || code == PrintState.RESUMING

data class FileInfo(val thumbnail: String, val estS: Int, val estGrams: Double, val layers: Int)

data class HistoryEntry(
    val taskId: String, val name: String, val thumbnail: String,
    val begin: Long, val end: Long, val completed: Boolean, val layers: Int, val totalLayers: Int,
)

data class Printer(val name: String, val ip: String, val mainboardId: String, val connId: String)

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
    return Status(
        machine = s.optJSONArray("CurrentStatus")?.optInt(0) ?: 0,
        nozzle = s.optDouble("TempOfNozzle", 0.0), nozzleTarget = s.optDouble("TempTargetNozzle", 0.0),
        bed = s.optDouble("TempOfHotbed", 0.0), bedTarget = s.optDouble("TempTargetHotbed", 0.0),
        chamber = s.optDouble("TempOfBox", 0.0),
        modelFan = fan.optInt("ModelFan"), auxFan = fan.optInt("AuxiliaryFan"), boxFan = fan.optInt("BoxFan"),
        light = light.optInt("SecondLight") == 1, rgb = light.optJSONArray("RgbLight")?.let { a -> List(a.length()) { a.optInt(it) } } ?: listOf(0, 0, 0),
        canvas = s.optInt("AmsConnectStatus") == 1, plate = s.optInt("PlatFormType"),
        print = PrintInfo(
            status = p.optInt("Status"), layer = p.optInt("CurrentLayer"), totalLayers = p.optInt("TotalLayer"),
            // FDM firmware reports ticks in seconds.
            elapsedS = p.optInt("CurrentTicks"), totalS = p.optInt("TotalTicks"), progress = p.optInt("Progress"),
            file = p.optString("Filename"), taskId = p.optString("TaskId"), speedPct = p.optInt("PrintSpeedPct", 100),
        ),
    )
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

/**
 * Where to send the discovery probe: each IPv4 link's directed broadcast (255.255.255.255 often
 * never leaves the Wi-Fi interface on Android), plus the limited broadcast as a fallback.
 */
fun broadcastTargets(ctx: Context): List<String> {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val links = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses.orEmpty()
    val directed = links.mapNotNull { la ->
        val v4 = la.address as? Inet4Address ?: return@mapNotNull null
        val ip = ByteBuffer.wrap(v4.address).int
        val hostBits = if (la.prefixLength >= 32) 0 else -1 ushr la.prefixLength
        InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip or hostBits).array()).hostAddress
    }
    return (directed + "255.255.255.255").distinct()
}

/** UDP "M99999" to port 3000 on every target (broadcast addresses, or one typed-in IP). */
suspend fun discover(targets: List<String>): List<Printer> = withContext(Dispatchers.IO) {
    val found = linkedMapOf<String, Printer>()
    DatagramSocket().use { sock ->
        sock.broadcast = true
        sock.soTimeout = 2000
        val msg = "M99999".toByteArray()
        targets.forEach { t ->
            runCatching { sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName(t), 3000)) }
        }
        val buf = ByteArray(4096)
        while (true) {
            val pkt = DatagramPacket(buf, buf.size)
            try { sock.receive(pkt) } catch (_: SocketTimeoutException) { break }
            runCatching {
                val j = JSONObject(String(pkt.data, 0, pkt.length))
                val d = j.optJSONObject("Data") ?: j
                val a = d.optJSONObject("Attributes") ?: d
                val ip = a.optString("MainboardIP").ifEmpty { pkt.address.hostAddress!! }
                found[ip] = Printer(a.optString("Name"), ip, a.optString("MainboardID"), j.optString("Id"))
            }
        }
    }
    found.values.toList()
}

/**
 * One printer's SDCP connection. All state lives on [scope]'s dispatcher (Main): socket callbacks
 * hop onto it before touching anything, so no field is shared across threads.
 */
class SdcpClient(private val http: OkHttpClient, val printer: Printer, private val scope: CoroutineScope) {
    private val _connected = MutableStateFlow(false)
    private val _status = MutableStateFlow<Status?>(null)
    private val _videoUrl = MutableStateFlow<String?>(null)
    private val _thumbnail = MutableStateFlow<Bitmap?>(null)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    val status: StateFlow<Status?> = _status.asStateFlow()
    val videoUrl: StateFlow<String?> = _videoUrl.asStateFlow()
    val thumbnail: StateFlow<Bitmap?> = _thumbnail.asStateFlow()

    private var ws: WebSocket? = null
    private var running = false
    private var poller: Job? = null
    private var thumbTask = ""
    private var wantVideo = false
    private var retryDelayMs = MIN_RETRY_MS

    private var users = 0

    /** Ref-counted: the UI and the print-monitor service each hold the connection while they need it. */
    fun start() {
        if (users++ > 0) return
        running = true
        open()
        // The printer pushes status on change; a slow poll covers anything missed.
        poller = scope.launch {
            while (isActive) { delay(10_000); send(CMD_STATUS) }
        }
    }

    fun stop() {
        if (users == 0 || --users > 0) return
        shutdown()
    }

    /** Drop the connection now, whoever holds it (used when switching printers). */
    fun shutdown() {
        users = 0
        running = false
        poller?.cancel()
        ws?.close(1000, null)
        ws = null
        _connected.value = false
    }

    private fun open() {
        val req = Request.Builder().url("ws://${printer.ip}:3030/websocket").build()
        // OkHttp calls these on its own threads; each hops to the client's scope and ignores
        // sockets that have since been replaced.
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch { if (webSocket === ws) opened() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (webSocket === ws) handle(text) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { lost(webSocket) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { lost(webSocket) }
            }
        })
    }

    private fun opened() {
        _connected.value = true
        retryDelayMs = MIN_RETRY_MS
        send(CMD_ATTRIBUTES)
        send(CMD_STATUS)
        if (wantVideo) send(CMD_VIDEO, JSONObject().put("Enable", 1))
    }

    /** Retry now instead of waiting out the backoff. */
    fun reconnect() {
        if (!running || connected.value) return
        retryDelayMs = MIN_RETRY_MS
        ws?.cancel()
        open()
    }

    private fun lost(socket: WebSocket) {
        if (socket !== ws) return
        _connected.value = false
        // Anyone waiting on a reply would otherwise sit out the full request timeout.
        pending.values.forEach { it.completeExceptionally(IOException("Connection to the printer was lost")) }
        pending.clear()
        if (!running) return
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        scope.launch { delay(wait); if (running && ws === socket) open() }
    }

    fun send(cmd: Int, data: JSONObject = JSONObject()) { sendFrame(cmd, data, newRequestId()) }

    private fun newRequestId() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun sendFrame(cmd: Int, data: JSONObject, requestId: String): Boolean {
        val frame = JSONObject()
            .put("Id", printer.connId)
            .put("Data", JSONObject()
                .put("Cmd", cmd).put("Data", data)
                .put("RequestID", requestId)
                .put("MainboardID", printer.mainboardId)
                .put("TimeStamp", System.currentTimeMillis() / 1000)
                .put("From", 0))
            .put("Topic", "sdcp/request/${printer.mainboardId}")
        return ws?.send(frame.toString()) == true
    }

    private val pending = HashMap<String, CompletableDeferred<JSONObject>>()

    /** Sends a command and waits for the printer's reply (matched on RequestID); returns its Data. */
    // Runs on the main dispatcher like every other piece of client state.
    suspend fun request(cmd: Int, data: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.Main.immediate) {
        val id = newRequestId()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            if (!sendFrame(cmd, data, id)) throw IOException("Not connected to the printer")
            withTimeout(REQUEST_TIMEOUT_MS) { reply.await() }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun files(): List<PrinterFile> {
        val list = request(CMD_FILE_LIST, JSONObject().put("Url", "/local")).optJSONArray("FileList") ?: JSONArray()
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optInt("type") == 1 } // 1 = file, 0 = folder
            .map { PrinterFile(it.optString("name"), it.optLong("FileSize"), it.optLong("CreateTime"), it.optInt("TotalLayers")) }
            .sortedByDescending { it.created }
    }

    suspend fun fileInfo(path: String): FileInfo {
        val f = request(CMD_FILE_INFO, JSONObject().put("Url", path)).optJSONObject("FileInfo") ?: JSONObject()
        return FileInfo(f.optString("Thumbnail"), f.optInt("EstTime"), f.optDouble("EstWeight", 0.0), f.optInt("TotalLayers"))
    }

    suspend fun history(): List<HistoryEntry> {
        val ids = request(CMD_HISTORY).optJSONArray("HistoryData") ?: return emptyList()
        if (ids.length() == 0) return emptyList()
        val list = request(CMD_TASK_DETAILS, JSONObject().put("Id", ids)).optJSONArray("HistoryDetailList") ?: JSONArray()
        return (0 until list.length()).map { list.getJSONObject(it) }.map {
            HistoryEntry(
                it.optString("TaskId"), it.optString("TaskName").substringAfterLast('/'), it.optString("Thumbnail"),
                it.optLong("BeginTime"), it.optLong("EndTime"), it.optInt("TaskStatus") == 1,
                it.optInt("AlreadyPrintLayer"), it.optJSONObject("SliceInformation")?.optInt("total_layer_numbers") ?: 0,
            )
        }.sortedByDescending { it.begin }
    }

    /** Same payload as the web UI's start dialog; slot_map stays empty without a Canvas. */
    suspend fun startPrint(path: String, autoLevel: Boolean, timelapse: Boolean, plate: Int): Int =
        request(CMD_START, JSONObject()
            .put("Filename", path).put("StartLayer", 0)
            .put("Calibration_switch", if (autoLevel) 1 else 0)
            .put("PrintPlatformType", plate)
            .put("Tlp_Switch", if (timelapse) 1 else 0)
            .put("slot_map", JSONArray())).optInt("Ack", -1)

    fun setSpeed(pct: Int) = send(CMD_CONTROL, JSONObject().put("PrintSpeedPct", pct.coerceIn(50, 160)))
    fun setNozzle(c: Int) = send(CMD_CONTROL, JSONObject().put("TempTargetNozzle", c.coerceIn(0, 320)))
    fun setBed(c: Int) = send(CMD_CONTROL, JSONObject().put("TempTargetHotbed", c.coerceIn(0, 110)))
    fun home(axis: String) = send(CMD_HOME, JSONObject().put("Axis", axis))

    /** The web UI always sends all three fans together. */
    fun setFans(model: Int, aux: Int, box: Int) = send(CMD_CONTROL, JSONObject().put("TargetFanSpeed",
        JSONObject().put("ModelFan", model.coerceIn(0, 100)).put("AuxiliaryFan", aux.coerceIn(0, 100))
            .put("BoxFan", box.coerceIn(0, 100))))

    private fun handle(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (j.optString("Topic").split("/").getOrNull(1)) {
            "status" -> j.optJSONObject("Status")?.let { s ->
                val st = parseStatus(s)
                _status.value = st
                if (st.print.taskId.isNotEmpty() && st.print.taskId != thumbTask) {
                    thumbTask = st.print.taskId
                    send(CMD_TASK_DETAILS, JSONObject().put("Id", JSONArray().put(thumbTask)))
                }
            }
            "response" -> {
                val d = j.optJSONObject("Data") ?: return
                val data = d.optJSONObject("Data") ?: return
                pending.remove(d.optString("RequestID"))?.complete(data)
                when (d.optInt("Cmd")) {
                    // A late reply to "video off" must not resurrect the URL.
                    CMD_VIDEO -> if (wantVideo) _videoUrl.value = data.optString("VideoUrl").ifEmpty { "${printer.ip}:3031/video" }
                        .let { if (it.startsWith("http")) it else "http://$it" }
                    // History fetches use the same command, so only take the current job's entry.
                    CMD_TASK_DETAILS -> data.optJSONArray("HistoryDetailList")?.let { list ->
                        (0 until list.length()).map { list.getJSONObject(it) }
                            .firstOrNull { it.optString("TaskId") == thumbTask }
                            ?.optString("Thumbnail")?.takeIf { it.isNotEmpty() }?.let(::loadThumbnail)
                    }
                }
            }
        }
    }

    private fun loadThumbnail(url: String) = scope.launch(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                _thumbnail.value = BitmapFactory.decodeStream(r.body.byteStream())
            }
        }
    }

    fun pause() = send(CMD_PAUSE)
    fun resume() = send(CMD_RESUME)
    fun stopPrint() = send(CMD_STOP)

    fun setLight(on: Boolean) = send(CMD_CONTROL, JSONObject().put("LightStatus",
        JSONObject().put("SecondLight", if (on) 1 else 0).put("RgbLight", JSONArray(status.value?.rgb ?: listOf(0, 0, 0)))))

    private var videoUsers = 0
    private var videoOff: Job? = null

    /**
     * Ref-counted like the HA integration's camera: the printer has few video slots, so video is
     * only on while some view shows it. "Off" waits a moment so a view handing over to another
     * (e.g. entering fullscreen) doesn't toggle the stream off and on, which the printer finishes
     * out of order and leaves video off.
     */
    fun video(enable: Boolean) {
        if (enable) {
            videoUsers++
            videoOff?.cancel()
            if (!wantVideo) {
                wantVideo = true
                _videoUrl.value = null
                send(CMD_VIDEO, JSONObject().put("Enable", 1))
            }
        } else if (videoUsers > 0 && --videoUsers == 0) {
            videoOff = scope.launch {
                delay(1500)
                wantVideo = false
                _videoUrl.value = null
                send(CMD_VIDEO, JSONObject().put("Enable", 0))
            }
        }
    }
}
