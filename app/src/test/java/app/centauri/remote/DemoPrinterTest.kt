package app.centauri.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulated printer, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoPrinterTest {
    private fun TestScope.demo() = DemoPrinter(backgroundScope).also { it.start(); runCurrent() }
    private val DemoPrinter.state get() = status.value!!.print.status

    @Test
    fun startsMidPrintAndIsRefCounted() = runTest {
        val d = demo()
        assertTrue(d.connected.value)
        assertEquals(PrintState.PRINTING, d.state)
        assertTrue(d.status.value!!.print.progress in 30..40)
        d.start()
        d.stop()
        assertTrue(d.connected.value)
        d.stop()
        assertFalse(d.connected.value)
        d.stop() // extra stops are harmless
        d.reconnect()
        d.video(true)
        assertEquals(R.drawable.demo_camera, d.stillFrame)
        assertTrue(d.hasCamera)
        assertEquals(null, d.videoUrl.value)
        assertEquals(null, d.thumbnail.value)
    }

    @Test
    fun pauseResumeAndStopGoThroughTransitions() = runTest {
        val d = demo()
        assertFalse(d.resume())
        assertTrue(d.pause())
        assertEquals(PrintState.PAUSING, d.state)
        advanceTimeBy(1_600)
        assertEquals(PrintState.PAUSED, d.state)
        assertFalse(d.pause())
        assertTrue(d.resume())
        advanceTimeBy(1_600)
        assertEquals(PrintState.PRINTING, d.state)
        val before = d.history().size
        assertTrue(d.stopPrint())
        advanceTimeBy(1_600)
        assertEquals(PrintState.STOPPED, d.state)
        assertFalse(d.stopPrint())
        val stopped = d.history()
        assertEquals(before + 1, stopped.size)
        assertFalse(stopped.first().completed)
        assertEquals(0.0, d.status.value!!.nozzleTarget, 0.0)
        assertTrue(d.home("XYZ"))
    }

    @Test
    fun aPrintRunsToCompletionAndANewOneCanStart() = runTest {
        val d = demo()
        assertFalse(d.home("Z"))
        assertEquals(1, d.startPrint("/local/x.gcode", false, false, 0)) // busy
        d.setSpeed(500) // clamped to 160 %: finishes faster
        assertEquals(160, d.status.value!!.print.speedPct)
        advanceTimeBy(10 * 60_000)
        assertEquals(PrintState.COMPLETE, d.state)
        assertEquals(0, d.status.value!!.machine)
        assertTrue(d.history().first().completed)
        val file = d.files().last()
        assertTrue(d.jog("X", 1.0)) // idle: the head can move
        assertEquals(0, d.startPrint(file.path, true, true, 1))
        assertEquals(PrintState.PREHEATING, d.state)
        assertFalse(d.jog("X", 1.0))
        assertFalse(d.stopFeed())
        assertTrue(d.skipPreheat())
        assertEquals(PrintState.PRINTING, d.state)
        assertFalse(d.skipPreheat()) // only while preheating
        advanceTimeBy(1_600) // the cancelled preheat doesn't come back
        assertEquals(PrintState.PRINTING, d.state)
        assertEquals(file.path.substringAfterLast('/'), d.status.value!!.print.file)
    }

    @Test
    fun theDemoCanvasFeedsFromTheChosenTray() = runTest {
        val d = demo()
        val canvas = d.canvas.value!!
        assertTrue(d.status.value!!.canvas)
        assertEquals(listOf(true, true, true, false), canvas.trays.map { it.loaded })
        assertTrue(canvas.isActive(canvas.trays[0]))
        d.stopPrint()
        advanceTimeBy(1_600)
        val file = d.files().first().path
        assertEquals(1, d.startPrint(file, false, false, 0, canvas.trays[3])) // empty tray
        assertEquals(0, d.startPrint(file, false, false, 0, canvas.trays[2]))
        assertEquals(2, d.canvas.value!!.activeTray)
        d.stop()
        assertEquals(null, d.canvas.value)
    }

    @Test
    fun settingsAndManagementCalls() = runTest {
        val d = demo()
        d.setLight(false); d.setNozzle(999); d.setBed(-5); d.setFans(150, -1, 50)
        val st = d.status.value!!
        assertFalse(st.light)
        assertEquals(320.0, st.nozzleTarget, 0.0)
        assertEquals(0.0, st.bedTarget, 0.0)
        assertEquals(listOf(100, 0, 50), listOf(st.modelFan, st.auxFan, st.boxFan))
        advanceTimeBy(20_000) // heaters drift toward their new targets
        assertTrue(d.status.value!!.nozzle > 220)

        assertTrue(d.renamePrinter("Shop"))
        assertEquals("Shop", d.name.value)
        val files = d.files()
        val info = d.fileInfo(files[0].path)
        assertTrue(info.estS > 0 && info.thumbnailRes != null)
        assertTrue(d.deleteFiles(listOf(files[0].path)))
        assertEquals(files.size - 1, d.files().size)
        val history = d.history()
        assertTrue(d.deleteHistory(listOf(history[0].taskId)))
        assertEquals(history.size - 1, d.history().size)
        assertTrue(d.jobThumbnailRes != null)
        assertEquals(history[1], d.taskDetails(history[1].taskId))
        assertEquals(null, d.taskDetails("nothing"))
    }
}
