package app.centauri.remote

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor

private const val MIN_RETRY_MS = 1_000L
private const val MAX_RETRY_MS = 30_000L
private const val STATUS_POLL_MS = 10_000L
private const val VIDEO_OFF_GRACE_MS = 1_500L
private const val DISCOVERY_WINDOW_MS = 2_000L
private const val THUMBNAIL_PX = 256

/**
 * Where to send the discovery probe: each IPv4 link's directed broadcast (255.255.255.255 often
 * never leaves the Wi-Fi interface on Android), plus the limited broadcast as a fallback.
 */
fun broadcastTargets(ctx: Context): List<String> {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val links = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses.orEmpty()
    val directed = links.mapNotNull { la -> (la.address as? Inet4Address)?.let { directedBroadcast(it, la.prefixLength) } }
    return (directed + "255.255.255.255").distinct()
}

/**
 * UDP "M99999" to [DISCOVERY_PORT] on every target (broadcast addresses, or one typed-in IP),
 * collecting replies for [DISCOVERY_WINDOW_MS]. Never throws for network trouble: an unusable
 * target or socket just means fewer (or no) printers found.
 */
suspend fun discover(targets: List<String>, socket: () -> DatagramSocket = ::DatagramSocket) = withContext(Dispatchers.IO) {
    val found = linkedMapOf<String, Printer>()
    try {
        socket().use { sock ->
            sock.broadcast = true
            val msg = "M99999".toByteArray()
            targets.forEach { t ->
                try {
                    sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName(t), DISCOVERY_PORT))
                } catch (_: IOException) {
                    // unresolvable or unreachable target; try the others
                }
            }
            val buf = ByteArray(4096)
            val deadline = System.nanoTime() + DISCOVERY_WINDOW_MS * 1_000_000
            // Short receive timeouts so cancellation and the overall window are honoured promptly.
            while (System.nanoTime() < deadline) {
                ensureActive()
                sock.soTimeout = 250
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                parseDiscovery(String(pkt.data, 0, pkt.length), pkt.address)?.let { found[it.ip] = it }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        // no socket (e.g. no network): report what we have
    }
    found.values.toList()
}

/**
 * One printer's SDCP connection. All state is confined to [scope]'s dispatcher (Main in the
 * app): socket callbacks hop onto it before touching anything, and suspend APIs switch to it.
 */
class SdcpClient(
    private val http: OkHttpClient,
    override val printer: Printer,
    private val scope: CoroutineScope,
    private val wsPort: Int = WS_PORT,
    private val requestTimeoutMs: Long = 10_000L,
    private val statusPollMs: Long = STATUS_POLL_MS,
) : PrinterLink {
    private val confined = scope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Main.immediate

    private val _connected = MutableStateFlow(false)
    private val _status = MutableStateFlow<Status?>(null)
    private val _videoUrl = MutableStateFlow<String?>(null)
    private val _thumbnail = MutableStateFlow<Bitmap?>(null)
    private val _name = MutableStateFlow(printer.name)
    private val _canvas = MutableStateFlow<Canvas?>(null)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    override val status: StateFlow<Status?> = _status.asStateFlow()
    override val videoUrl: StateFlow<String?> = _videoUrl.asStateFlow()
    override val thumbnail: StateFlow<Bitmap?> = _thumbnail.asStateFlow()
    override val name: StateFlow<String> = _name.asStateFlow()
    override val canvas: StateFlow<Canvas?> = _canvas.asStateFlow()

    private var ws: WebSocket? = null
    private var running = false
    private var users = 0
    private var poller: Job? = null
    private var retryDelayMs = MIN_RETRY_MS
    private val pending = HashMap<String, CompletableDeferred<JSONObject>>()

    private var thumbTask = ""
    private var thumbJob: Job? = null
    /** RequestID of the last thumbnail lookup, so history replies aren't parsed for it too. */
    private var thumbRequest = ""

    private var wantVideo = false
    private var videoUsers = 0
    private var videoOff: Job? = null

    /** Ref-counted: the UI and the print-monitor service each hold the connection while they need it. */
    override fun start() {
        if (users++ > 0) return
        running = true
        open()
        // The printer pushes status on change; a slow poll covers anything missed.
        poller = scope.launch {
            while (true) { // delay ends it on cancel
                delay(statusPollMs)
                send(CMD_STATUS)
                // Trays aren't in the status push; the tray in use changes mid-print.
                if (_status.value?.canvas == true) send(CMD_CANVAS)
                askThumbnail() // after a failed lookup or download
            }
        }
    }

    override fun stop() {
        if (users == 0 || --users > 0) return
        shutdown()
    }

    /** Drop the connection now, whoever holds it (also used when switching printers). */
    override fun shutdown() {
        // Give the printer its video slot back before the socket goes; OkHttp sends queued
        // messages before the close frame.
        videoOff?.cancel()
        if (wantVideo) send(CMD_VIDEO, JSONObject().put("Enable", 0))
        wantVideo = false
        videoUsers = 0
        _videoUrl.value = null
        users = 0
        running = false
        poller?.cancel()
        thumbJob?.cancel()
        failPending(R.string.err_lost)
        ws?.close(1000, null)
        ws = null
        _connected.value = false
        _canvas.value = null
        // A later start() must not show (or alert on) the job as it was when we let go.
        _status.value = null
        thumbTask = ""
        _thumbnail.value = null
    }

    private fun open() {
        val req = try {
            Request.Builder().url("ws://${printer.ip}:$wsPort/websocket").build()
        } catch (_: IllegalArgumentException) {
            return // unusable address; stay disconnected rather than crash
        }
        // OkHttp calls these on its own threads; each hops to the client's scope and ignores
        // sockets that have since been replaced.
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch { if (webSocket === ws) opened() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Parsed here, off the main thread; replies can be large (history).
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                scope.launch { if (webSocket === ws) handle(j) }
            }
            // The printer closing first: answer the handshake, or onClosed never comes and the
            // client stays "connected" to a dead socket.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
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
        if (_status.value?.canvas == true) send(CMD_CANVAS) // the status reply alone wouldn't ask again
        if (wantVideo) send(CMD_VIDEO, JSONObject().put("Enable", 1))
        askThumbnail()
    }

    /** Retry now instead of waiting out the backoff. */
    override fun reconnect() {
        if (!running || connected.value) return
        retryDelayMs = MIN_RETRY_MS
        ws?.cancel()
        open()
    }

    private fun lost(socket: WebSocket) {
        if (socket !== ws) return
        _connected.value = false
        // Anyone waiting on a reply would otherwise sit out the full request timeout.
        failPending(R.string.err_lost)
        if (!running) return
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        scope.launch { delay(wait); if (running && ws === socket) open() }
    }

    private fun failPending(reason: Int) {
        // Copy first: on Main.immediate a failed waiter resumes inline and removes itself from [pending].
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(PrinterException(reason)) }
    }

    /** Fire-and-forget command. Returns false when there is no connection to send it on. */
    fun send(cmd: Int, data: JSONObject = JSONObject()): Boolean = sendFrame(cmd, data, newRequestId())

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

    /**
     * Sends a command and waits for the printer's reply (matched on RequestID); returns its Data.
     * Fails with [PrinterException] when not connected, when the connection drops, or on timeout.
     * The timeout is a normal failure, not a cancellation, so callers' error paths see it.
     */
    suspend fun request(cmd: Int, data: JSONObject = JSONObject()): JSONObject = withContext(confined) {
        val id = newRequestId()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            if (!sendFrame(cmd, data, id)) throw PrinterException(R.string.err_not_connected)
            withTimeoutOrNull(requestTimeoutMs) { reply.await() } ?: throw PrinterException(R.string.err_timeout)
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun renamePrinter(name: String): Boolean {
        val ok = ackOk(request(CMD_RENAME_PRINTER, JSONObject().put("Name", name)))
        if (ok) _name.value = name
        return ok
    }

    override suspend fun deleteFiles(paths: List<String>) =
        ackOk(request(CMD_DELETE_FILES, JSONObject().put("FileList", JSONArray(paths))))

    override suspend fun deleteHistory(taskIds: List<String>) =
        ackOk(request(CMD_DELETE_HISTORY, JSONObject().put("Id", JSONArray(taskIds))))

    override suspend fun files(): List<PrinterFile> = parseFiles(request(CMD_FILE_LIST, JSONObject().put("Url", "/local")))

    override suspend fun fileInfo(path: String): FileInfo =
        parseFileInfo(request(CMD_FILE_INFO, JSONObject().put("Url", path))).let { it.copy(thumbnail = onPrinter(it.thumbnail)) }

    override suspend fun history(): List<HistoryEntry> {
        val ids = request(CMD_HISTORY).optJSONArray("HistoryData") ?: return emptyList()
        if (ids.length() == 0) return emptyList()
        return details(request(CMD_TASK_DETAILS, JSONObject().put("Id", ids)))
    }

    override suspend fun taskDetails(taskId: String): HistoryEntry? =
        details(request(CMD_TASK_DETAILS, JSONObject().put("Id", JSONArray().put(taskId)))).firstOrNull { it.taskId == taskId }

    private fun details(data: JSONObject) = parseHistory(data).map { it.copy(thumbnail = onPrinter(it.thumbnail)) }

    /** A URL the printer hands back, kept only if it points at the printer itself; otherwise "". */
    private fun onPrinter(url: String) = url.takeIf { it.toHttpUrlOrNull()?.host == printer.ip }.orEmpty()

    /** Same payload as the web UI's start dialog, plus Elegoo's slot_map for a chosen tray. Returns the printer's Ack. */
    override suspend fun startPrint(path: String, autoLevel: Boolean, timelapse: Boolean, plate: Int, tray: Tray?): Int =
        request(CMD_START, JSONObject()
            .put("Filename", path).put("StartLayer", 0)
            .put("Calibration_switch", if (autoLevel) 1 else 0)
            .put("PrintPlatformType", plate)
            .put("Tlp_Switch", if (timelapse) 1 else 0)
            .put("slot_map", slotMap(tray))).optInt("Ack", -1)

    override fun pause() = send(CMD_PAUSE)
    override fun resume() = send(CMD_RESUME)
    override fun stopPrint() = send(CMD_STOP)
    override fun setSpeed(pct: Int) = send(CMD_CONTROL, JSONObject().put("PrintSpeedPct", pct.coerceIn(50, 160)))
    override fun setNozzle(c: Int) = send(CMD_CONTROL, JSONObject().put("TempTargetNozzle", c.coerceIn(0, 320)))
    override fun setBed(c: Int) = send(CMD_CONTROL, JSONObject().put("TempTargetHotbed", c.coerceIn(0, 110)))
    override fun home(axis: String) = send(CMD_HOME, JSONObject().put("Axis", axis))
    /** Elegoo's own CC1 client builds this payload (elegoo-link MOVE_AXES); steps are capped at 10 mm. */
    override fun jog(axis: String, mm: Double) = send(CMD_MOVE, JSONObject().put("Axis", axis).put("Step", mm.coerceIn(-10.0, 10.0)))
    override fun skipPreheat() = send(CMD_SKIP_PREHEAT)
    override fun stopFeed() = send(CMD_STOP_FEED)

    /** The web UI always sends all three fans together. */
    override fun setFans(model: Int, aux: Int, box: Int) = send(CMD_CONTROL, JSONObject().put("TargetFanSpeed",
        JSONObject().put("ModelFan", model.coerceIn(0, 100)).put("AuxiliaryFan", aux.coerceIn(0, 100))
            .put("BoxFan", box.coerceIn(0, 100))))

    override fun setLight(on: Boolean) = send(CMD_CONTROL, JSONObject().put("LightStatus",
        JSONObject().put("SecondLight", if (on) 1 else 0).put("RgbLight", JSONArray(status.value?.rgb ?: listOf(0, 0, 0)))))

    /**
     * Ref-counted like the HA integration's camera: the printer has few video slots, so video is
     * only on while some view shows it. "Off" waits a moment so a view handing over to another
     * (e.g. entering fullscreen) doesn't toggle the stream off and on, which the printer finishes
     * out of order and leaves video off.
     */
    override fun video(enable: Boolean) {
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
                delay(VIDEO_OFF_GRACE_MS)
                wantVideo = false
                _videoUrl.value = null
                send(CMD_VIDEO, JSONObject().put("Enable", 0))
            }
        }
    }

    private fun handle(j: JSONObject) {
        val topic = j.optString("Topic").split("/")
        // Topics end in the mainboard id: ignore another printer that took over this address. A topic
        // without an id still counts, so firmware that leaves it out isn't shut out.
        if (topic.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { it != printer.mainboardId } == true) return
        when (topic.getOrNull(1)) {
            "status" -> j.optJSONObject("Status")?.let { s ->
                val st = parseStatus(s)
                val hadCanvas = _status.value?.canvas == true
                _status.value = st
                if (!st.canvas) _canvas.value = null
                else if (!hadCanvas) send(CMD_CANVAS) // just attached, or the first status
                if (st.print.taskId != thumbTask) {
                    // New job: drop the old picture now rather than show it on the wrong print.
                    thumbTask = st.print.taskId
                    thumbJob?.cancel()
                    _thumbnail.value = null
                    askThumbnail()
                }
            }
            // The printer's own name, e.g. after it was renamed on the touchscreen.
            "attributes" -> (j.optJSONObject("Attributes") ?: j).optString("Name").takeIf { it.isNotBlank() }
                ?.let { _name.value = it }
            "response" -> {
                val d = j.optJSONObject("Data") ?: return
                val data = d.optJSONObject("Data") ?: return
                val id = d.optString("RequestID")
                pending.remove(id)?.complete(data)
                when (d.optInt("Cmd")) {
                    // A late reply to "video off" must not resurrect the URL. A refusal (no free
                    // video slot) is "", so the view can say so instead of waiting.
                    CMD_VIDEO -> if (wantVideo) _videoUrl.value = if (data.optInt("Ack", 0) != 0) "" else data.optString("VideoUrl")
                        .let { if (it.startsWith("http")) it else "http://$it" }
                        .let(::onPrinter).ifEmpty { "http://${printer.ip}:$VIDEO_PORT/video" }
                    CMD_CANVAS -> if (data.optInt("Ack") == 0 && _status.value?.canvas == true) _canvas.value = parseCanvas(data)
                    // History fetches use the same command; only our own lookup is for the thumbnail.
                    CMD_TASK_DETAILS -> if (id == thumbRequest) details(data).firstOrNull { it.taskId == thumbTask }
                        ?.thumbnail?.takeIf { it.isNotEmpty() }?.let { loadThumbnail(thumbTask, it) }
                }
            }
        }
    }

    /** Asks for the current job's details (for its thumbnail) unless that's shown or loading. */
    private fun askThumbnail() {
        if (thumbTask.isEmpty() || _thumbnail.value != null || thumbJob?.isActive == true) return
        thumbRequest = newRequestId()
        sendFrame(CMD_TASK_DETAILS, JSONObject().put("Id", JSONArray().put(thumbTask)), thumbRequest)
    }

    private fun loadThumbnail(task: String, url: String) {
        thumbJob?.cancel()
        thumbJob = scope.launch {
            val bmp = orNull { withContext(Dispatchers.IO) { decodeScaled(http.fetchBytes(url), THUMBNAIL_PX) } }
            if (task == thumbTask) _thumbnail.value = bmp
        }
    }
}
