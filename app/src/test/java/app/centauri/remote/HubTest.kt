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
