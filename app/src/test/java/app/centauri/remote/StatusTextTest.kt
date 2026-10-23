package app.centauri.remote

import android.content.res.Resources
import android.graphics.Bitmap
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** What the top bar and the Status screen say, and the finish-time helpers. */
@RunWith(RobolectricTestRunner::class)
class StatusTextTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val res: Resources get() = app.resources

    private fun status(print: Int, machine: Int = 0, file: String = "benchy.gcode", progress: Int = 36) = parseStatus(
        JSONObject().put("CurrentStatus", org.json.JSONArray().put(machine))
            .put("PrintInfo", JSONObject().put("Status", print).put("Filename", file).put("Progress", progress)),
    )

    @Test
    fun theSpeedIsNamedWhenItIsAPreset() {
        assertEquals("Sport · 130%", speedLabel(res, 130))
        assertEquals("Running at 115% speed", speedLabel(res, 115))
    }

    @Test
    fun theConnectionLineUnderThePrinterName() {
        assertEquals("Connecting…", connectionLine(res, connected = true, st = null))
        assertEquals("Connecting…", connectionLine(res, connected = false, st = null))
        assertEquals("Reconnecting…", connectionLine(res, connected = false, st = status(PrintState.PRINTING)))
        assertEquals("Printing · 36%", connectionLine(res, true, status(PrintState.PRINTING)))
        assertEquals("Complete", connectionLine(res, true, status(PrintState.COMPLETE)))
        assertEquals("Ready to print", connectionLine(res, true, status(PrintState.IDLE, file = "")))
        assertEquals("Auto leveling", connectionLine(res, true, status(PrintState.IDLE, machine = 5, file = "")))
    }

    @Test
    fun startUpStepsAndWhatToDo() {
        assertEquals(0, printStep(PrintState.PREHEATING))
        assertEquals(0, printStep(PrintState.PREHEATING_DONE))
        assertEquals(1, printStep(PrintState.AUTO_LEVELING))
        assertEquals(1, printStep(PrintState.LEVELING_DONE))
        assertNull(printStep(PrintState.PRINTING))
        assertEquals(R.string.todo_runout, whatToDo(3))
        assertEquals(R.string.todo_runout, whatToDo(45))
        assertEquals(R.string.todo_jam, whatToDo(6))
        assertEquals(R.string.todo_detached, whatToDo(18))
        listOf(1, 33, 34).forEach { assertEquals(R.string.todo_heat, whatToDo(it)) }
        assertEquals(R.string.todo_generic, whatToDo(null))
        assertEquals(R.string.todo_generic, whatToDo(99))
    }

    private val hhmm: (ZonedDateTime) -> String = { it.format(DateTimeFormatter.ofPattern("HH:mm")) }

    // A Monday.
    private fun at(hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, 19, hour, minute, 0, 0, ZoneOffset.UTC)

    @Test
    fun finishTimeIsRelativeWhenCloseAndNamesTheDayPastMidnight() {
        assertEquals("Finishes in 42m", finishWhen(res, at(10), 42 * 60 + 5, hhmm))
        assertEquals("Finishes in 1m", finishWhen(res, at(10), 20, hhmm))
        assertEquals("Finishes in 1m", finishWhen(res, at(10), -5, hhmm))
        assertEquals("Finishes 12:00", finishWhen(res, at(10), RELATIVE_ETA_S * 2, hhmm))
        assertEquals("Finishes 11:00", finishWhen(res, at(10), RELATIVE_ETA_S, hhmm))
        assertEquals("Finishes tomorrow 01:30", finishWhen(res, at(22), 3 * 3600 + 1800, hhmm))
        assertEquals("Finishes Wednesday 04:00", finishWhen(res, at(22), 30 * 3600, hhmm))
    }

    @Test
    fun calendarAndAlarmAtTheFinish() {
        val cal = calendarIntent("benchy finishes", 1_000_000L)
        assertEquals(CalendarContract.Events.CONTENT_URI, cal.data)
        assertEquals(1_000_000L, cal.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
        assertEquals(1_900_000L, cal.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0))
        assertEquals("benchy finishes", cal.getStringExtra(CalendarContract.Events.TITLE))
        val alarm = alarmIntent("benchy finishes", at(23, 45))
        assertEquals(AlarmClock.ACTION_SET_ALARM, alarm.action)
        assertEquals(23, alarm.getIntExtra(AlarmClock.EXTRA_HOUR, 0))
        assertEquals(45, alarm.getIntExtra(AlarmClock.EXTRA_MINUTES, 0))
        assertEquals("benchy finishes", alarm.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        // It rings at the next such time: a finish a day or more away would ring early.
        assertTrue(alarmFits(23 * 3600 + 59 * 60))
        assertFalse(alarmFits(24 * 3600))
    }

    private fun entry(path: String, begin: Long, outcome: Outcome = Outcome.DONE, reason: Int = 0) =
        HistoryEntry("t$begin", path, "", begin, begin + 60, outcome, 1, 2, errorReason = reason)

    @Test
    fun recentJobsAndTheLastOutcome() {
        val h = listOf(
            entry("/local/a.gcode", 9), entry("/local/b.gcode", 8), entry("/local/a.gcode", 7),
            entry("/local/c.gcode", 6), entry("/local/d.gcode", 5),
        )
        assertEquals(listOf("/local/a.gcode", "/local/b.gcode", "/local/c.gcode"), recentJobs(h).map { it.path })
        assertEquals(1, recentJobs(h, 1).size)
        assertEquals("Last print: a · Completed", lastOutcome(res, h[0]))
        assertEquals("Last print: a · Cancelled", lastOutcome(res, entry("/local/a.gcode", 1, Outcome.STOPPED)))
        // A failure the printer gave no reason for still reads as one, as in History.
        assertEquals("Last print: a · Failed", lastOutcome(res, entry("/local/a.gcode", 1, Outcome.FAILED)))
        assertEquals(
            "Last print: a · The print came loose from the plate", lastOutcome(res, entry("/local/a.gcode", 1, Outcome.FAILED, reason = 18)),
        )
    }

    @Test
    fun zoomedPanStaysInsideThePicture() {
        assertEquals(0f, clampPan(30f, 1f, 200f))
        assertEquals(0f, clampPan(30f, 0.5f, 200f))
        assertEquals(50f, clampPan(80f, 1.5f, 200f))
        assertEquals(-50f, clampPan(-80f, 1.5f, 200f))
        assertEquals(20f, clampPan(20f, 1.5f, 200f))
    }

    @Test
    fun theStatusFileLivesUnderLocal() {
        assertEquals("/local/benchy.gcode", localPath("benchy.gcode"))
        assertEquals("/local/benchy.gcode", localPath("/local/benchy.gcode"))
    }

    @Test
    fun aSavedFrameGoesToPicturesAndAFailedOneLeavesNothing() = runBlocking {
        val frame = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertTrue(saveFrame(app, frame, "centauri-1"))
        val cr = shadowOf(app.contentResolver)
        val insert = cr.insertStatements.single()
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, insert.uri)
        assertEquals("centauri-1.jpg", insert.contentValues.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals("Pictures/Centauri", insert.contentValues.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        // Shown in the gallery only once written.
        assertEquals(0, cr.updateStatements.single().contentValues.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertTrue(cr.deleteStatements.isEmpty())

        // A storage error while writing: writing to whichever row the next insert gets fails.
        val full = object : java.io.OutputStream() { override fun write(b: Int) = throw java.io.IOException("full") }
        (2..20).forEach { cr.registerOutputStream(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.toLong()), full) }
        assertFalse(saveFrame(app, frame, "centauri-2"))
        assertEquals(1, cr.deleteStatements.size)
        assertEquals(1, cr.updateStatements.size)
    }
}
