package app.centauri.remote

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** What Files, the start form and History work out and say. */
@RunWith(RobolectricTestRunner::class)
class LibraryTextTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val res: Resources get() = app.resources

    private fun entry(path: String, outcome: Outcome, seconds: Long = 3600, timelapse: String = "") =
        HistoryEntry("t$path", path, "", 1_800_000_000, 1_800_000_000 + seconds, outcome == Outcome.DONE, 1, 2,
            outcome = outcome, timelapseUrl = timelapse)

    @Test
    fun aStopWithAReasonIsAFailure() {
        assertEquals(Outcome.DONE, outcomeOf(1, 0))
        assertEquals(Outcome.FAILED, outcomeOf(2, 0))
        assertEquals(Outcome.FAILED, outcomeOf(3, 6))
        assertEquals(Outcome.STOPPED, outcomeOf(3, 0))
        assertEquals(Outcome.STOPPED, outcomeOf(0, 0))
        // Entries built without a TaskStatus (the demo) go by completed and the reason.
        assertEquals(Outcome.FAILED, HistoryEntry("t", "p", "", 0, 0, false, 1, 2, errorReason = 3).outcome)
        assertEquals(Outcome.DONE, HistoryEntry("t", "p", "", 0, 0, true, 1, 2).outcome)
    }

    @Test
    fun filtersPickByOutcomeAndTimelapse() {
        val done = entry("/local/a.gcode", Outcome.DONE, timelapse = "http://p/v.mp4")
        val failed = entry("/local/b.gcode", Outcome.FAILED)
        val stopped = entry("/local/c.gcode", Outcome.STOPPED)
        val all = listOf(done, failed, stopped)
        assertEquals(all, all.filter { it.shownUnder(HistoryFilter.ALL) })
        assertEquals(listOf(done), all.filter { it.shownUnder(HistoryFilter.COMPLETED) })
        assertEquals(listOf(failed), all.filter { it.shownUnder(HistoryFilter.FAILED) })
        assertEquals(listOf(done), all.filter { it.shownUnder(HistoryFilter.TIMELAPSES) })
    }

    @Test
    fun totalsCountGramsOfCompletedJobsByFilament() {
        val pla = "/local/ECC_0.4_a_Elegoo PLA+ _0.2_1h.gcode"
        val petg = "/local/ECC_0.4_b_Elegoo Rapid PETG _0.2_1h.gcode"
        val entries = listOf(
            entry(pla, Outcome.DONE, 3600), entry(pla, Outcome.DONE, 1800), entry(petg, Outcome.FAILED, 600),
            entry("/local/plain.gcode", Outcome.DONE, 600), entry(petg, Outcome.DONE, -5),
        )
        val grams = mapOf(pla to 10.0, petg to 40.0, "/local/plain.gcode" to 99.0)
        val t = historyTotals(entries) { grams[it.path] }
        assertEquals(5, t.prints)
        assertEquals(6600L, t.seconds) // a negative span (clock jump) counts as none
        assertEquals(4, t.done)
        assertEquals(mapOf("Elegoo PLA+" to 20.0, "Elegoo Rapid PETG" to 40.0), t.grams)
        assertEquals(listOf("5 prints · 1h 50m · 80% successful", "Elegoo PLA+ 20 g · Elegoo Rapid PETG 40 g"), t.lines(res))

        val none = historyTotals(listOf(entry(pla, Outcome.STOPPED))) { null }
        assertEquals(listOf("1 print · 1h 0m · 0% successful"), none.lines(res))
        assertEquals("0 prints · 0m · 0% successful", historyTotals(emptyList()) { null }.lines(res).single())
    }

    @Test
    fun theTrayHoldingTheFilesFilamentIsPicked() {
        val pla = Tray(0, 0, true, "PLA", "PLA", "", null)
        val plaPlus = Tray(0, 1, true, "PLA+", "PLA+", "", null)
        val petg = Tray(0, 2, true, "PETG", "Rapid PETG", "", null)
        val blank = Tray(0, 3, true, "", "", "", null)
        val loaded = listOf(pla, plaPlus, petg, blank)
        assertEquals(petg, matchingTray("Elegoo Rapid PETG", loaded))
        assertEquals(plaPlus, matchingTray("Elegoo PLA+", loaded)) // the longest type wins
        assertEquals(pla, matchingTray("elegoo pla", loaded))
        assertNull(matchingTray("TPU", loaded))
        assertNull(matchingTray(null, loaded))

        assertTrue(trayMismatch("TPU", loaded))
        assertFalse(trayMismatch("PETG", loaded))
        assertFalse(trayMismatch(null, loaded))
        assertFalse(trayMismatch("TPU", emptyList())) // no Canvas, or the Lab is off: nothing to warn about
    }

    @Test
    fun startChoicesRoundTripAndSummarise() {
        val c = StartChoices(1, autoLevel = true, timelapse = false)
        assertEquals(c, StartChoices.decode(c.encode()))
        assertNull(StartChoices.decode(null))
        assertNull(StartChoices.decode("1,true"))
        assertNull(StartChoices.decode("x,true,false"))
        assertEquals("Smooth · Auto leveling", c.summary(res))
        assertEquals("Textured · Timelapse", StartChoices(0, autoLevel = false, timelapse = true).summary(res))
    }

    @Test
    fun freeSpaceAndUsbFromTheAttributes() {
        assertTrue(freeSpace(app, JSONObject("""{"RemainingMemory":5300000000}"""))!!.endsWith("free on the printer"))
        assertNull(freeSpace(app, JSONObject("{}")))
        assertNull(freeSpace(app, null))
        assertTrue(usbConnected(JSONObject("""{"UsbDiskStatus":1}""")))
        assertFalse(usbConnected(JSONObject("""{"UsbDiskStatus":0}""")))
        assertFalse(usbConnected(null))
    }

    @Test
    fun aTimelapseGoesToTheSystemPlayer() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get() // the app calls it from its Activity
        assertTrue(playVideo(activity, "http://10.0.0.5/video/t1.mp4"))
        val sent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, sent.action)
        assertEquals("video/*", sent.type)
        assertEquals("http://10.0.0.5/video/t1.mp4", sent.dataString)

        shadowOf(app).checkActivities(true) // no player installed
        assertFalse(playVideo(activity, "http://10.0.0.5/video/t1.mp4"))
    }
}
