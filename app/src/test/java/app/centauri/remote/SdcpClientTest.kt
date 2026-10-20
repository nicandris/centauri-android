package app.centauri.remote

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs [SdcpClient] against a real local WebSocket server playing the printer. */
@RunWith(RobolectricTestRunner::class) // for decoding the job thumbnail
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SdcpClientTest {
    private val server = MockWebServer()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = executor.asCoroutineDispatcher() // stands in for the app's main thread
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val received = CopyOnWriteArrayList<JSONObject>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    /** What the fake printer does with each request; default: ignore it. */
    private var printer: (WebSocket, JSONObject) -> Unit = { _, _ -> }

    private lateinit var client: SdcpClient

    private fun upgrade() = MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { sockets += webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = JSONObject(text)
            received += frame
            printer(webSocket, frame)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null) // answer the close handshake so the server can stop
        }
    }).build()

    private fun newClient(ip: String = server.hostName, timeoutMs: Long = 300, pollMs: Long = 10_000) = SdcpClient(
        OkHttpClient(), Printer("test", ip, "m1", ""), scope,
        wsPort = server.port, requestTimeoutMs = timeoutMs, statusPollMs = pollMs,
    ).also { client = it }

    @Before
    fun setUp() {
        server.enqueue(upgrade())
        server.start()
        newClient()
    }

    @After
    fun tearDown() {
        runBlocking { withContext(main) { client.shutdown() } }
        scope.cancel()
        executor.shutdown()
        server.close()
    }

    private fun onMain(block: () -> Unit) = runBlocking { withContext(main) { block() } }

    private fun connect() = runBlocking {
        withContext(main) { client.start() }
        withTimeout(5_000) { client.connected.first { it } }
        waitFor("the server side") { sockets.size == server.requestCount }
    }

    /** Drops the printer's side of the current connection, like a network failure. */
    private fun drop() {
        waitFor("the server side") { sockets.size == server.requestCount }
        sockets.last().close(1001, "going away")
        waitFor("disconnect") { !client.connected.value }
    }

    private fun waitFor(what: String, check: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!check()) delay(20) }
        } catch (_: Exception) {
            fail("timed out waiting for $what")
        }
    }

    private fun reply(ws: WebSocket, req: JSONObject, data: JSONObject) {
        val d = req.getJSONObject("Data")
        ws.send(JSONObject().put("Topic", "sdcp/response/m1").put("Data", JSONObject()
            .put("Cmd", d.getInt("Cmd")).put("RequestID", d.getString("RequestID")).put("Data", data)).toString())
    }

    private fun push(topic: String, body: JSONObject) = sockets.last().send(body.put("Topic", "sdcp/$topic/m1").toString())

    private fun JSONObject.cmd() = getJSONObject("Data").getInt("Cmd")
    private fun JSONObject.payload() = getJSONObject("Data").getJSONObject("Data")
    private fun sent(cmd: Int) = received.filter { it.cmd() == cmd }.map { it.payload() }
    private fun videoCommands() = sent(CMD_VIDEO).map { it.getInt("Enable") }

    /** A printer that answers [cmd] with [data] and ignores everything else. */
    private fun answering(vararg replies: Pair<Int, String>) {
        val map = replies.toMap()
        printer = { ws, req -> map[req.cmd()]?.let { reply(ws, req, JSONObject(it)) } }
    }

    @Test
    fun repliesAreMatchedByRequestId() {
        answering(CMD_FILE_LIST to """{"Ack":0,"FileList":[{"name":"/local/a.gcode","type":1,"CreateTime":1,"FileSize":1,"TotalLayers":3}]}""")
        connect()
        val files = runBlocking { client.files() }
        assertEquals(listOf("/local/a.gcode"), files.map { it.path })
        // The first things a fresh connection asks for.
        assertEquals(listOf(CMD_ATTRIBUTES, CMD_STATUS), received.take(2).map { it.cmd() })
    }

    @Test
    fun aSilentPrinterIsAnErrorNotACancellation() {
        connect()
        runBlocking {
            try {
                client.files()
                fail("expected a timeout")
            } catch (e: PrinterException) {
                assertEquals(R.string.err_timeout, e.messageRes)
            }
        }
    }

    @Test
    fun requestsWithoutAConnectionFailAtOnce() {
        newClient(ip = "not a host") // unusable address: never connects, never crashes
        onMain { client.start() }
        runBlocking {
            try {
                client.history()
                fail("expected not connected")
            } catch (e: PrinterException) {
                assertEquals(R.string.err_not_connected, e.messageRes)
            }
        }
        onMain { client.reconnect() }
        assertFalse(client.connected.value)
    }

    @Test
    fun aDroppedConnectionFailsPendingRequestsAtOnce() {
        printer = { ws, req -> if (req.cmd() == CMD_HISTORY) ws.close(1001, "going away") }
        newClient(timeoutMs = 30_000)
        connect()
        runBlocking {
            val started = System.nanoTime()
            try {
                client.history()
                fail("expected the request to fail")
            } catch (e: PrinterException) {
                assertEquals(R.string.err_lost, e.messageRes)
            }
            assertTrue("failed fast", System.nanoTime() - started < 5_000_000_000L)
        }
    }

    @Test
    fun reconnectsAfterADropAndOnRequest() {
        server.enqueue(upgrade())
        server.enqueue(upgrade())
        connect()
        onMain { client.reconnect() } // already connected: nothing to do
        assertEquals(1, server.requestCount)
        drop()
        onMain { client.reconnect() } // skip the backoff
        waitFor("reconnect") { client.connected.value }
        drop()
        waitFor("backoff retry") { client.connected.value } // second retry waits 2 s
        assertEquals(3, server.requestCount)
    }

    @Test
    fun startAndStopAreRefCountedAndThePollerAsksForStatus() {
        newClient(pollMs = 50)
        connect()
        onMain { client.start() }
        waitFor("a status poll") { sent(CMD_STATUS).size >= 3 }
        onMain { client.stop() }
        assertTrue(client.connected.value)
        onMain { client.stop() }
        assertFalse(client.connected.value)
        onMain { client.stop(); client.reconnect() } // extra stop, and reconnect while not running
        assertFalse(client.connected.value)
    }

    @Test
    fun controlCommandsCarryClampedValues() {
        connect()
        onMain {
            assertTrue(client.pause() && client.resume() && client.stopPrint() && client.home("XYZ"))
            client.setSpeed(500); client.setNozzle(-1); client.setBed(200); client.setFans(150, 50, -5); client.setLight(true)
        }
        waitFor("all commands") { sent(CMD_CONTROL).size == 5 }
        assertEquals(1, sent(CMD_PAUSE).size + sent(CMD_RESUME).size - 1)
        assertEquals(1, sent(CMD_STOP).size)
        assertEquals("XYZ", sent(CMD_HOME)[0].getString("Axis"))
        val control = sent(CMD_CONTROL)
        assertEquals(160, control[0].getInt("PrintSpeedPct"))
        assertEquals(0, control[1].getInt("TempTargetNozzle"))
        assertEquals(110, control[2].getInt("TempTargetHotbed"))
        assertEquals("""{"ModelFan":100,"AuxiliaryFan":50,"BoxFan":0}""", control[3].getJSONObject("TargetFanSpeed").toString())
        assertEquals(1, control[4].getJSONObject("LightStatus").getInt("SecondLight"))
    }

    @Test
    fun managementCallsReportWhatThePrinterAccepted() {
        answering(
            CMD_RENAME_PRINTER to """{"Ack":0}""",
            CMD_DELETE_FILES to """{"Ack":0,"ErrData":["/local/a.gcode"]}""",
            CMD_DELETE_HISTORY to """{"Ack":0}""",
            CMD_FILE_INFO to """{"FileInfo":{"Thumbnail":"t.png","EstTime":60,"EstWeight":1.5,"TotalLayers":9}}""",
            CMD_START to """{"Ack":0}""",
            CMD_HISTORY to """{"Ack":0,"HistoryData":["t1"]}""",
            CMD_TASK_DETAILS to """{"HistoryDetailList":[{"TaskId":"t1","TaskName":"/local/a.gcode","TaskStatus":1}]}""",
        )
        connect()
        runBlocking {
            assertTrue(client.renamePrinter("Shop"))
            assertEquals("Shop", client.name.value)
            assertFalse(client.deleteFiles(listOf("/local/a.gcode")))
            assertTrue(client.deleteHistory(listOf("t1")))
            assertEquals(FileInfo("t.png", 60, 1.5, 9), client.fileInfo("/local/a.gcode"))
            assertEquals(0, client.startPrint("/local/a.gcode", true, false, 1))
            assertEquals(listOf("t1"), client.history().map { it.taskId })
        }
        val start = sent(CMD_START)[0]
        assertEquals(1, start.getInt("Calibration_switch"))
        assertEquals(0, start.getInt("Tlp_Switch"))
        assertEquals(1, start.getInt("PrintPlatformType"))

        answering(CMD_RENAME_PRINTER to """{"Ack":1}""", CMD_HISTORY to """{"Ack":0,"HistoryData":[]}""")
        runBlocking {
            assertFalse(client.renamePrinter("Nope"))
            assertEquals("Shop", client.name.value)
            assertEquals(emptyList<HistoryEntry>(), client.history())
        }
        answering(CMD_HISTORY to """{"Ack":0}""")
        runBlocking { assertEquals(emptyList<HistoryEntry>(), client.history()) }
    }

    @Test
    fun videoUrlFollowsThePrintersReplyAndALateOffIsIgnored() {
        var url = ""
        printer = { ws, req -> if (req.cmd() == CMD_VIDEO) reply(ws, req, JSONObject().put("Ack", 0).put("VideoUrl", url)) }
        connect()
        for ((given, expected) in listOf(
            "10.0.0.5:3031/video" to "http://10.0.0.5:3031/video",
            "https://cam/video" to "https://cam/video",
            "" to "http://${server.hostName}:$VIDEO_PORT/video",
        )) {
            url = given
            onMain { client.video(true) }
            waitFor("video url $expected") { client.videoUrl.value == expected }
            onMain { client.video(false) }
            waitFor("video off") { client.videoUrl.value == null && videoCommands().last() == 0 }
        }
        onMain { client.video(false) } // no viewers left: nothing to release
        assertNull(client.videoUrl.value)
    }

    @Test
    fun videoHandOverDoesNotToggleTheStreamAndShutdownReleasesIt() {
        connect()
        runBlocking {
            // A view handing the stream to another (entering fullscreen) within the grace period.
            withContext(main) { client.video(true); client.video(false); client.video(true) }
            delay(2_000)
            assertEquals(listOf(1), videoCommands())
            withContext(main) { client.shutdown() }
            withTimeout(5_000) { while (videoCommands() != listOf(1, 0)) delay(20) }
        }
    }

    @Test
    fun videoAskedForBeforeConnectingIsRequestedOnConnect() {
        onMain { client.video(true) }
        connect()
        // The first "on" had no socket to go out on; the fresh connection sends it.
        waitFor("video on") { videoCommands() == listOf(1) }
    }

    @Test
    fun statusNameAndTheCurrentJobsThumbnail() {
        val png = Buffer().write(image(64, 64))
        server.enqueue(MockResponse.Builder().body(png).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        val thumb = server.url("/thumb.png").toString()
        printer = { ws, req ->
            if (req.cmd() == CMD_TASK_DETAILS) reply(ws, req, JSONObject().put("HistoryDetailList", JSONArray()
                .put(JSONObject().put("TaskId", "other").put("Thumbnail", "x"))
                .put(JSONObject().put("TaskId", req.payload().getJSONArray("Id").getString(0)).put("Thumbnail", thumb))))
        }
        connect()
        push("attributes", JSONObject().put("Attributes", JSONObject().put("Name", "Shop")))
        waitFor("name") { client.name.value == "Shop" }
        push("attributes", JSONObject().put("Name", " ")) // blank names don't overwrite
        sockets.last().send("not json")
        push("unknown", JSONObject())
        push("response", JSONObject()) // no Data
        push("response", JSONObject().put("Data", JSONObject())) // no inner Data

        push("status", JSONObject().put("Status", JSONObject().put("PrintInfo", JSONObject().put("TaskId", "t1").put("Status", 13))))
        waitFor("thumbnail") { client.thumbnail.value != null }
        assertEquals(PrintState.PRINTING, client.status.value!!.print.status)
        assertEquals(64, client.thumbnail.value!!.width)

        // A new job drops the old picture; this one's thumbnail fails to load and stays empty.
        push("status", JSONObject().put("Status", JSONObject().put("PrintInfo", JSONObject().put("TaskId", "t2"))))
        waitFor("second thumbnail request") { server.requestCount == 3 }
        assertNull(client.thumbnail.value)
        push("status", JSONObject().put("Status", JSONObject())) // no job: no details request
        assertEquals("Shop", client.name.value)
        assertEquals(2, sent(CMD_TASK_DETAILS).size)
    }

    @Test
    fun aNewJobCancelsTheOldThumbnailDownload() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(image(8, 8))).headersDelay(3, TimeUnit.SECONDS).build())
        val thumb = server.url("/slow.png").toString()
        printer = { ws, req ->
            if (req.cmd() == CMD_TASK_DETAILS) reply(ws, req, JSONObject().put("HistoryDetailList", JSONArray()
                .put(JSONObject().put("TaskId", req.payload().getJSONArray("Id").getString(0)).put("Thumbnail", thumb))))
        }
        connect()
        push("status", JSONObject().put("Status", JSONObject().put("PrintInfo", JSONObject().put("TaskId", "t1"))))
        waitFor("the slow download") { server.requestCount == 2 }
        push("status", JSONObject().put("Status", JSONObject()))
        waitFor("the job to clear") { client.status.value?.print?.taskId == "" }
        runBlocking { delay(3_500) } // the old download would have landed by now
        assertNull(client.thumbnail.value)
    }

    private fun image(w: Int, h: Int) = image(w, h, Bitmap.CompressFormat.PNG)
}
