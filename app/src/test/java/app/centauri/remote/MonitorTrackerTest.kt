package app.centauri.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorTrackerTest {
    private fun st(status: Int, progress: Int = 10, layer: Int = 5) = parseStatus(JSONObject().put("PrintInfo",
        JSONObject().put("Status", status).put("Progress", progress).put("CurrentLayer", layer).put("Filename", "a.gcode")))

    @Test
    fun theMonitorRunsOnlyForAPrintWithNotificationsOn() {
        for (active in listOf(true, false)) for (perm in listOf(true, false)) {
            assertEquals(MonitorAction.STOP, monitorAction(notify = false, active = active, needsPermission = perm))
        }
        assertEquals(MonitorAction.NONE, monitorAction(notify = true, active = false, needsPermission = true))
        assertEquals(MonitorAction.ASK_PERMISSION, monitorAction(notify = true, active = true, needsPermission = true))
        assertEquals(MonitorAction.START, monitorAction(notify = true, active = true, needsPermission = false))
    }

    @Test
    fun repostsOnlyWhenSomethingVisibleChanges() {
        val t = MonitorTracker()
        assertEquals(MonitorStep(repost = true), t.next(st(PrintState.PRINTING), true))
        assertEquals(MonitorStep(), t.next(st(PrintState.PRINTING), true))
        assertEquals(MonitorStep(repost = true), t.next(st(PrintState.PRINTING, progress = 11), true))
        assertEquals(MonitorStep(repost = true), t.next(st(PrintState.PRINTING, progress = 11), false))
    }

    @Test
    fun alertsOnTransitionsAndStopsOnceIdle() {
        val t = MonitorTracker()
        t.next(st(PrintState.PRINTING), true)
        assertEquals(MonitorStep(R.string.alert_paused, repost = true), t.next(st(PrintState.PAUSED), true))
        assertEquals(MonitorStep(resumed = true, repost = true), t.next(st(PrintState.RESUMING), true))
        assertEquals(MonitorStep(repost = true), t.next(st(PrintState.PRINTING), true))
        t.next(st(PrintState.PAUSED), true)
        assertEquals(MonitorStep(resumed = true, repost = true), t.next(st(PrintState.PRINTING), true))
        t.next(st(PrintState.PAUSED), true)
        assertEquals(MonitorStep(R.string.alert_finished, stop = true, finished = true), t.next(st(PrintState.COMPLETE), true))
    }

    @Test
    fun noStatusYetOrOfflineKeepsTheServiceRunning() {
        val t = MonitorTracker()
        assertEquals(MonitorStep(repost = true), t.next(null, false))
        // The first status is the baseline, not a transition; idle but offline doesn't stop.
        assertEquals(MonitorStep(repost = true), t.next(st(PrintState.COMPLETE), false))
        // Losing the status keeps the last code, so the comeback isn't a fresh "finished".
        t.next(null, false)
        assertEquals(MonitorStep(stop = true), t.next(st(PrintState.COMPLETE), true))
    }

    private fun job(status: Int = PrintState.PRINTING, layer: Int = 50, elapsed: Int = 0, total: Int = 3600) =
        parseStatus(JSONObject().put("PrintInfo", JSONObject().put("Status", status).put("CurrentLayer", layer)
            .put("CurrentTicks", elapsed).put("TotalTicks", total).put("Filename", "a.gcode")))

    @Test
    fun timeLeftFiresOnceWhenTheJobCrossesTheChosenMinutes() {
        var remind = 600
        val t = MonitorTracker(remindBeforeS = { remind })
        assertFalse(t.next(job(elapsed = 2000), true).timeLeft) // 26 min left: armed
        // Paused inside the window: wait for the print to resume.
        assertFalse(t.next(job(PrintState.PAUSED, elapsed = 3000), true).timeLeft)
        assertTrue(t.next(job(elapsed = 3000), true).timeLeft)
        assertFalse(t.next(job(elapsed = 3100), true).timeLeft)
        // A monitor that starts inside the window, or a print shorter than it, stays quiet.
        assertFalse(MonitorTracker(remindBeforeS = { remind }).next(job(elapsed = 3100), true).timeLeft)
        // Off, or a job without an estimate.
        remind = 0
        val off = MonitorTracker(remindBeforeS = { remind })
        off.next(job(elapsed = 0), true)
        assertFalse(off.next(job(elapsed = 3100), true).timeLeft)
        remind = 600
        val noEstimate = MonitorTracker(remindBeforeS = { remind })
        noEstimate.next(job(elapsed = 0, total = 0), true)
        assertFalse(noEstimate.next(job(elapsed = 3100, total = 0), true).timeLeft)
    }

    @Test
    fun onlyTheKindsOfNotificationSwitchedOnFire() {
        var on = Notice.defaults
        val t = MonitorTracker({ on }, { 600 })
        t.next(job(layer = 1, elapsed = 0), true)
        // Off by default: the first-layer check and the time-left alert.
        assertEquals(MonitorStep(), t.next(job(layer = FIRST_LAYER_CHECK, elapsed = 3000), true).copy(repost = false))
        on = Notice.defaults - Notice.PAUSED
        assertNull(t.next(job(PrintState.PAUSED), true).alert)
        on = Notice.defaults - Notice.COMPLETED
        // No alert, but the "Print again" shortcut still learns the job finished.
        assertEquals(MonitorStep(stop = true, finished = true), t.next(job(PrintState.COMPLETE), true).copy(repost = false))
        on = Notice.defaults
        assertEquals(R.string.alert_stopped, MonitorTracker({ on }).run { next(job(), true); next(job(PrintState.STOPPED), true).alert })
        assertEquals(setOf(Notice.TIME_LEFT, Notice.FIRST_LAYER), Notice.entries.toSet() - Notice.defaults)
    }

    @Test
    fun eachAlertBelongsToOneKindOfNotification() {
        assertEquals(Notice.COMPLETED, noticeFor(R.string.alert_finished))
        assertEquals(Notice.PAUSED, noticeFor(R.string.alert_paused))
        assertEquals(Notice.STOPPED, noticeFor(R.string.alert_stopped))
        assertEquals(Notice.ERROR, noticeFor(R.string.alert_error))
        assertEquals(Notice.ERROR, noticeFor(R.string.alert_unload_error))
        // Every kind has its own channel, and errors keep the old shared one.
        assertEquals(Notice.entries.size, Notice.entries.map { it.channel }.toSet().size)
        assertEquals("alerts", Notice.ERROR.channel)
        assertEquals("progress", Notice.PROGRESS.channel)
    }

    @Test
    fun firstLayerCheckFiresOnceAtLayerThreeWhilePrinting() {
        val t = MonitorTracker()
        assertFalse(t.next(job(PrintState.PREHEATING, layer = 0), true).firstLayer)
        assertFalse(t.next(job(layer = FIRST_LAYER_CHECK - 1), true).firstLayer)
        assertFalse(t.next(job(PrintState.PAUSED, layer = FIRST_LAYER_CHECK), true).firstLayer)
        assertTrue(t.next(job(layer = FIRST_LAYER_CHECK), true).firstLayer)
        assertFalse(t.next(job(layer = FIRST_LAYER_CHECK + 1), true).firstLayer)
        // Started mid-print: that layer is long gone.
        assertFalse(MonitorTracker().next(job(layer = 87), true).firstLayer)
    }

    @Test
    fun theLightAndTheThumbnailAreVisibleChanges() {
        val t = MonitorTracker()
        t.next(st(PrintState.PRINTING), true)
        assertEquals(MonitorStep(repost = true), t.next(st(PrintState.PRINTING), true, thumbnail = true))
        val lightOn = parseStatus(JSONObject().put("LightStatus", JSONObject().put("SecondLight", 1))
            .put("PrintInfo", JSONObject().put("Status", PrintState.PRINTING).put("Progress", 10).put("CurrentLayer", 5).put("Filename", "a.gcode")))
        assertEquals(MonitorStep(repost = true), t.next(lightOn, true, thumbnail = true))
    }

    @Test
    fun aLateFirstLayerPictureIsDroppedOnceTheJobEnded() {
        val then = st(PrintState.PRINTING)
        assertTrue(stillRunning(then, st(PrintState.PRINTING)))
        assertTrue(stillRunning(then, st(PrintState.PAUSED)))
        assertFalse(stillRunning(then, null))
        assertFalse(stillRunning(then, st(PrintState.ERROR)))
        assertFalse(stillRunning(then, st(PrintState.STOPPED)))
        val other = st(PrintState.PRINTING).let { it.copy(print = it.print.copy(taskId = "next")) }
        assertFalse(stillRunning(then, other))
    }

    @Test
    fun liveUpdateBarGoesPreheatThenLevelThenPrint() {
        fun at(status: Int, progress: Int = 0) = liveProgress(job(status).print.copy(progress = progress))
        assertEquals(0, at(PrintState.HOMING))
        assertEquals(0, at(PrintState.PREHEATING))
        // Preheating done is still the Heat step, as on the Status screen; levelling done is still Level.
        assertEquals(0, at(PrintState.PREHEATING_DONE))
        assertEquals(5, at(PrintState.AUTO_LEVELING))
        assertEquals(5, at(PrintState.RESONANCE_TESTING))
        assertEquals(5, at(PrintState.LEVELING_DONE))
        assertEquals(10, at(PrintState.PRINTING, 0))
        assertEquals(55, at(PrintState.PRINTING, 50))
        assertEquals(100, at(PrintState.PRINTING, 120))
    }

    @Test
    fun remembersTheJobFileAfterThePrinterClearsIt() {
        val t = MonitorTracker()
        t.next(st(PrintState.PRINTING), true)
        t.next(parseStatus(JSONObject().put("PrintInfo", JSONObject().put("Status", PrintState.COMPLETE).put("Filename", ""))), true)
        assertEquals("a.gcode", t.file)
        // A new job that hasn't named its file yet doesn't inherit the last one's name.
        t.next(parseStatus(JSONObject().put("PrintInfo", JSONObject().put("Status", PrintState.PRINTING).put("TaskId", "b"))), true)
        assertEquals("", t.file)
    }
}
