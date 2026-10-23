package app.centauri.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs [block], turning printer/network failures into null while letting cancellation through. */
internal suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Thumbnails are small PNGs; anything bigger is not a thumbnail. */
private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024

/**
 * GETs [url] and returns its body, cancelling the HTTP call if the coroutine is cancelled
 * (a row scrolled away, a screen left). Bad URLs, HTTP errors and oversized bodies are IOExceptions.
 */
suspend fun OkHttpClient.fetchBytes(url: String, maxBytes: Long = MAX_IMAGE_BYTES, accept: String? = null): ByteArray {
    val request = try {
        Request.Builder().url(url).apply { accept?.let { header("Accept", it) } }.build()
    } catch (e: IllegalArgumentException) {
        throw IOException("Bad URL from printer: $url", e)
    }
    val call = newCall(request)
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $url")
                        val length = it.body.contentLength()
                        if (length > maxBytes) throw IOException("Body too large ($length bytes) for $url")
                        it.body.source().apply { request(maxBytes + 1) }.buffer.let { buf ->
                            if (buf.size > maxBytes) throw IOException("Body too large for $url")
                            buf.readByteArray()
                        }
                    }
                }
                result.fold(cont::resume, cont::resumeWithException)
            }
        })
    }
}

/**
 * Decodes at the largest power-of-two downsample whose longer side still covers [targetPx], so views
 * draw cheap bitmaps and an odd-shaped image can't decode at full size.
 */
fun decodeScaled(bytes: ByteArray, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.also { it.prepareToDraw() }
}

/**
 * The firmware's logo, as the printer's own web UI serves it at [host] (its IP); stock Elegoo firmware
 * and OpenCentauri ship different ones. Null when there is none or it doesn't decode.
 */
suspend fun printerLogo(http: OkHttpClient, host: String, targetPx: Int = 256): Bitmap? = orNull {
    val bytes = http.fetchBytes("http://$host/assets/images/network/logo.png", maxBytes = 512L * 1024)
    withContext(Dispatchers.Default) { decodeScaled(bytes, targetPx) }
}
