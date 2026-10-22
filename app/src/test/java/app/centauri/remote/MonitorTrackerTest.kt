package app.centauri.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        assertEquals(MonitorStep(R.string.alert_finished, stop = true), t.next(st(PrintState.COMPLETE), true))
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
}
