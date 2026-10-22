package app.centauri.remote

import android.graphics.Bitmap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    }

    @Test
    fun mjpegStreamYieldsEachFrame() {
        val jpeg = image(32, 24, Bitmap.CompressFormat.JPEG)
        val body = Buffer().write("--b\r\n".toByteArray()).write(jpeg).write("\r\n--b\r\n".toByteArray()).write(jpeg)
        server.enqueue(MockResponse.Builder().body(body).build())
        val frames = runBlocking { withTimeout(5_000) { mjpegFrames(http, server.url("/video").toString()).toList() } }
        assertTrue(frames.isNotEmpty())
        assertEquals(32, frames.last().width)
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
}
