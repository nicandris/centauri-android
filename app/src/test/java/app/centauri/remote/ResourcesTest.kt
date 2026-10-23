package app.centauri.remote

import android.content.res.Resources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The helpers that turn printer data into user-facing text. */
@RunWith(RobolectricTestRunner::class)
class ResourcesTest {
    private val res: Resources = RuntimeEnvironment.getApplication().resources

    @Test
    fun printerDetailsSkipMissingFields() {
        val d = org.json.JSONObject("""{"BrandName":"ELEGOO","MachineName":"Centauri Carbon",
            "FirmwareVersion":"V1.1.46","XYZsize":"256x256x256","NetworkStatus":"wlan","RemainingMemory":5300000000,
            "UsbDiskStatus":0,"NumberOfVideoStreamConnected":1,"MaximumVideoStreamAllowed":1}""")
        val details = printerDetails(RuntimeEnvironment.getApplication(), d)
        assertEquals(
            listOf(
                "Model" to "ELEGOO Centauri Carbon", "Firmware" to "V1.1.46", "Build volume" to "256 × 256 × 256 mm",
                "Network" to "Wi-Fi", "USB drive" to "Not connected", "Camera viewers" to "1 of 1",
            ),
            details.filter { it.first != "Free space" },
        )
        assertTrue(details.single { it.first == "Free space" }.second.contains("GB"))
        assertEquals("Not set (stuck at 1970)", printerClock(RuntimeEnvironment.getApplication(), 90_000))
        assertTrue(printerClock(RuntimeEnvironment.getApplication(), 1_792_000_000).contains("2026"))
    }

    @Test
    fun durationsAndStatusLabels() {
        assertEquals("8h 58m", formatDuration(res, 8 * 3600 + 58 * 60))
        assertEquals("42m", formatDuration(res, 42 * 60 + 59))
        assertEquals("0m", formatDuration(res, -5))
        assertEquals("Printing", printStatusLabel(res, PrintState.PRINTING))
        assertTrue(printStatusLabel(res, 99).contains("99"))
        assertEquals("Idle", machineStatusLabel(res, 0))
        assertTrue(machineStatusLabel(res, 99).contains("99"))
        assertEquals(4, SPEED_PRESETS.size)
        assertNull(errorReasonLabel(res, 0))
        assertEquals("The print came loose from the bed", errorReasonLabel(res, 18))
        assertEquals("Error code 99", errorReasonLabel(res, 99))
        assertTrue(printStatusIsError(PrintState.UNLOAD_ERROR) && !printStatusIsError(PrintState.PRINTING))
        assertTrue(printStatusIsTransition(PrintState.RESUMING) && !printStatusIsTransition(PrintState.PAUSED))
    }

    @Test
    fun jobDetailOnlyForSlicedNames() {
        assertEquals("PETG · 0.2 mm · 8h58m", jobName("ECC_0.4_riser_PETG _0.2_8h58m.gcode").detail(res))
        assertNull(jobName("/local/plain.gcode").detail(res))
        assertEquals("PETG · 0.2 mm", jobName("ECC_0.4_riser_PETG _0.2_8h58m.gcode").detail(res, withTime = false))
        assertNull(jobName("/local/plain.gcode").detail(res, withTime = false))
    }

    @Test
    fun presetSummaries() {
        assertEquals("Nozzle 220 °C · Bed 60 °C", builtInPresets(res)[0].summary(res))
        assertEquals("Nozzle off · Bed off", builtInPresets(res)[3].summary(res))
        assertTrue(Preset("x", speedPct = 130, fans = listOf(1, 2, 3)).summary(res).contains("130"))
    }

    @Test
    fun presetsReportWhetherEverySettingWentOut() {
        val p = Preset("all", 200, 60, 100, listOf(1, 2, 3))
        assertTrue(DemoPrinter(CoroutineScope(Dispatchers.Unconfined)).apply(p))
        // Never started, so there is no socket to send on.
        val offline = SdcpClient(OkHttpClient(), Printer("", "10.0.0.1", "m", ""), CoroutineScope(Dispatchers.Unconfined))
        assertFalse(offline.apply(p))
        assertTrue(offline.apply(Preset("nothing")))
    }

    @Test
    fun displayNamePrefersThePrintersOwn() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val real = SdcpClient(OkHttpClient(), Printer("", "10.0.0.1", "m", ""), scope)
        assertEquals("Centauri Carbon", real.displayName(res))
        assertEquals("Shop", real.displayName(res, "Shop"))
        assertEquals("Demo printer", DemoPrinter(scope).displayName(res))
        assertNull(real.stillFrame)
        assertNull(real.jobThumbnailRes)
    }
}
