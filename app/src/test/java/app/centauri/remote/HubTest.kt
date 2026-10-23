package app.centauri.remote

import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

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
    fun aRenameOnThePrintersScreenIsSaved() {
        Hub.pick(Printer("Shop", "192.168.1.20", "m1", "c1"))
        @Suppress("UNCHECKED_CAST")
        val name = SdcpClient::class.java.getDeclaredField("_name").apply { isAccessible = true }
            .get(Hub.client.value) as MutableStateFlow<String>
        name.value = "Workshop" // what an attributes push from the printer does
        shadowOf(Looper.getMainLooper()).idle()
        Hub.init(app)
        assertEquals("Workshop", Hub.client.value!!.printer.name)
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
    fun sortOrderPersistsAndFallsBackToNewest() {
        Hub.init(app)
        Hub.setOrder(ListOrder(SortBy.NAME, unknownFirst = false))
        Hub.init(app)
        assertEquals(ListOrder(SortBy.NAME, unknownFirst = false), Hub.order.value)
        saved.edit().putString("sort", "GONE").commit()
        Hub.init(app)
        assertEquals(ListOrder(unknownFirst = false), Hub.order.value)
        Hub.setOrder(ListOrder())
    }

    @Test
    fun clockWarningIsOnUntilSwitchedOff() {
        saved.edit().remove("clockWarning").commit()
        Hub.init(app)
        assertEquals(true, Hub.clockWarning.value)
        Hub.setClockWarning(false)
        Hub.init(app)
        assertEquals(false, Hub.clockWarning.value)
        Hub.setClockWarning(true)
    }

    @Test
    fun notificationKindsAndTheReminderPersist() {
        saved.edit().remove("remindBefore").remove("notices").commit()
        Hub.init(app)
        assertEquals(10, Hub.remindBefore.value)
        assertEquals(Notice.defaults, Hub.notices.value)
        Hub.setRemindBefore(15)
        Hub.setNotice(Notice.TIME_LEFT, true)
        Hub.setNotice(Notice.PAUSED, false)
        saved.edit().putInt("remindBefore", 5).commit() // read back from storage, not memory
        Hub.init(app)
        assertEquals(5, Hub.remindBefore.value)
        assertEquals(Notice.defaults + Notice.TIME_LEFT - Notice.PAUSED, Hub.notices.value)
        // A kind that no longer exists in this version is dropped quietly.
        saved.edit().putStringSet("notices", setOf("COMPLETED", "GONE")).commit()
        Hub.init(app)
        assertEquals(setOf(Notice.COMPLETED), Hub.notices.value)
        saved.edit().remove("remindBefore").remove("notices").commit()
        Hub.init(app)
    }

    @Test
    fun dynamicColourAndLanguagePersist() {
        saved.edit().remove("dynamicColor").remove("language").commit()
        Hub.init(app)
        assertFalse(Hub.dynamicColor.value)
        assertEquals("", Hub.language.value)
        Hub.setDynamicColor(true)
        Hub.setLanguage("el")
        Hub.init(app)
        assertTrue(Hub.dynamicColor.value)
        assertEquals("el", Hub.language.value)
        saved.edit().putString("language", "xx").commit() // not one we ship: back to the phone's
        Hub.init(app)
        assertEquals("", Hub.language.value)
        Hub.setDynamicColor(false)
        Hub.setLanguage("")
    }

    @Test
    fun labsStartOffPersistAndForgetRetiredOnes() {
        Lab.entries.forEach { Hub.setLab(it, false) }
        Hub.init(app)
        assertEquals(emptySet<Lab>(), Hub.labs.value)
        Hub.setLab(Lab.PRINT_STEPS, true)
        Hub.setLab(Lab.CANVAS_TRAY, true)
        Hub.setLab(Lab.CANVAS_TRAY, false)
        Hub.init(app)
        assertEquals(setOf(Lab.PRINT_STEPS), Hub.labs.value)
        // A lab that no longer exists in this version is dropped quietly.
        saved.edit().putStringSet("labs", setOf("PRINT_STEPS", "JOG")).commit()
        Hub.init(app)
        assertEquals(setOf(Lab.PRINT_STEPS), Hub.labs.value)
        Hub.setLab(Lab.PRINT_STEPS, false)
    }

    @Test
    fun startChoicesAreRememberedPerPrinter() {
        assertNull(Hub.startChoices("never-started"))
        Hub.rememberStart("m1", StartChoices(1, autoLevel = true, timelapse = true))
        Hub.rememberStart("m2", StartChoices(0, autoLevel = false, timelapse = false))
        Hub.init(app) // next launch
        assertEquals(StartChoices(1, autoLevel = true, timelapse = true), Hub.startChoices("m1"))
        assertEquals(StartChoices(0, autoLevel = false, timelapse = false), Hub.startChoices("m2"))
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

    @Test
    fun aPrinterWithANewAddressIsFoundAgainAndSaved() = kotlinx.coroutines.runBlocking {
        val saved = Printer("Shop", "192.168.1.20", "m1", "c1")
        Hub.startDemo()
        assertNull(Hub.relocate { listOf(saved) }) // nothing to look for in the demo
        Hub.pick(saved)
        val first = Hub.client.value
        assertNull(Hub.relocate { emptyList() })
        assertTrue(first === Hub.client.value)
        // Still at its address: same client, it just reconnects.
        assertEquals(saved, Hub.relocate { listOf(saved) })
        assertTrue(first === Hub.client.value)
        // Asked again while looking: one search at a time.
        var inner: Printer? = saved
        Hub.relocate { inner = Hub.relocate { listOf(saved) }; emptyList() }
        assertNull(inner)
        assertFalse(Hub.searching.value)
        val moved = Hub.relocate { listOf(saved.copy(ip = "192.168.1.31", connId = "c2")) }
        assertEquals("192.168.1.31", moved!!.ip)
        Hub.init(app) // next launch
        assertEquals(saved.copy(ip = "192.168.1.31", connId = "c2"), Hub.client.value!!.printer)
        // Switched to another printer while the search ran: that choice wins.
        assertNull(Hub.relocate { Hub.startDemo(); listOf(saved) })
        assertTrue(Hub.client.value is DemoPrinter)
        // The real search, on the test's loopback-only network, finds nobody.
        Hub.pick(saved)
        assertNull(Hub.relocate())
    }
}
