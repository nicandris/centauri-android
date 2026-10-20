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
}
