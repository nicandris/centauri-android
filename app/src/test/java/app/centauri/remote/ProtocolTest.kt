package app.centauri.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class ProtocolTest {
    private fun v4(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun aRepeatedHistoryEntryIsListedOnce() {
        val data = JSONObject("""{"HistoryDetailList":[{"TaskId":"t1","BeginTime":5},{"TaskId":"t1","BeginTime":5},{"TaskId":"","BeginTime":0},{"TaskId":"","BeginTime":0}]}""")
        val keys = parseHistory(data).map { it.key }
        assertEquals(keys.distinct(), keys) // LazyColumn keys must be unique
        assertEquals(2, keys.size)
    }

    @Test
    fun directedBroadcastCoversCommonPrefixes() {
        assertEquals("192.168.178.255", directedBroadcast(v4("192.168.178.64"), 24))
        assertEquals("10.0.255.255", directedBroadcast(v4("10.0.3.7"), 16))
        assertEquals("172.16.0.15", directedBroadcast(v4("172.16.0.9"), 28))
        assertEquals("192.168.1.5", directedBroadcast(v4("192.168.1.5"), 32))
        assertEquals("255.255.255.255", directedBroadcast(v4("192.168.1.5"), 0))
    }

    @Test
    fun discoveryUsesTheSenderAddressNotTheReportedOne() {
        val reply = """{"Id":"abc","Data":{"Name":"Centauri Carbon","MainboardIP":"not an ip","MainboardID":"m1"}}"""
        assertEquals(Printer("Centauri Carbon", "192.168.178.64", "m1", "abc"), parseDiscovery(reply, v4("192.168.178.64")))
    }

    @Test
    fun discoveryIgnoresJunkAndRepliesWithoutAnId() {
        assertNull(parseDiscovery("hello", v4("192.168.1.2")))
        assertNull(parseDiscovery("""{"Data":{"Name":"x"}}""", v4("192.168.1.2")))
        assertNull(parseDiscovery("""{"Data":{"MainboardID":"m"}}""", InetAddress.getByName("::1")))
    }

    @Test
    fun fileListKeepsFilesOnlyNewestFirst() {
        val data = JSONObject(
            """{"Ack":0,"FileList":[
              {"name":"/local/old.gcode","type":1,"CreateTime":100,"FileSize":5,"TotalLayers":10},
              {"name":"/local/sub","type":0,"CreateTime":300},
              {"name":"/local/new.gcode","type":1,"CreateTime":200,"FileSize":7,"TotalLayers":20}]}""",
        )
        assertEquals(listOf("/local/new.gcode", "/local/old.gcode"), parseFiles(data).map { it.path })
        assertEquals(emptyList<PrinterFile>(), parseFiles(JSONObject("{}")))
    }

    @Test
    fun historyParsesOutcomeAndKeysStayUniqueWithoutTaskIds() {
        val data = JSONObject(
            """{"HistoryDetailList":[
              {"TaskName":"/local/a.gcode","BeginTime":10,"EndTime":20,"TaskStatus":1,"AlreadyPrintLayer":5,
               "SliceInformation":{"total_layer_numbers":5}},
              {"TaskName":"/local/b.gcode","BeginTime":30,"EndTime":35,"TaskStatus":0,"AlreadyPrintLayer":2,
               "SliceInformation":{"total_layer_numbers":9}}]}""",
        )
        val h = parseHistory(data)
        assertEquals(listOf("/local/b.gcode", "/local/a.gcode"), h.map { it.path })
        assertEquals(listOf(false, true), h.map { it.completed })
        assertEquals(2 to 9, h[0].layers to h[0].totalLayers)
        assertNotEquals(h[0].key, h[1].key)
    }

    @Test
    fun managementAcksNeedNoPerItemErrors() {
        assertEquals(true, ackOk(JSONObject("""{"Ack":0}""")))
        assertEquals(true, ackOk(JSONObject("""{"Ack":0,"ErrData":[]}""")))
        assertEquals(false, ackOk(JSONObject("""{"Ack":0,"ErrData":["a.gcode"]}""")))
        assertEquals(false, ackOk(JSONObject("""{"Ack":1}""")))
        assertEquals(false, ackOk(JSONObject("{}")))
    }

    @Test
    fun canvasTraysSkipDisconnectedUnitsAndBlankPlaceholders() {
        val reply = """{"Ack":0,"active_canvas_id":0,"active_tray_id":2,"auto_refill":false,"canvas_list":[
            {"canvas_id":0,"connected":1,"tray_list":[
              {"tray_id":0,"brand":"ELEGOO","filament_type":"PLA","filament_name":"PLA+","filament_color":"#FF8800","status":1},
              {"tray_id":1,"brand":"— — —","filament_type":"?","filament_name":"—","filament_color":"","status":0},
              {"tray_id":2,"brand":"","filament_type":"PETG","filament_name":"","filament_color":"00ff00","status":1}]},
            {"canvas_id":1,"connected":0,"tray_list":[{"tray_id":0,"status":1}]}]}"""
        val c = parseCanvas(JSONObject(reply))
        assertEquals(listOf("A1", "A2", "A3"), c.trays.map { it.label })
        assertEquals(Tray(0, 0, true, "PLA", "PLA+", "ELEGOO", 0xFF8800), c.trays[0])
        assertEquals(Tray(0, 1, false, "", "", "", null), c.trays[1])
        assertEquals(0x00FF00, c.trays[2].color)
        assertEquals(listOf(false, false, true), c.trays.map(c::isActive))
        assertEquals("B4", Tray(1, 3, true, "", "", "", null).label)

        // The CC2 shape wraps the same fields; an empty reply has nothing in use.
        val wrapped = parseCanvas(JSONObject("""{"canvas_info":$reply}"""))
        assertEquals(c, wrapped)
        assertEquals(Canvas(emptyList(), -1, -1), parseCanvas(JSONObject("{}")))
    }

    @Test
    fun aTrayIsTheSameSlotWhateverItsFilament() {
        val a1 = Tray(0, 0, true, "PLA", "PLA+", "ELEGOO", 0x111111)
        assertEquals(true, a1.sameSlot(a1.copy(name = "PLA+ Basic", color = 0x222222)))
        assertEquals(false, a1.sameSlot(a1.copy(trayId = 1)))
        assertEquals(false, a1.sameSlot(a1.copy(canvasId = 1)))
        assertEquals(false, a1.sameSlot(null))
    }

    @Test
    fun jogDistancesReadAsPeopleWriteThem() {
        assertEquals("1", formatMm(1.0, java.util.Locale.US))
        assertEquals("10", formatMm(10.0, java.util.Locale.US))
        assertEquals("0.1", formatMm(0.1, java.util.Locale.US))
        assertEquals("0,1", formatMm(0.1, java.util.Locale.forLanguageTag("nl")))
        assertEquals("-0,1", formatMm(-0.1, java.util.Locale.forLanguageTag("nl")))
        assertEquals(formatMm(10.0, java.util.Locale.getDefault()), formatMm(10.0)) // follows the phone
    }

    @Test
    fun slotMapFeedsToolZeroFromTheChosenTray() {
        assertEquals("[]", slotMap(null).toString())
        assertEquals("""[{"t":0,"canvas_id":1,"tray_id":3}]""", slotMap(Tray(1, 3, true, "", "", "", null)).toString())
    }

    @Test
    fun alertsOnlyForMeaningfulTransitions() {
        assertEquals(R.string.alert_finished, alertFor(PrintState.PRINTING, PrintState.COMPLETE))
        assertEquals(R.string.alert_paused, alertFor(PrintState.PAUSING, PrintState.PAUSED))
        assertEquals(R.string.alert_stopped, alertFor(PrintState.STOPPING, PrintState.STOPPED))
        assertEquals(R.string.alert_error, alertFor(PrintState.PRINTING, PrintState.ERROR))
        assertNull(alertFor(PrintState.COMPLETE, PrintState.COMPLETE))
        assertNull(alertFor(PrintState.IDLE, PrintState.PAUSED)) // e.g. app opened on an already-paused print
        assertNull(alertFor(PrintState.PAUSED, PrintState.PRINTING))
    }
}
