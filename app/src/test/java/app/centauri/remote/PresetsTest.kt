package app.centauri.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetsTest {
    @Test
    fun savedPresetsSurviveARoundTrip() {
        val presets = listOf(
            Preset("PETG tall", nozzle = 245, bed = 75, speedPct = 130, fans = listOf(60, 0, 40)),
            Preset("Temps only", nozzle = 210, bed = 55),
        )
        assertEquals(presets, presetsFromJson(presetsToJson(presets)))
    }

    @Test
    fun malformedStorageLosesOnlyTheBadEntries() {
        val json = """[{"name":"ok","nozzle":200},{"nozzle":1},"junk",{"name":"fans","fans":[1,2]}]"""
        assertEquals(listOf(Preset("ok", nozzle = 200), Preset("fans")), presetsFromJson(json))
        assertEquals(emptyList<Preset>(), presetsFromJson("not json"))
        assertEquals(emptyList<Preset>(), presetsFromJson(null))
    }

    @Test
    fun currentSettingsCaptureTargetsSpeedAndFans() {
        val st = parseStatus(JSONObject(
            """{"TempTargetNozzle":250,"TempTargetHotbed":70,"TempOfNozzle":180,
               "CurrentFanSpeed":{"ModelFan":29,"AuxiliaryFan":0,"BoxFan":68},
               "PrintInfo":{"PrintSpeedPct":130}}""",
        ))
        // Targets, not the current (still heating) temperatures.
        assertEquals(Preset("mine", 250, 70, 130, listOf(29, 0, 68)), presetFrom("mine", st))
    }

    @Test
    fun aPresetForAnotherFilamentIsFlaggedMidPrint() {
        fun st(status: Int, file: String) = parseStatus(JSONObject().put("PrintInfo", JSONObject().put("Status", status).put("Filename", file)))
        val printing = st(PrintState.PRINTING, "ECC_0.4_benchy_Elegoo Rapid PLA+_0.2_1h2m.gcode")
        assertEquals("Elegoo Rapid PLA+", presetClash(Preset("ABS", 255, 100), printing))
        assertEquals(null, presetClash(Preset("PLA", 220, 60), printing))
        assertEquals(null, presetClash(Preset("Cool down", 0, 0), printing)) // names no filament
        assertEquals(null, presetClash(Preset("ABS"), st(PrintState.COMPLETE, printing.print.file)))
        assertEquals(null, presetClash(Preset("ABS"), null))
        assertEquals(null, presetClash(Preset("ABS"), st(PrintState.PRINTING, "plain.gcode")))
        // A file whose filament isn't one we know can't be told apart.
        assertEquals(null, presetClash(Preset("ABS"), st(PrintState.PRINTING, "ECC_0.4_x_Silk_0.2_1h2m.gcode")))
        assertEquals("PCTG", presetClash(Preset("PC"), st(PrintState.PRINTING, "ECC_0.4_x_PCTG_0.2_1h2m.gcode")))
    }
}
