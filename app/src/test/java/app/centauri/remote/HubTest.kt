package app.centauri.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** What the app remembers between launches. Robolectric starts [CentauriApp], which runs [Hub.init]. */
@RunWith(RobolectricTestRunner::class)
class HubTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val saved get() = app.getSharedPreferences("printer", 0)

    @Before
    fun reset() = Hub.forget() // Hub is a process-wide object; tests share it

    @Test
    fun aPickedPrinterIsRestoredOnTheNextLaunch() {
        assertNull(Hub.client.value)
        val p = Printer("Shop", "192.168.1.20", "m1", "c1")
        Hub.pick(p)
        Hub.rememberName("Garage")
        Hub.init(app) // next launch
        assertEquals(p.copy(name = "Garage"), Hub.client.value!!.printer)

        Hub.forget()
        assertNull(Hub.client.value)
        Hub.init(app)
        assertNull(Hub.client.value)
    }

    @Test
    fun theDemoIsNeverSaved() {
        Hub.startDemo()
        assertTrue(Hub.client.value is DemoPrinter)
        Hub.rememberName("Not saved")
        assertFalse(saved.contains("ip"))
        assertFalse(saved.contains("name"))
        Hub.startDemo() // replacing the demo shuts the old one down
        assertTrue(Hub.client.value is DemoPrinter)
    }

    @Test
    fun labsStartOffPersistAndForgetRetiredOnes() {
        Lab.entries.forEach { Hub.setLab(it, false) }
        Hub.init(app)
        assertEquals(emptySet<Lab>(), Hub.labs.value)
        Hub.setLab(Lab.JOG, true)
        Hub.setLab(Lab.CANVAS_TRAY, true)
        Hub.setLab(Lab.CANVAS_TRAY, false)
        Hub.init(app)
        assertEquals(setOf(Lab.JOG), Hub.labs.value)
        // A lab that no longer exists in this version is dropped quietly.
        saved.edit().putStringSet("labs", setOf("JOG", "GONE")).commit()
        Hub.init(app)
        assertEquals(setOf(Lab.JOG), Hub.labs.value)
        Hub.setLab(Lab.JOG, false)
    }

    @Test
    fun presetsAndNotificationSettingPersist() {
        val a = Preset("PETG", 245, 75)
        Hub.savePreset(a)
        Hub.savePreset(Preset("petg", 250, 80)) // same name, any case: replaces
        Hub.savePreset(Preset("PLA", 210, 55))
        Hub.setNotifications(false)
        Hub.init(app)
        assertEquals(listOf("petg", "PLA"), Hub.presets.value.map { it.name })
        assertFalse(Hub.notifications.value)
        Hub.deletePreset(Hub.presets.value[0])
        Hub.init(app)
        assertEquals(listOf("PLA"), Hub.presets.value.map { it.name })
    }
}
