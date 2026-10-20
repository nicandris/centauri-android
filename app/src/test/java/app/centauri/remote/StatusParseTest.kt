package app.centauri.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusParseTest {
    // A real sdcp/status frame from a Centauri Carbon (firmware V1.4.49) mid-print.
    private val frame = """
        {"CurrentStatus":[1],"AmsConnectStatus":0,"PlatFormType":0,"TimeLapseStatus":0,
         "TempOfNozzle":250.01,"TempTargetNozzle":250,"TempOfHotbed":70.0,"TempTargetHotbed":70,
         "TempOfBox":31.6,"TempTargetBox":0,"CurrenCoord":"120.00,110.00,13.40","ZOffset":0.0,
         "CurrentFanSpeed":{"ModelFan":29,"AuxiliaryFan":0,"BoxFan":68},
         "LightStatus":{"SecondLight":0,"RgbLight":[0,0,0]},
         "PrintInfo":{"Status":13,"CurrentLayer":112,"TotalLayer":540,"CurrentTicks":6319.23886593,
           "TotalTicks":32267,"Filename":"ECC_0.4_canvas-riser-led-petg_Elegoo Rapid PETG _0.2_8h58m.gcode",
           "TaskId":"e6e946d6-b907-4952-9784-d1998b99a5d4","PrintSpeedPct":100,"Progress":17}}
    """.trimIndent()

    @Test
    fun parsesARunningPrint() {
        val st = parseStatus(JSONObject(frame))
        assertEquals(PrintState.PRINTING, st.print.status)
        assertEquals(112, st.print.layer)
        assertEquals(540, st.print.totalLayers)
        assertEquals(6319, st.print.elapsedS) // fractional seconds truncate
        assertEquals(32267, st.print.totalS)
        assertEquals(17, st.print.progress)
        assertEquals(250.01, st.nozzle, 0.001)
        assertEquals(29, st.modelFan)
        assertEquals(68, st.boxFan)
        assertFalse(st.light)
        assertFalse(st.canvas)
        assertFalse(st.idle)
    }

    @Test
    fun identicalFramesAreEqualSoStateFlowDedupes() {
        assertEquals(parseStatus(JSONObject(frame)), parseStatus(JSONObject(frame)))
    }

    @Test
    fun missingFieldsFallBackToIdleDefaults() {
        val st = parseStatus(JSONObject("{}"))
        assertTrue(st.idle)
        assertEquals(100, st.print.speedPct)
        assertEquals(listOf(0, 0, 0), st.rgb)
    }
}
