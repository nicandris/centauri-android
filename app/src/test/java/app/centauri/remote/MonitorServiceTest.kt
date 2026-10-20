package app.centauri.remote

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.robolectric.annotation.Config
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** The print monitor against the demo printer, with the main looper driven by hand. */
@RunWith(RobolectricTestRunner::class)
class MonitorServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Before
    fun reset() = Hub.forget()

    private fun startService() = Robolectric.buildService(PrintMonitorService::class.java).create().startCommand(0, 1)
        .also { looper.idle() }

    @Test
    fun mirrorsAPrintAndAlertsWhenItPausesAndStops() {
        Hub.startDemo()
        val demo = Hub.client.value!!
        val service = startService()
        val ongoing = nm.getNotification(1)
        assertTrue(ongoing.title(), ongoing.title().startsWith("benchy"))
        assertTrue(ongoing.text(), ongoing.text().startsWith("Finishes"))

        demo.pause()
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals("Print paused", nm.getNotification(2).title())
        assertEquals("Paused", nm.getNotification(1).text())

        demo.stopPrint()
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals("Print stopped", nm.getNotification(2).title())
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        service.destroy()
    }

    @Test
    fun givesUpOnAnUnreachablePrinter() {
        Hub.startDemo()
        val service = startService()
        Hub.client.value!!.shutdown() // looks like a lost connection
        looper.idle()
        assertEquals("Reconnecting to the printer…", nm.getNotification(1).text())
        looper.idleFor(Duration.ofMinutes(4))
        assertFalse(shadowOf(service.get()).isStoppedBySelf)
        looper.idleFor(Duration.ofMinutes(2))
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        // It was mid-print, so giving up is itself worth an alert.
        assertEquals("Lost contact with the printer", nm.getNotification(2).title())
        assertEquals("Demo printer stopped answering during benchy", nm.getNotification(2).text())
    }

    /** The demo printer, but the printer's history says why a job stopped. */
    private class ReasonLink(demo: DemoPrinter, val details: suspend (String) -> HistoryEntry?) : PrinterLink by demo {
        override suspend fun taskDetails(taskId: String) = details(taskId)
    }

    @Suppress("UNCHECKED_CAST")
    private fun use(link: PrinterLink) {
        val f = Hub::class.java.getDeclaredField("_client").apply { isAccessible = true }
        (f.get(Hub) as kotlinx.coroutines.flow.MutableStateFlow<PrinterLink?>).value = link
    }

    private fun entry(reason: Int) = HistoryEntry("t", "/local/x.gcode", "", 0, 0, false, 1, 2, errorReason = reason)

    @Test
    fun alertsSayWhyAPrintStoppedWhenThePrinterKnows() {
        val link = ReasonLink(DemoPrinter(Hub.scope)) { entry(3) }
        use(link)
        startService()
        link.pause()
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals("Print paused", nm.getNotification(2).title())
        assertEquals("Filament ran out · benchy", nm.getNotification(2).text())
    }

    @Test
    fun aFailedReasonLookupKeepsThePlainAlert() {
        val link = ReasonLink(DemoPrinter(Hub.scope)) { throw java.io.IOException("gone") }
        use(link)
        startService()
        link.pause()
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals("benchy", nm.getNotification(2).text())
    }

    @Test
    fun stoppingTheServiceCancelsAPendingReasonLookup() {
        var cancelled = false
        val link = ReasonLink(DemoPrinter(Hub.scope)) {
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
        }
        use(link)
        val service = startService()
        link.pause()
        looper.idleFor(Duration.ofSeconds(2))
        service.destroy()
        looper.idle()
        assertTrue(cancelled)
        assertEquals("benchy", nm.getNotification(2).text())
    }

    @Test
    fun stopsWhenThePrinterIsSwitchedAndOnlyWatchesOnce() {
        Hub.startDemo()
        val service = startService()
        service.startCommand(0, 2) // the UI asks again: same client, nothing restarts
        looper.idle()
        assertFalse(shadowOf(service.get()).isStoppedBySelf)
        Hub.startDemo()
        looper.idle()
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
    }

    @Test
    fun stopsAtOnceWithoutAPrinterOrWithNotificationsOff() {
        val none = startService()
        assertTrue(shadowOf(none.get()).isStoppedBySelf)
        assertEquals("Centauri Carbon", nm.getNotification(1).title())

        Hub.startDemo()
        Hub.setNotifications(false)
        val off = startService()
        assertTrue(shadowOf(off.get()).isStoppedBySelf)
        Hub.setNotifications(true)
    }

    @Test
    @Config(sdk = [33]) // before Android 14's foreground service types
    fun runsInTheForegroundOnOlderAndroid() {
        Hub.startDemo()
        val service = startService()
        assertNull(service.get().onBind(null))
        assertNotNull(shadowOf(service.get()).lastForegroundNotification)
    }

    @Test
    fun alertTextsAndStartStopHelpers() {
        val st = parseStatus(JSONObject("""{"PrintInfo":{"Status":9,"CurrentTicks":5400,"Filename":"ECC_0.4_benchy_PLA_0.2_1h.gcode"}}"""))
        assertEquals("benchy took 1h 30m", alert(app, st, R.string.alert_finished).text())
        assertEquals("benchy needs attention", alert(app, st, R.string.alert_error).text())
        assertEquals("benchy", alert(app, st, R.string.alert_stopped).text())

        PrintMonitorService.start(app)
        assertNotNull(shadowOf(app).nextStartedService)
        PrintMonitorService.stop(app)
        assertNotNull(shadowOf(app).nextStoppedService)
    }
}
