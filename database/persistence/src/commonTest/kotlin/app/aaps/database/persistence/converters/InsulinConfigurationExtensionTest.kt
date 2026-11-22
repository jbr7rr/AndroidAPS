package app.aaps.database.persistence.converters

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.database.entities.embedments.InsulinConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InsulinConfigurationExtensionTest {

    @Test
    fun bilinearRoundTripsThroughDb() {
        val iCfg = ICfg("Rapid", 6L * 3600 * 1000, 75L * 60 * 1000, 1.0, InsulinModel.Bilinear)

        val db = iCfg.toDb()
        assertEquals("""{"type":"BILINEAR"}""", db.insulinModel)
        assertEquals(iCfg, db.fromDb())
    }

    @Test
    fun pdRoundTripsThroughDb() {
        val pd = InsulinModel.Pd(61.33, 12.27, 0.05185, concentrationScalesPeak = true, durationMinutes = 480)
        val iCfg = ICfg("Lyumjev PD", 8L * 3600 * 1000, 65L * 60 * 1000, 2.0, pd)

        val db = iCfg.toDb()
        val restored = db.fromDb()

        assertEquals(iCfg, restored)
        assertEquals(pd, restored.insulinModel)
        assertTrue(restored.isUsable)
    }

    @Test
    fun invalidPdStaysUnusableThroughDb() {
        val db = InsulinConfiguration(
            insulinLabel = "Broken PD",
            insulinEndTime = 8L * 3600 * 1000,
            insulinPeakTime = 51L * 60 * 1000,
            concentration = 1.0,
            insulinModel = """{"type":"PD","a0":-5.0,"a1":12.27,"b1":0.05185}"""
        )

        val restored = db.fromDb()
        assertIs<InsulinModel.Pd>(restored.insulinModel)
        assertTrue(restored.isPresent)
        assertFalse(restored.isUsable)
    }

    @Test
    fun unknownModelInDbFallsBackToBilinear() {
        val db = InsulinConfiguration(
            insulinLabel = "Future",
            insulinEndTime = 8L * 3600 * 1000,
            insulinPeakTime = 51L * 60 * 1000,
            concentration = 1.0,
            insulinModel = """{"type":"FUTURE_MODEL"}"""
        )

        val restored = db.fromDb()
        assertEquals(InsulinModel.Bilinear, restored.insulinModel)
    }
}
