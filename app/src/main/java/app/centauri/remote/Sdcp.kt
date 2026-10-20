package app.centauri.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID

// SDCP over WebSocket, Centauri Carbon (CC1) only. Command ids and payloads mirror
// elegoo-homeassistant's sdcp/const.py and sdcp/transport/base.py.
const val CMD_STATUS = 0
const val CMD_ATTRIBUTES = 1
const val CMD_PAUSE = 129
const val CMD_STOP = 130
const val CMD_RESUME = 131
const val CMD_TASK_DETAILS = 321
const val CMD_VIDEO = 386
const val CMD_CONTROL = 403

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
    val light: Boolean, val rgb: JSONArray,
    val print: PrintInfo,
)

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
        light = light.optInt("SecondLight") == 1, rgb = light.optJSONArray("RgbLight") ?: JSONArray("[0,0,0]"),
        print = PrintInfo(
            status = p.optInt("Status"), layer = p.optInt("CurrentLayer"), totalLayers = p.optInt("TotalLayer"),
            // FDM firmware reports ticks in seconds.
            elapsedS = p.optInt("CurrentTicks"), totalS = p.optInt("TotalTicks"), progress = p.optInt("Progress"),
            file = p.optString("Filename"), taskId = p.optString("TaskId"), speedPct = p.optInt("PrintSpeedPct", 100),
        ),
    )
}

// FDM PrintInfo.Status table, from ElegooPrintStatus.from_fdm_int.
private val PRINT_STATUS = mapOf(
    0 to "Idle", 1 to "Homing", 5 to "Pausing", 6 to "Paused", 7 to "Stopping", 8 to "Stopped",
    9 to "Complete", 10 to "Checking file", 11 to "Checking printer", 12 to "Resuming", 13 to "Printing",
    14 to "Error", 15 to "Auto leveling", 16 to "Preheating", 17 to "Resonance testing", 18 to "Print started",
    19 to "Leveling done", 20 to "Preheating done", 21 to "Homing done", 22 to "Resonance test done",
    23 to "Auto feeding", 24 to "Unloading filament", 25 to "Filament unload error", 26 to "Unload paused",
)
private val MACHINE_STATUS = mapOf(
    0 to "Idle", 1 to "Printing", 2 to "File transfer", 4 to "Self-test", 5 to "Leveling",
    6 to "Input shaping", 7 to "Stopping", 8 to "Stopped", 9 to "Homing", 10 to "Loading/unloading",
    11 to "PID tuning", 12 to "Recovery",
)

fun printStatusLabel(code: Int) = PRINT_STATUS[code] ?: "Status $code"
fun machineStatusLabel(code: Int) = MACHINE_STATUS[code] ?: "Status $code"

/** UDP "M99999" to port 3000; broadcast, or unicast to a typed-in IP. */
suspend fun discover(target: String = "255.255.255.255"): List<Printer> = withContext(Dispatchers.IO) {
    val found = linkedMapOf<String, Printer>()
    DatagramSocket().use { sock ->
        sock.broadcast = true
        sock.soTimeout = 2000
        val msg = "M99999".toByteArray()
        sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName(target), 3000))
        val buf = ByteArray(4096)
        while (true) {
            val pkt = DatagramPacket(buf, buf.size)
            try { sock.receive(pkt) } catch (_: SocketTimeoutException) { break }
            runCatching {
                val j = JSONObject(String(pkt.data, 0, pkt.length))
                val d = j.optJSONObject("Data") ?: j
                val a = d.optJSONObject("Attributes") ?: d
                val ip = a.optString("MainboardIP").ifEmpty { pkt.address.hostAddress!! }
                found[ip] = Printer(a.optString("Name", "Centauri Carbon"), ip, a.optString("MainboardID"), j.optString("Id"))
            }
        }
    }
    found.values.toList()
}

class SdcpClient(private val http: OkHttpClient, val printer: Printer, private val scope: CoroutineScope) {
    val connected = MutableStateFlow(false)
    val status = MutableStateFlow<Status?>(null)
    val videoUrl = MutableStateFlow<String?>(null)
    val thumbnail = MutableStateFlow<Bitmap?>(null)

    private var ws: WebSocket? = null
    private var running = false
    private var poller: Job? = null
    private var thumbTask = ""
    private var wantVideo = false

    fun start() {
        if (running) return
        running = true
        open()
        // The printer pushes status on change; a slow poll covers anything missed.
        poller = scope.launch {
            while (isActive) { delay(10_000); send(CMD_STATUS) }
        }
    }

    fun stop() {
        running = false
        poller?.cancel()
        ws?.close(1000, null)
        ws = null
        connected.value = false
    }

    private fun open() {
        val req = Request.Builder().url("ws://${printer.ip}:3030/websocket").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.value = true
                send(CMD_ATTRIBUTES)
                send(CMD_STATUS)
                if (wantVideo) video(true)
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
    }

    private fun lost(socket: WebSocket) {
        if (socket !== ws) return
        connected.value = false
        if (running) scope.launch { delay(3000); if (running && ws === socket) open() }
    }

    fun send(cmd: Int, data: JSONObject = JSONObject()) {
        val frame = JSONObject()
            .put("Id", printer.connId)
            .put("Data", JSONObject()
                .put("Cmd", cmd).put("Data", data)
                .put("RequestID", UUID.randomUUID().toString().replace("-", "").take(16))
                .put("MainboardID", printer.mainboardId)
                .put("TimeStamp", System.currentTimeMillis() / 1000)
                .put("From", 0))
            .put("Topic", "sdcp/request/${printer.mainboardId}")
        ws?.send(frame.toString())
    }

    private fun handle(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (j.optString("Topic").split("/").getOrNull(1)) {
            "status" -> j.optJSONObject("Status")?.let { s ->
                val st = parseStatus(s)
                status.value = st
                if (st.print.taskId.isNotEmpty() && st.print.taskId != thumbTask) {
                    thumbTask = st.print.taskId
                    send(CMD_TASK_DETAILS, JSONObject().put("Id", JSONArray().put(thumbTask)))
                }
            }
            "response" -> {
                val d = j.optJSONObject("Data") ?: return
                val data = d.optJSONObject("Data") ?: return
                when (d.optInt("Cmd")) {
                    CMD_VIDEO -> videoUrl.value = data.optString("VideoUrl").ifEmpty { "${printer.ip}:3031/video" }
                        .let { if (it.startsWith("http")) it else "http://$it" }
                    CMD_TASK_DETAILS -> data.optJSONArray("HistoryDetailList")?.optJSONObject(0)
                        ?.optString("Thumbnail")?.takeIf { it.isNotEmpty() }?.let(::loadThumbnail)
                }
            }
        }
    }

    private fun loadThumbnail(url: String) = scope.launch(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                thumbnail.value = BitmapFactory.decodeStream(r.body.byteStream())
            }
        }
    }

    fun pause() = send(CMD_PAUSE)
    fun resume() = send(CMD_RESUME)
    fun stopPrint() = send(CMD_STOP)

    fun setLight(on: Boolean) = send(CMD_CONTROL, JSONObject().put("LightStatus",
        JSONObject().put("SecondLight", if (on) 1 else 0).put("RgbLight", status.value?.rgb ?: JSONArray("[0,0,0]"))))

    /** The printer has a few video slots; HA holds one, so only enable while the camera is on screen. */
    fun video(enable: Boolean) {
        wantVideo = enable
        if (!enable) videoUrl.value = null
        send(CMD_VIDEO, JSONObject().put("Enable", if (enable) 1 else 0))
    }
}
