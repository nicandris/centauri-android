package app.centauri.remote

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/** The print monitor against the demo printer, with the main looper driven by hand. */
@RunWith(RobolectricTestRunner::class)
class MonitorServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun Notification.buttons() = actions.orEmpty().map { it.title.toString() }
    private fun Notification.button(title: String) = actions.single { it.title == title }

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
        assertEquals(listOf("Pause", "Light off", "Stop"), ongoing.buttons())
        // Stop needs the phone unlocked, and only opens the app's confirmation; a tap opens the Status tab.
        assertTrue(ongoing.button("Stop").isAuthenticationRequired)
        assertEquals(Destination.STOP, destinationOf(shadowOf(ongoing.button("Stop").actionIntent).savedIntent))
        assertTrue(shadowOf(ongoing.button("Stop").actionIntent).isActivityIntent)
        assertFalse(ongoing.button("Pause").isAuthenticationRequired)
        assertEquals(Destination.STATUS, destinationOf(shadowOf(ongoing.contentIntent).savedIntent))
        // Android 16+: a Live Update with the time left as its chip.
        assertTrue(ongoing.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals(Notification.ProgressStyle::class.java.name, ongoing.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("1h 1m", ongoing.shortCriticalText)

        demo.pause()
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals("Print paused", nm.getNotification(2).title())
        assertEquals("Paused", nm.getNotification(1).text())
        assertEquals(listOf("Resume", "Light off", "Stop"), nm.getNotification(1).buttons())
        assertNull(nm.getNotification(1).shortCriticalText)

        demo.stopPrint()
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals("Print stopped", nm.getNotification(2).title())
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        service.destroy()
    }

    @Test
    fun theLastFinishFrameGoesOnceTheNextJobRuns() {
        Hub.finishFrame.value = "old" to Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565)
        Hub.startDemo()
        val demo = Hub.client.value as DemoPrinter
        demo.play(DemoScenario.IDLE)
        val service = startService()
        assertNotNull(Hub.finishFrame.value) // idle: the Complete card still shows it
        demo.play(DemoScenario.PRINTING)
        looper.idleFor(Duration.ofSeconds(2))
        assertNull(Hub.finishFrame.value)
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
        assertEquals(
            "Demo printer stopped answering during benchy. Monitoring paused, tap to reconnect.", nm.getNotification(2).text(),
        )
        assertEquals(Destination.STATUS, destinationOf(shadowOf(nm.getNotification(2).contentIntent).savedIntent))
    }

    /** A job picture that fails when collected, like any unexpected throw inside the monitor. */
    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    private object Broken : StateFlow<Bitmap?> {
        override val value: Bitmap? = null
        override val replayCache = listOf<Bitmap?>(null)
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Bitmap?>): Nothing = error("boom")
    }

    @Test
    fun aFailingMonitorStopsInsteadOfFreezing() {
        use(FakeLink(DemoPrinter(Hub.scope), thumbnail = Broken))
        val service = startService()
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
    }

    /** The demo printer with a status and job picture the test sets. */
    private class FakeLink(
        demo: DemoPrinter, override val status: StateFlow<Status?> = demo.status,
        override val thumbnail: StateFlow<Bitmap?> = demo.thumbnail,
    ) : PrinterLink by demo

    private fun job(layer: Int, elapsed: Int) = parseStatus(JSONObject().put("PrintInfo", JSONObject()
        .put("Status", PrintState.PRINTING).put("CurrentLayer", layer).put("TotalLayer", 240)
        .put("CurrentTicks", elapsed).put("TotalTicks", 3600).put("Filename", "benchy.gcode")))

    @Test
    fun firstLayerCheckAndTimeLeftAlerts() {
        Hub.setNotice(Notice.FIRST_LAYER, true)
        Hub.setNotice(Notice.TIME_LEFT, true)
        val status = MutableStateFlow<Status?>(job(layer = 1, elapsed = 0))
        use(FakeLink(DemoPrinter(Hub.scope), status))
        startService()
        status.value = job(layer = FIRST_LAYER_CHECK, elapsed = 300)
        looper.idle()
        val layer = nm.getNotification(4)
        assertEquals("First layer done", layer.title())
        assertEquals("Check that benchy is sticking to the plate", layer.text())
        assertNotNull(layer.extras.get(Notification.EXTRA_PICTURE)) // the demo's camera frame
        assertEquals(listOf("Looks good", "Stop"), layer.buttons())
        assertTrue(layer.button("Stop").isAuthenticationRequired)
        assertEquals(Destination.STOP, destinationOf(shadowOf(layer.button("Stop").actionIntent).savedIntent))
        assertTrue(shadowOf(layer.button("Stop").actionIntent).isActivityIntent)

        status.value = job(layer = 200, elapsed = 3000)
        looper.idle()
        assertEquals("10m left", nm.getNotification(3).title())
        assertTrue(nm.getNotification(3).text().startsWith("benchy finishes at"))
        // A later alert (paused here) clears the first-layer one.
        status.value = parseStatus(JSONObject().put("PrintInfo", JSONObject().put("Status", PrintState.PAUSED).put("Filename", "benchy.gcode")))
        looper.idle()
        assertNull(nm.getNotification(4))
        Hub.setNotice(Notice.FIRST_LAYER, false)
        Hub.setNotice(Notice.TIME_LEFT, false)
    }

    @Test
    fun withProgressAndLostContactOffTheServiceIsQuiet() {
        Hub.setNotice(Notice.PROGRESS, false)
        Hub.setNotice(Notice.LOST, false)
        Hub.setNotice(Notice.PAUSED, false)
        Hub.startDemo()
        val service = startService()
        Hub.client.value!!.pause()
        looper.idleFor(Duration.ofSeconds(2))
        assertNull(nm.getNotification(2))
        // A foreground service needs a notification: a plain one-liner, no bar or buttons.
        val ongoing = nm.getNotification(1)
        assertEquals("Watching the print for alerts", ongoing.text())
        assertTrue(ongoing.buttons().isEmpty())
        assertFalse(ongoing.extras.getBoolean("android.requestPromotedOngoing"))
        Hub.client.value!!.shutdown()
        looper.idleFor(Duration.ofMinutes(6))
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        assertNull(nm.getNotification(2))
        Hub.setNotice(Notice.PROGRESS, true)
        Hub.setNotice(Notice.LOST, true)
        Hub.setNotice(Notice.PAUSED, true)
    }

    @Test
    fun theOngoingTitleKeepsTheJobAfterAnErrorBlanksTheFile() {
        val status = MutableStateFlow<Status?>(job(layer = 5, elapsed = 300))
        use(FakeLink(DemoPrinter(Hub.scope), status))
        startService()
        assertTrue(nm.getNotification(1).title().startsWith("benchy"))
        status.value = parseStatus(JSONObject().put("PrintInfo", JSONObject().put("Status", PrintState.ERROR).put("Progress", 42)))
        looper.idle()
        assertEquals("benchy · 42%", nm.getNotification(1).title())
        // Never known: the state names it.
        assertEquals("Error · 42%", ongoing(app, null, status.value, true, file = "").title())
    }

    @Test
    fun anAlertSnapshotTakesTheAppsCameraFrameInsteadOfASecondStream() {
        val toggled = mutableListOf<Boolean>()
        val link = object : PrinterLink by DemoPrinter(Hub.scope) {
            override val stillFrame: Int? = null
            override val videoUrl = MutableStateFlow<String?>("http://127.0.0.1:1/") // refused at once
            override fun video(enable: Boolean) { toggled += enable }
        }
        val live = Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565)
        Hub.liveFrame = live
        assertTrue(live === kotlinx.coroutines.runBlocking { snapshot(app, link, Hub.http) })
        assertTrue(toggled.isEmpty())
        // No view streaming: it opens the camera itself, and lets go of it.
        Hub.liveFrame = null
        assertNull(kotlinx.coroutines.runBlocking { snapshot(app, link, Hub.http) })
        assertEquals(listOf(true, false), toggled)
    }

    @Test
    fun theJobPictureIsTheLargeIcon() {
        val thumb = MutableStateFlow<Bitmap?>(null)
        use(FakeLink(DemoPrinter(Hub.scope), thumbnail = thumb))
        startService()
        assertNull(nm.getNotification(1).getLargeIcon())
        thumb.value = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        looper.idle()
        assertNotNull(nm.getNotification(1).getLargeIcon())
    }

    @Test
    fun notificationButtonsCommandThePrinter() {
        Hub.startDemo()
        val demo = Hub.client.value!!
        demo.start()
        val buttons = PrintActionReceiver()
        buttons.onReceive(app, Intent(ACTION_LIGHT))
        assertFalse(demo.status.value!!.light)
        buttons.onReceive(app, Intent(ACTION_PAUSE))
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals(PrintState.PAUSED, demo.status.value!!.print.status)
        buttons.onReceive(app, Intent(ACTION_RESUME))
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals(PrintState.PRINTING, demo.status.value!!.print.status)

        val manager = app.getSystemService(NotificationManager::class.java)
        manager.notify(4, firstLayerAlert(app, "a.gcode", null))
        buttons.onReceive(app, Intent(ACTION_LOOKS_GOOD))
        assertNull(nm.getNotification(4))
        // Stop isn't the receiver's: the app's confirmation sends it, and that clears the first-layer alert.
        buttons.onReceive(app, Intent("app.centauri.remote.STOP"))
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals(PrintState.PRINTING, demo.status.value!!.print.status)
        manager.notify(4, firstLayerAlert(app, "a.gcode", null))
        assertTrue(confirmedStop(app, demo))
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals(PrintState.STOPPED, demo.status.value!!.print.status)
        assertNull(nm.getNotification(4))
        // Not sent: the alert stays.
        manager.notify(4, firstLayerAlert(app, "a.gcode", null))
        assertFalse(confirmedStop(app, object : PrinterLink by demo { override fun stopPrint() = false }))
        assertNotNull(nm.getNotification(4))
        buttons.onReceive(app, Intent("something else"))
        assertEquals(0, ShadowToast.shownToastCount())

        // Nothing to send it to: say so rather than fail silently.
        Hub.forget()
        buttons.onReceive(app, Intent(ACTION_PAUSE))
        assertEquals("Not connected to the printer. Nothing was sent.", ShadowToast.getTextOfLatestToast())
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

    private fun entry(reason: Int) = HistoryEntry("t", "/local/x.gcode", "", 0, 0, Outcome.FAILED, 1, 2, errorReason = reason)

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
    fun aStoppedPrintStillGetsItsReasonBeforeTheServiceStops() {
        val answer = kotlinx.coroutines.CompletableDeferred<HistoryEntry?>()
        val link = ReasonLink(DemoPrinter(Hub.scope)) { answer.await() }
        use(link)
        val service = startService()
        link.stopPrint()
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals("Print stopped", nm.getNotification(2).title())
        assertFalse(shadowOf(service.get()).isStoppedBySelf) // still waiting for the reason
        answer.complete(entry(6))
        looper.idle()
        assertEquals("Filament jam or clog · benchy", nm.getNotification(2).text())
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
    }

    @Test
    fun aPrintStartedWhileWaitingForTheReasonKeepsTheServiceRunning() {
        val answer = kotlinx.coroutines.CompletableDeferred<HistoryEntry?>()
        val link = ReasonLink(DemoPrinter(Hub.scope)) { answer.await() }
        use(link)
        val service = startService()
        link.stopPrint()
        looper.idleFor(Duration.ofSeconds(2))
        val file = kotlinx.coroutines.runBlocking { link.files() }.first().path
        assertEquals(0, kotlinx.coroutines.runBlocking { link.startPrint(file, false, false, 0) })
        looper.idle()
        answer.complete(entry(6))
        looper.idleFor(Duration.ofSeconds(2))
        assertFalse(shadowOf(service.get()).isStoppedBySelf)
        service.destroy()
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
        // The plain notification, still with its buttons.
        val n = nm.getNotification(1)
        assertFalse(n.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals(listOf("Pause", "Light off", "Stop"), n.buttons())
    }

    @Test
    fun alertTextsAndStartStopHelpers() {
        val st = parseStatus(JSONObject("""{"PrintInfo":{"Status":9,"CurrentTicks":5400,"Filename":"ECC_0.4_benchy_PLA_0.2_1h.gcode"}}"""))
        assertEquals("benchy took 1h 30m", alert(app, st, R.string.alert_finished).text())
        assertEquals("benchy needs attention", alert(app, st, R.string.alert_error).text())
        assertEquals("benchy", alert(app, st, R.string.alert_stopped).text())
        assertEquals("finished", alert(app, st, R.string.alert_finished).channelId)
        assertEquals("stopped", alert(app, st, R.string.alert_stopped).channelId)
        assertEquals("alerts", alert(app, st, R.string.alert_error).channelId)
        assertEquals("10m left", timeLeftAlert(app, parseStatus(JSONObject("{\"PrintInfo\":{\"TotalTicks\":600}}")), "a.gcode").title())

        PrintMonitorService.start(app)
        assertNotNull(shadowOf(app).nextStartedService)
        PrintMonitorService.stop(app)
        assertNotNull(shadowOf(app).nextStoppedService)
    }

    @Test
    fun aFinishedPrintLeavesItsLastFrameForTheStatusScreen() {
        Hub.finishFrame.value = null
        val status = MutableStateFlow<Status?>(job(layer = 1, elapsed = 0))
        use(FakeLink(DemoPrinter(Hub.scope), status))
        startService()
        fun ended(code: Int) = parseStatus(JSONObject().put("PrintInfo", JSONObject()
            .put("Status", code).put("TaskId", "t9").put("Filename", "benchy.gcode")))
        status.value = ended(PrintState.PAUSED)
        looper.idle()
        assertEquals("Print paused", nm.getNotification(2).title())
        assertNull(Hub.finishFrame.value) // only a finished print's
        status.value = ended(PrintState.COMPLETE)
        looper.idleFor(Duration.ofSeconds(2)) // the light settles first
        assertEquals("Print finished", nm.getNotification(2).title())
        assertEquals("t9", Hub.finishFrame.value?.first)
        Hub.finishFrame.value = null
    }

    @Test
    fun aFinishOrErrorPhotoLightsTheChamberForThreeMinutes() {
        val demo = DemoPrinter(Hub.scope)
        demo.setLight(false)
        assertTrue(lightForSnapshot(demo, demo.status.value!!))
        assertTrue(demo.status.value!!.light)
        assertTrue(demo.connected.value) // held after the service stops
        looper.idleFor(Duration.ofMinutes(3))
        assertFalse(demo.status.value!!.light)
        assertFalse(demo.connected.value)

        // Already lit: left alone, and nothing is held.
        demo.setLight(true)
        assertFalse(lightForSnapshot(demo, demo.status.value!!))
        looper.idleFor(Duration.ofMinutes(3))
        assertTrue(demo.status.value!!.light)
        assertFalse(demo.connected.value)

        // Switched off by hand meanwhile: not switched again.
        demo.setLight(false)
        lightForSnapshot(demo, demo.status.value!!)
        demo.setLight(false)
        looper.idleFor(Duration.ofMinutes(3))
        assertFalse(demo.status.value!!.light)
    }
}
