package app.centauri.remote

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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Runs [SdcpClient] against a real local WebSocket server playing the printer. */
class SdcpClientTest {
    private val server = MockWebServer()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = executor.asCoroutineDispatcher() // stands in for the app's main thread
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val received = CopyOnWriteArrayList<JSONObject>()

    /** What the fake printer does with each request; default: ignore it. */
    private var printer: (WebSocket, JSONObject) -> Unit = { _, _ -> }

    private lateinit var client: SdcpClient

    @Before
    fun setUp() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text)
                received += frame
                printer(webSocket, frame)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null) // answer the close handshake so the server can stop
            }
        }).build())
        server.start()
        client = SdcpClient(
            OkHttpClient(), Printer("test", server.hostName, "m1", ""), scope,
            wsPort = server.port, requestTimeoutMs = 300,
        )
    }

    @After
    fun tearDown() {
        runBlocking { withContext(main) { client.shutdown() } }
        scope.cancel()
        executor.shutdown()
        server.close()
    }

    private fun connect() = runBlocking {
        withContext(main) { client.start() }
        withTimeout(5_000) { client.connected.first { it } }
    }

    private fun reply(ws: WebSocket, req: JSONObject, data: JSONObject) {
        val d = req.getJSONObject("Data")
        ws.send(JSONObject().put("Topic", "sdcp/response/m1").put("Data", JSONObject()
            .put("Cmd", d.getInt("Cmd")).put("RequestID", d.getString("RequestID")).put("Data", data)).toString())
    }

    private fun videoCommands() = received.map { it.getJSONObject("Data") }
        .filter { it.getInt("Cmd") == CMD_VIDEO }.map { it.getJSONObject("Data").getInt("Enable") }

    @Test
    fun repliesAreMatchedByRequestId() {
        printer = { ws, req ->
            if (req.getJSONObject("Data").getInt("Cmd") == CMD_FILE_LIST) reply(ws, req, JSONObject(
                """{"Ack":0,"FileList":[{"name":"/local/a.gcode","type":1,"CreateTime":1,"FileSize":1,"TotalLayers":3}]}""",
            ))
        }
        connect()
        val files = runBlocking { client.files() }
        assertEquals(listOf("/local/a.gcode"), files.map { it.path })
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
    fun aDroppedConnectionFailsPendingRequestsAtOnce() {
        printer = { ws, req -> if (req.getJSONObject("Data").getInt("Cmd") == CMD_HISTORY) ws.cancel() }
        val longTimeout = SdcpClient(
            OkHttpClient(), Printer("test", server.hostName, "m1", ""), scope,
            wsPort = server.port, requestTimeoutMs = 30_000,
        ).also { client = it }
        connect()
        runBlocking {
            val started = System.nanoTime()
            try {
                longTimeout.history()
                fail("expected the request to fail")
            } catch (e: PrinterException) {
                assertEquals(R.string.err_lost, e.messageRes)
            }
            assertTrue("failed fast", System.nanoTime() - started < 5_000_000_000L)
        }
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
}
