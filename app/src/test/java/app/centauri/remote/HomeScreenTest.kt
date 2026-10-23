package app.centauri.remote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration

/** The widgets, the Quick Settings tile and the launcher shortcuts. */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val res get() = app.resources
    private val looper get() = shadowOf(Looper.getMainLooper())
    private val widgets get() = shadowOf(AppWidgetManager.getInstance(app))

    @Before
    fun reset() {
        Hub.forget()
        widgetHolders = 0 // other tests' monitors may still hold the widgets
    }

    private fun st(status: Int, file: String = "ECC_0.4_benchy_Elegoo PLA+ _0.2_1h35m.gcode", light: Boolean = false) =
        parseStatus(JSONObject().put("LightStatus", JSONObject().put("SecondLight", if (light) 1 else 0)).put("PrintInfo", JSONObject()
            .put("Status", status).put("Progress", 42).put("CurrentTicks", 600).put("TotalTicks", 3600).put("Filename", file)))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test
    fun widgetContentFollowsTheJob() {
        val finish = { "14:32" }
        assertNull(widgetContent(res, "Shop", null, true, finish))
        assertNull(widgetContent(res, "Shop", st(PrintState.PRINTING), false, finish))
        assertEquals(
            WidgetContent("benchy", "Printing · 42% · Finishes 14:32", 42, ACTION_PAUSE to R.string.pause, R.string.action_light_on),
            widgetContent(res, "Shop", st(PrintState.PRINTING), true, finish),
        )
        assertEquals(
            WidgetContent("benchy", "Paused · 42%", 42, ACTION_RESUME to R.string.resume, R.string.action_light_off),
            widgetContent(res, "Shop", st(PrintState.PAUSED, light = true), true, finish),
        )
        // No time while it isn't printing; an error that blanked the file is titled by the state.
        assertEquals("Complete · 42%", widgetContent(res, "Shop", st(PrintState.COMPLETE), true, finish)!!.line)
        assertNull(widgetContent(res, "Shop", st(PrintState.COMPLETE), true, finish)!!.pause)
        assertEquals("Error", widgetContent(res, "Shop", st(PrintState.ERROR, file = ""), true, finish)!!.title)
        assertEquals(
            WidgetContent("Shop", "Ready to print", null, null, R.string.action_light_on),
            widgetContent(res, "Shop", st(PrintState.IDLE, file = ""), true, finish),
        )
        // Homing or levelling before the job is named: as the top bar says it.
        assertEquals("Auto leveling", widgetContent(res, "Shop", st(PrintState.IDLE, file = "").copy(machine = 5), true, finish)!!.line)
        // Nothing holding the connection: no buttons, since a tap would have nothing to send them over.
        val ended = widgetContent(res, "Shop", st(PrintState.PAUSED), true, finish, controls = false)!!
        assertNull(ended.light)
        assertNull(ended.pause)
        // A job without a total time has no finish to show.
        val noTotal = st(PrintState.PRINTING).let { it.copy(print = it.print.copy(totalS = 0)) }
        assertEquals("Printing · 42%", widgetContent(res, "Shop", noTotal, true, finish)!!.line)
    }

    @Test
    fun aNewWidgetSaysToOpenTheAppUntilThereIsAConnection() {
        val small = widgets.createWidget(PrintWidget::class.java, R.layout.widget_small)
        val large = widgets.createWidget(PrintWidgetLarge::class.java, R.layout.widget_large)
        for (id in listOf(small, large)) {
            val v = widgets.getViewFor(id)
            assertEquals("Open Centauri to connect", v.text(R.id.widget_title))
            assertFalse(v.shown(R.id.widget_progress))
            assertFalse(v.shown(R.id.widget_line))
        }
        assertFalse(widgets.getViewFor(large).shown(R.id.widget_light))
        assertFalse(widgets.getViewFor(large).shown(R.id.widget_pause))
        // Added while the app is connected: it shows the print straight away, without a request of its own.
        Hub.startDemo()
        Hub.client.value!!.start()
        widgets.createWidget(PrintWidget::class.java, R.layout.widget_small).let {
            assertEquals("benchy", widgets.getViewFor(it).text(R.id.widget_title))
        }
        Hub.client.value!!.stop()
    }

    @Test
    fun theLargeWidgetHasThePictureAndButtons() {
        val large = widgets.createWidget(PrintWidgetLarge::class.java, R.layout.widget_large)
        val small = widgets.createWidget(PrintWidget::class.java, R.layout.widget_small)
        Hub.startDemo()
        val demo = Hub.client.value!!
        demo.start()
        updateWidgets(app, demo, demo.status.value, true)
        val v = widgets.getViewFor(large)
        assertEquals("benchy", v.text(R.id.widget_title))
        assertTrue(v.text(R.id.widget_line), v.text(R.id.widget_line).startsWith("Printing · "))
        assertEquals(demo.status.value!!.print.progress, v.findViewById<ProgressBar>(R.id.widget_progress).progress)
        assertTrue(v.shown(R.id.widget_thumb))
        assertEquals("Pause", v.findViewById<Button>(R.id.widget_pause).text.toString())
        assertEquals("Light off", v.findViewById<Button>(R.id.widget_light).text.toString())
        // The buttons are the notification's: PrintActionReceiver runs them.
        v.findViewById<Button>(R.id.widget_light).performClick()
        looper.idle()
        assertFalse(demo.status.value!!.light)
        assertTrue(widgets.getViewFor(small).shown(R.id.widget_progress))
        assertEquals(null, widgets.getViewFor(small).findViewById<ImageView?>(R.id.widget_thumb))

        // A real printer's picture is a bitmap.
        val picture = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val withPicture = object : PrinterLink by demo {
            override val thumbnail = kotlinx.coroutines.flow.MutableStateFlow(picture)
            override val jobThumbnailRes: Int? = null
        }
        updateWidgets(app, withPicture, demo.status.value, true)
        assertTrue(widgets.getViewFor(large).shown(R.id.widget_thumb))
        val drawn = widgets.getViewFor(large).findViewById<ImageView>(R.id.widget_thumb).drawable
        assertEquals(4, (drawn as android.graphics.drawable.BitmapDrawable).bitmap.width)
        // Without any picture, or idle, there's nothing to show beside the text.
        val noPicture = object : PrinterLink by demo { override val jobThumbnailRes: Int? = null }
        updateWidgets(app, noPicture, demo.status.value, true)
        assertFalse(widgets.getViewFor(large).shown(R.id.widget_thumb))
        updateWidgets(app, demo, st(PrintState.IDLE, file = ""), true)
        assertFalse(widgets.getViewFor(large).shown(R.id.widget_thumb))
        assertEquals("Demo printer", widgets.getViewFor(large).text(R.id.widget_title))
        demo.stop()
    }

    @Test
    fun theTileShowsThePrinterAndTogglesTheLight() {
        val tile = Robolectric.buildService(LightTile::class.java).create().get()
        tile.onStartListening()
        looper.idle()
        assertEquals("Centauri", tile.qsTile.label.toString())
        assertEquals("Not connected", tile.qsTile.subtitle.toString())
        assertEquals(Tile.STATE_INACTIVE, tile.qsTile.state)

        Hub.startDemo()
        val demo = Hub.client.value!!
        demo.start()
        looper.idle()
        assertEquals("Demo printer", tile.qsTile.label.toString())
        assertEquals("Chamber light, Demo printer", tile.qsTile.contentDescription.toString())
        assertTrue(tile.qsTile.subtitle.toString(), tile.qsTile.subtitle.toString().startsWith("Printing · "))
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        tile.onClick()
        looper.idle()
        assertFalse(demo.status.value!!.light)
        assertEquals(Tile.STATE_INACTIVE, tile.qsTile.state)

        // Not listening: changes don't reach the tile.
        tile.onStopListening()
        demo.setLight(true)
        looper.idle()
        assertEquals(Tile.STATE_INACTIVE, tile.qsTile.state)
        // Light on but the connection gone: the tile can't vouch for it.
        tile.onStartListening()
        looper.idle()
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        demo.stop()
        looper.idle()
        assertEquals(Tile.STATE_INACTIVE, tile.qsTile.state)
    }

    @Test
    fun theTileSubtitleIsTheState() {
        assertEquals("Not connected", tileSubtitle(res, null, true))
        assertEquals("Not connected", tileSubtitle(res, st(PrintState.PRINTING), false))
        assertEquals("Paused · 42%", tileSubtitle(res, st(PrintState.PAUSED), true))
        assertEquals("Idle", tileSubtitle(res, st(PrintState.IDLE, file = ""), true))
    }

    @Test
    fun withoutAConnectionTheTileOpensTheApp() {
        val tile = Robolectric.buildService(LightTile::class.java).create().get()
        // Android 14+ collapses the panel into a PendingIntent, through the system's tile service. Robolectric 4.17's
        // ShadowTileService only shadows the Intent overload, so the service field is stubbed by reflection.
        var sent: PendingIntent? = null
        val qs = Class.forName("android.service.quicksettings.IQSService")
        val service = Proxy.newProxyInstance(qs.classLoader, arrayOf(qs)) { _, method, args ->
            if (method.name == "startActivity") sent = args!![1] as PendingIntent
            null
        }
        TileService::class.java.getDeclaredField("mService").apply { isAccessible = true }.set(tile, service)
        tile.onClick()
        assertEquals(Destination.STATUS, destinationOf(shadowOf(sent).savedIntent))
    }

    @Test
    @Config(sdk = [33])
    fun beforeAndroid14TheTileOpensTheAppWithAnIntent() {
        val tile = Robolectric.buildService(LightTile::class.java).create().get()
        tile.onClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Destination.STATUS, destinationOf(started))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun intentsSayWhereToGo() {
        assertEquals(Destination.CAMERA, destinationOf(openIntent(app, Destination.CAMERA)))
        // Two destinations' PendingIntents must not collapse into one (extras don't tell them apart).
        assertFalse(openIntent(app, Destination.CAMERA).filterEquals(openIntent(app, Destination.STATUS)))
        assertFalse(openIntent(app, Destination.STOP).filterEquals(openIntent(app, Destination.STATUS)))
        assertEquals(Destination.STOP, destinationOf(openIntent(app, Destination.STOP)))
        assertNull(destinationOf(Intent()))
        assertNull(destinationOf(Intent().putExtra(EXTRA_OPEN, "nowhere")))
    }

    @Test
    fun aFinishedPrintBecomesThePrintAgainShortcut() {
        setPrintAgainShortcut(app, "")
        setPrintAgainShortcut(app, "/local/.gcode") // no name to label it with
        val shortcuts = app.getSystemService(ShortcutManager::class.java)
        assertTrue(shortcuts.dynamicShortcuts.isEmpty())
        setPrintAgainShortcut(app, "ECC_0.4_benchy_Elegoo PLA+ _0.2_1h35m.gcode")
        val s = shortcuts.dynamicShortcuts.single()
        assertEquals("Print again: benchy", s.longLabel.toString())
        assertEquals("benchy", s.shortLabel.toString())
        assertEquals(Destination.REPRINT, destinationOf(s.intent!!))
        assertEquals("/local/ECC_0.4_benchy_Elegoo PLA+ _0.2_1h35m.gcode", s.intent!!.getStringExtra(EXTRA_FILE))
        // The next one replaces it.
        setPrintAgainShortcut(app, "/local/ECC_0.4_cable-clip_Elegoo PLA+ _0.28_18m.gcode")
        assertEquals("Print again: cable-clip", shortcuts.dynamicShortcuts.single().longLabel.toString())
    }

    @Test
    fun theMonitorUpdatesTheWidgetsAndLeavesTheEndingBehind() {
        val large = widgets.createWidget(PrintWidgetLarge::class.java, R.layout.widget_large)
        Hub.startDemo()
        val demo = Hub.client.value as DemoPrinter
        val service = Robolectric.buildService(PrintMonitorService::class.java).create().startCommand(0, 1)
        looper.idle()
        assertEquals("benchy", widgets.getViewFor(large).text(R.id.widget_title))
        demo.pause()
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals("Resume", widgets.getViewFor(large).findViewById<Button>(R.id.widget_pause).text.toString())
        // Finished: the shortcut, and the widget keeps how it ended once the monitor stops.
        demo.play(DemoScenario.ALMOST_DONE)
        looper.idleFor(Duration.ofSeconds(30))
        assertEquals("Print again: benchy", app.getSystemService(ShortcutManager::class.java).dynamicShortcuts.single().longLabel.toString())
        service.destroy()
        assertTrue(widgets.getViewFor(large).text(R.id.widget_line), widgets.getViewFor(large).text(R.id.widget_line).startsWith("Complete"))
        assertFalse(widgets.getViewFor(large).shown(R.id.widget_light))
    }

    @Test
    fun theWidgetsFollowTheAppWithNotificationsOff() {
        Hub.setNotifications(false)
        val large = widgets.createWidget(PrintWidgetLarge::class.java, R.layout.widget_large)
        Hub.startDemo()
        val demo = Hub.client.value as DemoPrinter
        demo.play(DemoScenario.IDLE)
        demo.start()
        val unfollow = followOnWidgets(app, demo)
        looper.idle()
        assertEquals("Ready to print", widgets.getViewFor(large).text(R.id.widget_line))
        assertTrue(widgets.getViewFor(large).shown(R.id.widget_light))
        demo.play(DemoScenario.PAUSED)
        looper.idle()
        assertEquals("Resume", widgets.getViewFor(large).findViewById<Button>(R.id.widget_pause).text.toString())
        // A monitor holding the connection too: the app leaving doesn't blank the widget.
        holdWidgets()
        unfollow()
        assertTrue(widgets.getViewFor(large).shown(R.id.widget_pause))
        // The last holder gone mid-job: "open the app", without buttons.
        releaseWidgets(app, demo)
        assertEquals("Open Centauri to connect", widgets.getViewFor(large).text(R.id.widget_title))
        assertFalse(widgets.getViewFor(large).shown(R.id.widget_light))
        assertFalse(widgets.getViewFor(large).shown(R.id.widget_pause))
        demo.stop()
        Hub.setNotifications(true)
    }

    @Test
    fun aReconnectBlipWhileHeldKeepsTheJobWithoutButtons() {
        val large = widgets.createWidget(PrintWidgetLarge::class.java, R.layout.widget_large)
        Hub.startDemo()
        val demo = Hub.client.value!!
        updateWidgets(app, demo, st(PrintState.PRINTING), false) // nothing holds it: nothing to vouch for
        assertEquals("Open Centauri to connect", widgets.getViewFor(large).text(R.id.widget_title))
        holdWidgets()
        updateWidgets(app, demo, st(PrintState.PRINTING), false)
        assertEquals("benchy", widgets.getViewFor(large).text(R.id.widget_title))
        assertFalse(widgets.getViewFor(large).shown(R.id.widget_pause))
        assertFalse(widgets.getViewFor(large).shown(R.id.widget_light))
    }

    @Test
    fun theMonitorLeavesTheWidgetsToTheAppWhileItFollows() {
        val small = widgets.createWidget(PrintWidget::class.java, R.layout.widget_small)
        Hub.startDemo()
        holdWidgets() // the app on screen, which paints them itself
        val service = Robolectric.buildService(PrintMonitorService::class.java).create().startCommand(0, 1)
        looper.idle()
        assertEquals("Open Centauri to connect", widgets.getViewFor(small).text(R.id.widget_title))
        service.destroy()
    }

    @Test
    fun aMonitorThatGivesUpMidPrintForgetsTheJobOnTheWidget() {
        val small = widgets.createWidget(PrintWidget::class.java, R.layout.widget_small)
        Hub.startDemo()
        val service = Robolectric.buildService(PrintMonitorService::class.java).create().startCommand(0, 1)
        looper.idle()
        assertEquals("benchy", widgets.getViewFor(small).text(R.id.widget_title))
        service.destroy()
        assertEquals("Open Centauri to connect", widgets.getViewFor(small).text(R.id.widget_title))
    }
}
