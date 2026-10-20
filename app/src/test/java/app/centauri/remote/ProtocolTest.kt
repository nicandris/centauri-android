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
        assertEquals(listOf("b.gcode", "a.gcode"), h.map { it.name })
        assertEquals(listOf(false, true), h.map { it.completed })
        assertEquals(2 to 9, h[0].layers to h[0].totalLayers)
        assertNotEquals(h[0].key, h[1].key)
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
