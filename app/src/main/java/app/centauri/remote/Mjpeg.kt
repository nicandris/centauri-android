package app.centauri.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Cuts a byte stream into JPEG frames on the SOI (FF D8) / EOI (FF D9) markers, so the
 * multipart boundary format of the printer's MJPEG stream doesn't matter.
 */
// ponytail: a JPEG with an embedded EXIF thumbnail would be cut at the inner EOI; the
// printer's camera frames carry none. Parse multipart Content-Length if that changes.
class JpegSplitter(private val maxFrame: Int = 4 shl 20) {
    private val buf = ByteArrayOutputStream()
    private var inFrame = false
    private var prev = -1

    fun feed(bytes: ByteArray, n: Int): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (!inFrame) {
                if (prev == 0xFF && b == 0xD8) {
                    inFrame = true
                    buf.reset(); buf.write(0xFF); buf.write(0xD8)
                }
            } else {
                buf.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    frames += buf.toByteArray()
                    inFrame = false
                } else if (buf.size() > maxFrame) {
                    inFrame = false // no EOI in sight: resync on the next SOI
                }
            }
            prev = b
        }
        return frames
    }
}

fun mjpegFrames(http: OkHttpClient, url: String): Flow<Bitmap> = callbackFlow<ByteArray> {
    val call = http.newCall(Request.Builder().url(url).build())
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { close(e) }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val input = it.body.byteStream()
                val splitter = JpegSplitter()
                val chunk = ByteArray(16 * 1024)
                try {
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        for (jpeg in splitter.feed(chunk, n)) trySend(jpeg)
                    }
                    close()
                } catch (e: IOException) { close(e) }
            }
        }
    })
    awaitClose { call.cancel() }
}
    // A slow collector only ever sees the newest frame: frames it would drop are skipped as bytes,
    // before decoding, and decoding happens off the main thread.
    .conflate()
    .mapNotNull { jpeg -> BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
    .flowOn(Dispatchers.Default)
    .conflate()
