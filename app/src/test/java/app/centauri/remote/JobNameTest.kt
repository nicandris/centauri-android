package app.centauri.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class JobNameTest {
    @Test
    fun slicerNameSplitsIntoModelAndProfile() {
        val n = jobName("/local/ECC_0.4_canvas-riser-led-petg_Elegoo Rapid PETG _0.2_8h58m.gcode")
        assertEquals("canvas-riser-led-petg", n.title)
        assertEquals("Elegoo Rapid PETG · 0.2 mm · 8h58m", n.detail)
    }

    @Test
    fun modelNamesMayContainUnderscores() {
        assertEquals("box_lid_v2", jobName("ECC_0.4_box_lid_v2_Elegoo PLA+ _0.28_13m29s.gcode").title)
    }

    @Test
    fun otherNamesPassThroughWithoutExtension() {
        val n = jobName("/local/benchy.gcode")
        assertEquals("benchy", n.title)
        assertEquals(null, n.detail)
    }
}
