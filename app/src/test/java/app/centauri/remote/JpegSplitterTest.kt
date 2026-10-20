package app.centauri.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class JpegSplitterTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun framesSplitAcrossChunksAndBoundaryJunk() {
        val frame1 = bytes(0xFF, 0xD8, 1, 2, 0xFF, 0xD9)
        val frame2 = bytes(0xFF, 0xD8, 3, 0xFF, 0xD9)
        val stream = "--boundary\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + frame1 +
            "\r\n--boundary\r\n\r\n".toByteArray() + frame2
        val s = JpegSplitter()
        // Feed in 3-byte chunks so markers straddle chunk edges.
        val out = stream.toList().chunked(3).flatMap { c -> s.feed(c.toByteArray(), c.size) }
        assertEquals(2, out.size)
        assertArrayEquals(frame1, out[0])
        assertArrayEquals(frame2, out[1])
    }

    @Test
    fun oversizeFrameResyncs() {
        val s = JpegSplitter(maxFrame = 4)
        assertEquals(0, s.feed(bytes(0xFF, 0xD8, 1, 2, 3, 4, 5), 7).size)
        val ok = bytes(0xFF, 0xD8, 0xFF, 0xD9)
        assertArrayEquals(ok, s.feed(ok, 4).single())
    }
}
