package app.centauri.remote

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException

fun image(w: Int, h: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray =
    ByteArrayOutputStream().also { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(format, 80, it) }.toByteArray()

/** Thumbnail downloads and the camera stream, against a local HTTP server. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HttpTest {
    private val server = MockWebServer().apply { start() }
    private val http = OkHttpClient()

    @After
    fun tearDown() = server.close()

    private fun fetchFails(url: String, maxBytes: Long = 1024) = runBlocking {
        try {
            http.fetchBytes(url, maxBytes)
            fail("expected an IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun fetchReturnsTheBodyAndRejectsErrorsAndOversizedBodies() {
        server.enqueue(MockResponse.Builder().body("png").build())
        assertEquals("png", runBlocking { http.fetchBytes(server.url("/a").toString()) }.decodeToString())
        server.enqueue(MockResponse.Builder().code(404).build())
        fetchFails(server.url("/missing").toString())
        server.enqueue(MockResponse.Builder().body("x".repeat(2000)).build())
        fetchFails(server.url("/big").toString())
        // No Content-Length up front: the size check happens while reading.
        server.enqueue(MockResponse.Builder().chunkedBody("x".repeat(2000), 100).build())
        fetchFails(server.url("/chunked").toString())
        fetchFails("not a url")
        fetchFails("http://127.0.0.1:1/refused")
        // The app's client doesn't follow a printer-supplied URL to another host.
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://elsewhere.example/x").build())
        runBlocking {
            try {
                Hub.http.fetchBytes(server.url("/moved").toString())
                fail("redirect followed")
            } catch (_: IOException) {
            }
        }
        assertEquals(1, server.requestCount - 4) // only the 302 itself was requested
    }

    @Test
    fun decodeDownsamplesToTheTargetAndRejectsJunk() {
        val bmp = decodeScaled(image(512, 512), 100)!!
        assertEquals(128, bmp.width) // 512 / 4: halving again would drop below 100
        assertNull(decodeScaled("junk".toByteArray(), 100))
        // A long thin image is downsampled on its longer side, not decoded at full size.
        assertEquals(128, decodeScaled(image(4096, 16), 100)!!.width) // 4096 / 32; was decoded at full width
    }

    @Test
    fun mjpegErrorPageFailsTheStream() {
        server.enqueue(MockResponse.Builder().code(503).body("busy").build())
        val result = runBlocking { withTimeout(5_000) { runCatching { mjpegFrames(http, server.url("/video").toString()).toList() } } }
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun mjpegStreamYieldsEachFrame() {
        val jpeg = image(32, 24, Bitmap.CompressFormat.JPEG)
        val body = Buffer().write("--b\r\n".toByteArray()).write(jpeg).write("\r\n--b\r\n".toByteArray()).write(jpeg)
        server.enqueue(MockResponse.Builder().body(body).build())
        val frames = runBlocking { withTimeout(5_000) { mjpegFrames(http, server.url("/video").toString()).toList() } }
        assertTrue(frames.isNotEmpty())
        assertEquals(32, frames.last().width)
        assertEquals(Bitmap.Config.RGB_565, frames.last().config)
    }

    @Test
    fun mjpegStreamFailsWhenTheCameraIsUnreachable() {
        runBlocking {
            try {
                mjpegFrames(http, "http://127.0.0.1:1/video").first()
                fail("expected an IOException")
            } catch (_: IOException) {
            }
        }
    }

    /** The demo printer with a live camera at [url] (null: the printer never answers). */
    private class CameraLink(url: String?) : PrinterLink by DemoPrinter(CoroutineScope(Dispatchers.Unconfined)) {
        override val stillFrame: Int? = null
        override val videoUrl = MutableStateFlow(url)
        var users = 0
        override fun video(enable: Boolean) { users += if (enable) 1 else -1 }
    }

    @Test
    fun alertSnapshotsAreOneScaledFrameAndReleaseTheCamera() {
        val ctx = RuntimeEnvironment.getApplication()
        server.enqueue(MockResponse.Builder().body(Buffer().write(image(1920, 1080, Bitmap.CompressFormat.JPEG))).build())
        val live = CameraLink(server.url("/video").toString())
        val frame = runBlocking { snapshot(ctx, live, http) }!!
        assertEquals(960 to 540, frame.width to frame.height)
        assertEquals(0, live.users)

        server.enqueue(MockResponse.Builder().body(Buffer().write(image(320, 240, Bitmap.CompressFormat.JPEG))).build())
        assertEquals(320, runBlocking { snapshot(ctx, CameraLink(server.url("/video").toString()), http) }!!.width)
        assertNull(runBlocking { snapshot(ctx, CameraLink("http://127.0.0.1:1/video"), http) })
        val silent = CameraLink(null)
        runTest { assertNull(snapshot(ctx, silent, http)) } // virtual time: the 10 s timeout passes at once
        assertEquals(0, silent.users)
        assertNotNull(runBlocking { snapshot(ctx, DemoPrinter(CoroutineScope(Dispatchers.Unconfined)), http) })
        assertNull(DemoPrinter(CoroutineScope(Dispatchers.Unconfined)).attributes.value)
        assertNull(DemoPrinter(CoroutineScope(Dispatchers.Unconfined)).clock.value)
    }
}
