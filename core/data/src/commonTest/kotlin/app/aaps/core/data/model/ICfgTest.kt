package app.aaps.core.data.model

import kotlin.math.exp
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In `commonTest`, so this runs through Kotlin/Native as well as the JVM - which matters more here
 * than for most tests, because `iobCalcForTreatment` is dosing arithmetic and the whole point of
 * sharing it with a client is that it computes the same numbers everywhere.
 *
 * `kotlin.test` rather than Truth and JUnit 5, because neither of those exists off the JVM. Truth's
 * `isGreaterThan` / `isAtLeast` / `isFinite` become plain boolean assertions with a message, so a
 * failure still says what it expected.
 */
class ICfgTest {

    private fun bolus(amount: Double, iCfg: ICfg, timestamp: Long = 0L) =
        BS(timestamp = timestamp, amount = amount, type = BS.Type.NORMAL, iCfg = iCfg)

    @Test
    fun `valid config produces positive finite IOB shortly after bolus`() {
        val iCfg = ICfg(insulinLabel = "test", peak = 75, dia = 6.0, concentration = 1.0)
        // 30 min after a 1 U bolus
        val iob = iCfg.iobCalcForTreatment(bolus(1.0, iCfg, timestamp = 0L), time = 30 * 60 * 1000L)
        assertTrue(iob.iobContrib.isFinite(), "iobContrib was ${iob.iobContrib}")
        assertTrue(iob.iobContrib > 0.0, "expected > 0.0 but was ${iob.iobContrib}")
        assertTrue(iob.iobContrib < 1.0, "expected < 1.0 but was ${iob.iobContrib}")
    }

    @Test
    fun `migration sentinel -1 does not silently zero IOB`() {
        // v33 migration writes insulinEndTime = -1 / insulinPeakTime = -1 before the repair pass.
        // dia rounds to 0.0 -> td = 0 -> the `t < td` gate would never fire -> silent zero IOB.
        val iCfg = ICfg(insulinLabel = "sentinel", insulinEndTime = -1L, insulinPeakTime = -1L, concentration = 1.0)
        val iob = iCfg.iobCalcForTreatment(bolus(5.0, iCfg, timestamp = 0L), time = 30 * 60 * 1000L)
        assertTrue(iob.iobContrib.isFinite(), "iobContrib was ${iob.iobContrib}")
        // 5 U bolus 30 min ago must still be counted as on board, not silently dropped to 0.
        assertTrue(iob.iobContrib > 0.0, "expected > 0.0 but was ${iob.iobContrib}")
    }

    @Test
    fun `degenerate peak at dia-over-2 singularity does not produce NaN or Infinity`() {
        // dia 5h -> td = 300 min; peak 150 min makes 2*tp == td -> original formula divides by zero.
        val iCfg = ICfg(insulinLabel = "singular", peak = 150, dia = 5.0, concentration = 1.0)
        val iob = iCfg.iobCalcForTreatment(bolus(2.0, iCfg, timestamp = 0L), time = 60 * 60 * 1000L)
        assertTrue(iob.iobContrib.isFinite(), "iobContrib was ${iob.iobContrib}")
        assertTrue(iob.activityContrib.isFinite(), "activityContrib was ${iob.activityContrib}")
    }

    @Test
    fun `peak larger than dia-over-2 does not produce negative IOB`() {
        // 2*tp > td would yield a negative tau and negative iobContrib, inflating dosing.
        val iCfg = ICfg(insulinLabel = "negativeTau", peak = 200, dia = 5.0, concentration = 1.0)
        val iob = iCfg.iobCalcForTreatment(bolus(3.0, iCfg, timestamp = 0L), time = 60 * 60 * 1000L)
        assertTrue(iob.iobContrib.isFinite(), "iobContrib was ${iob.iobContrib}")
        assertTrue(iob.iobContrib >= 0.0, "expected >= 0.0 but was ${iob.iobContrib}")
    }

    @Test
    fun `IOB is zero only after DIA has fully elapsed`() {
        val iCfg = ICfg(insulinLabel = "test", peak = 75, dia = 5.0, concentration = 1.0)
        // 6 h after bolus, well past the 5 h DIA
        val iob = iCfg.iobCalcForTreatment(bolus(1.0, iCfg, timestamp = 0L), time = 6 * 60 * 60 * 1000L)
        assertEquals(0.0, iob.iobContrib)
    }

    // The DB v33 migration stamps this sentinel onto every pre-ICfg row, including the active profile's.
    // Read back as a value it yields DIA 0.0, which fails the APS hard-limit check and aborts every cycle.
    @Test
    fun `the v33 migration sentinel is not a usable insulin`() {
        val sentinel = ICfg(insulinLabel = "", insulinEndTime = -1, insulinPeakTime = -1, concentration = 1.0)

        assertFalse(sentinel.isUsable)
        assertEquals(0.0, sentinel.dia) // the value that blocks the loop
    }

    @Test
    fun `a real insulin is usable`() {
        assertTrue(ICfg(insulinLabel = "test", peak = 75, dia = 5.0, concentration = 1.0).isUsable)
    }

    @Test
    fun `a zero or negative DIA or peak is not usable`() {
        assertFalse(ICfg(insulinLabel = "", insulinEndTime = 0, insulinPeakTime = 4_500_000, concentration = 1.0).isUsable)
        assertFalse(ICfg(insulinLabel = "", insulinEndTime = 18_000_000, insulinPeakTime = 0, concentration = 1.0).isUsable)
    }

    // The insulin list rebuilds every label at start, and a profile switch keeps the label it was made with.
    // Matching by label added one copy of the running insulin to the list at every start.
    @Test
    fun `same curve ignores the label`() {
        val inList = ICfg(insulinLabel = "Novorapid 75m 8h U100", peak = 75, dia = 8.0, concentration = 1.0)
        val running = ICfg(insulinLabel = "Novorapid", peak = 75, dia = 8.0, concentration = 1.0)

        assertTrue(inList.hasSameCurve(running))
        assertFalse(inList.isEqual(running))
    }

    @Test
    fun `a different DIA or peak or concentration is not the same curve`() {
        val base = ICfg(insulinLabel = "x", peak = 75, dia = 8.0, concentration = 1.0)

        assertFalse(base.hasSameCurve(ICfg(insulinLabel = "x", peak = 75, dia = 7.0, concentration = 1.0)))
        assertFalse(base.hasSameCurve(ICfg(insulinLabel = "x", peak = 55, dia = 8.0, concentration = 1.0)))
        assertFalse(base.hasSameCurve(ICfg(insulinLabel = "x", peak = 75, dia = 8.0, concentration = 2.0)))
        assertFalse(base.hasSameCurve(null))
    }

    @Test
    fun `indexOfInsulin prefers the entry with the same label`() {
        val list = listOf(
            ICfg(insulinLabel = "Fiasp 75m 8h U100", peak = 75, dia = 8.0, concentration = 1.0),
            ICfg(insulinLabel = "Novorapid 75m 8h U100", peak = 75, dia = 8.0, concentration = 1.0)
        )

        assertEquals(1, list.indexOfInsulin(ICfg(insulinLabel = "Novorapid 75m 8h U100", peak = 75, dia = 8.0, concentration = 1.0)))
    }

    @Test
    fun `indexOfInsulin falls back to the first entry with the same curve`() {
        val list = listOf(
            ICfg(insulinLabel = "Lyumjev 45m 7h U100", peak = 45, dia = 7.0, concentration = 1.0),
            ICfg(insulinLabel = "Novorapid 75m 8h U100", peak = 75, dia = 8.0, concentration = 1.0)
        )

        assertEquals(1, list.indexOfInsulin(ICfg(insulinLabel = "Novorapid", peak = 75, dia = 8.0, concentration = 1.0)))
        assertEquals(-1, list.indexOfInsulin(ICfg(insulinLabel = "Novorapid", peak = 75, dia = 9.0, concentration = 1.0)))
        assertEquals(-1, list.indexOfInsulin(null))
    }

    @Test
    fun `labelOfInsulin gives the label from the list and keeps its own label when nothing matches`() {
        val list = listOf(ICfg(insulinLabel = "Novorapid 75m 8h U100", peak = 75, dia = 8.0, concentration = 1.0))

        assertEquals("Novorapid 75m 8h U100", list.labelOfInsulin(ICfg(insulinLabel = "Novorapid", peak = 75, dia = 8.0, concentration = 1.0)))
        assertEquals("Novorapid", list.labelOfInsulin(ICfg(insulinLabel = "Novorapid", peak = 75, dia = 9.0, concentration = 1.0)))
        assertEquals(null, list.labelOfInsulin(null))
    }

    // --- PD model integration (P3) ---

    /**
     * The old bilinear formula, copied from the pre-PD [ICfg.iobCalcForTreatment].
     * The test compares against it with zero tolerance, so extracting the PD
     * branch did not change any bilinear number.
     */
    private fun oldBilinearCurve(amount: Double, timeMinutes: Double, peak: Int, dia: Double): Pair<Double, Double> {
        var activity = 0.0
        var iob = 0.0
        if (amount != 0.0) {
            val td = (dia * 60).coerceAtLeast(300.0)
            val tp = peak.toDouble().coerceIn(1.0, td / 2.0 - 1.0)
            if (timeMinutes < td) {
                val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
                val a = 2 * tau / td
                val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
                activity = amount * (s / tau.pow(2.0)) * timeMinutes * (1 - timeMinutes / td) * exp(-timeMinutes / tau)
                iob = amount * (1 - s * (1 - a) * ((timeMinutes.pow(2.0) / (tau * td * (1 - a)) - timeMinutes / tau - 1) * exp(-timeMinutes / tau) + 1))
            }
        }
        return activity to iob
    }

    @Test
    fun `bilinear curve matches the pre-port formula exactly`() {
        val configs = listOf(
            ICfg(insulinLabel = "rapid", peak = 75, dia = 8.0, concentration = 1.0),
            ICfg(insulinLabel = "ultra", peak = 45, dia = 5.0, concentration = 1.0),
            ICfg(insulinLabel = "slow", peak = 120, dia = 6.0, concentration = 1.0),
            // Degenerate peak at the old singular point: the defensive clamp must stay.
            ICfg(insulinLabel = "singular", peak = 150, dia = 5.0, concentration = 1.0),
            // The DB v33 sentinel: the existing floor must stay.
            ICfg(insulinLabel = "sentinel", insulinEndTime = -1L, insulinPeakTime = -1L, concentration = 1.0),
        )
        val amounts = listOf(0.1, 1.0, 5.0)
        val times = listOf(0.0, 30.0, 60.0, 120.0, 300.0, 479.0, 480.0)

        configs.forEach { iCfg ->
            amounts.forEach { amount ->
                times.forEach { minutes ->
                    val expected = oldBilinearCurve(amount, minutes, iCfg.peak, iCfg.dia)
                    val actual = iCfg.iobCalcForTreatment(bolus(amount, iCfg), time = minutes.toLong() * 60_000L)
                    assertEquals(expected.first, actual.activityContrib, 0.0, "activity ${iCfg.insulinLabel} ${amount}U @${minutes}min")
                    assertEquals(expected.second, actual.iobContrib, 0.0, "iob ${iCfg.insulinLabel} ${amount}U @${minutes}min")
                }
            }
        }
    }

    /**
     * Duration vs profile DIA decision for PD:
     *
     * The 3.4 PD plugins always used a fixed 8 h curve, whatever DIA the profile
     * had. 4.0 keeps that: the PD branch uses
     * [InsulinModel.Pd.durationMinutes], not [ICfg.insulinEndTime]. P5 sets the
     * PD presets to DIA 8 h so the 4.0 IOB query window covers the full curve.
     * This test pins both parts: the curve is 8 h even with a 5 h DIA, and the
     * result does not depend on the DIA.
     */
    @Test
    fun `PD curve uses durationMinutes and not the profile DIA`() {
        val model = InsulinModel.Pd(a0 = 51.0, a1 = 12.27, b1 = 0.05185, durationMinutes = 480)
        val dia5 = ICfg(insulinLabel = "Custom PD", peak = 51, dia = 5.0, concentration = 1.0, insulinModel = model)
        val dia8 = dia5.copy(insulinEndTime = 8L * 3600 * 1000)
        val amount = 1.0

        listOf(0.0, 30.0, 60.0, 120.0, 300.0, 479.0).forEach { minutes ->
            val expected = model.iobContrib(amount, minutes, dia5.concentration)
            val fromDia5 = dia5.iobCalcForTreatment(bolus(amount, dia5), time = minutes.toLong() * 60_000L)
            val fromDia8 = dia8.iobCalcForTreatment(bolus(amount, dia8), time = minutes.toLong() * 60_000L)

            assertEquals(expected, fromDia5.iobContrib, 0.0, "DIA 5h at $minutes min")
            assertEquals(fromDia5.iobContrib, fromDia8.iobContrib, 0.0, "DIA must not change the PD curve at $minutes min")
        }
        assertTrue(dia5.iobCalcForTreatment(bolus(amount, dia5), time = 479L * 60_000L).iobContrib > 0.0)
        assertEquals(0.0, dia5.iobCalcForTreatment(bolus(amount, dia5), time = 480L * 60_000L).iobContrib)
    }

    @Test
    fun `deepClone copies the insulin model`() {
        val model = InsulinModel.Pd(a0 = 51.0, a1 = 12.27, b1 = 0.05185, concentrationScalesPeak = false)
        val original = ICfg(insulinLabel = "Custom PD", peak = 51, dia = 8.0, concentration = 1.0, insulinModel = model)
            .also { it.insulinNickname = "custom" }

        val clone = original.deepClone()

        assertEquals(original, clone)
        assertEquals(model, clone.insulinModel)
        assertEquals("custom", clone.insulinNickname)
    }

    @Test
    fun `isEqual and hasSameCurve include the insulin model and its parameters`() {
        val bilinear = ICfg(insulinLabel = "PD", peak = 51, dia = 8.0, concentration = 1.0)
        val model = InsulinModel.Pd(a0 = 51.0, a1 = 12.27, b1 = 0.05185, concentrationScalesPeak = false)
        val pd = bilinear.copy(insulinModel = model)

        assertFalse(pd.isEqual(bilinear))
        assertFalse(bilinear.isEqual(pd))
        assertFalse(pd.hasSameCurve(bilinear))
        assertFalse(bilinear.hasSameCurve(pd))
        assertTrue(pd.isEqual(pd.copy()))
        assertTrue(pd.hasSameCurve(pd.copy()))

        // Same peak, DIA and concentration, but another curve parameter.
        val otherModel = model.copy(b1 = 0.05186)
        assertFalse(pd.isEqual(pd.copy(insulinModel = otherModel)))
        assertFalse(pd.hasSameCurve(pd.copy(insulinModel = otherModel)))

        // Scaling the peak is a different curve too.
        assertFalse(pd.hasSameCurve(pd.copy(insulinModel = model.copy(concentrationScalesPeak = true))))
        assertFalse(pd.hasSameCurve(pd.copy(insulinModel = model.copy(durationMinutes = 300))))
    }

    @Test
    fun `peakMinutesForDose returns peak for bilinear and tp for PD`() {
        val bilinear = ICfg(insulinLabel = "rapid", peak = 75, dia = 8.0, concentration = 1.0)
        assertEquals(75.0, bilinear.peakMinutesForDose(1.0))

        val custom = bilinear.copy(
            insulinLabel = "Custom PD",
            insulinPeakTime = 51L * 60_000,
            insulinModel = InsulinModel.Pd(a0 = 51.0, a1 = 12.27, b1 = 0.05185, concentrationScalesPeak = false)
        )
        assertEquals(60.151162237961685, custom.peakMinutesForDose(1.0), 1e-12)

        val lyumjevU200 = bilinear.copy(
            insulinLabel = "Lyumjev PD",
            insulinPeakTime = 65L * 60_000,
            concentration = 2.0,
            insulinModel = InsulinModel.Pd(a0 = 61.33, a1 = 12.27, b1 = 0.05185, concentrationScalesPeak = true)
        )
        assertEquals(81.63711555830204, lyumjevU200.peakMinutesForDose(1.0), 1e-12)
    }

    @Test
    fun `activityAtPeak evaluates the curve at the peak`() {
        val bilinear = ICfg(insulinLabel = "rapid", peak = 75, dia = 8.0, concentration = 1.0)
        val (expectedBilinearActivity, _) = oldBilinearCurve(1.0, 75.0, bilinear.peak, bilinear.dia)
        assertEquals(expectedBilinearActivity, bilinear.activityAtPeak(1.0), 0.0)

        val custom = bilinear.copy(
            insulinLabel = "Custom PD",
            insulinPeakTime = 51L * 60_000,
            insulinModel = InsulinModel.Pd(a0 = 51.0, a1 = 12.27, b1 = 0.05185, concentrationScalesPeak = false)
        )
        assertEquals(0.01008344040491123, custom.activityAtPeak(1.0), 1e-12)
    }

    @Test
    fun `an invalid PD model makes the ICfg unusable and returns zero instead of NaN`() {
        val invalid = ICfg(
            insulinLabel = "broken",
            peak = 51,
            dia = 8.0,
            concentration = 1.0,
            insulinModel = InsulinModel.Pd(a0 = Double.NaN, a1 = 12.27, b1 = 0.05185)
        )

        assertFalse(invalid.isUsable)
        val iob = invalid.iobCalcForTreatment(bolus(1.0, invalid), time = 30L * 60_000L)
        assertEquals(0.0, iob.iobContrib)
        assertEquals(0.0, iob.activityContrib)
        assertEquals(0.0, invalid.activityAtPeak(1.0))
    }

    @Test
    fun `isPresent keeps the old sentinel meaning and isUsable adds the model`() {
        val invalidModel = ICfg(
            insulinLabel = "broken",
            peak = 51,
            dia = 8.0,
            concentration = 1.0,
            insulinModel = InsulinModel.Pd(a0 = Double.NaN, a1 = 12.27, b1 = 0.05185)
        )
        assertTrue(invalidModel.isPresent)
        assertFalse(invalidModel.isUsable)

        val sentinel = ICfg(insulinLabel = "", insulinEndTime = -1, insulinPeakTime = -1, concentration = 1.0)
        assertFalse(sentinel.isPresent)
        assertFalse(sentinel.isUsable)

        val zeroPeak = ICfg(insulinLabel = "", insulinEndTime = 8L * 3600 * 1000, insulinPeakTime = 0, concentration = 1.0)
        assertFalse(zeroPeak.isPresent)
        assertFalse(zeroPeak.isUsable)
    }

    @Test
    fun `a PD model whose curve term overflows is not usable`() {
        val huge = ICfg(
            insulinLabel = "huge",
            peak = 51,
            dia = 8.0,
            concentration = 1.0,
            insulinModel = InsulinModel.Pd(a0 = 1e308, a1 = 12.27, b1 = 0.05185)
        )
        assertTrue(huge.isPresent)
        assertFalse(huge.isUsable)
        // The curve must not return a value derived from Infinity.
        assertEquals(0.0, huge.activityAtPeak(1.0))
        assertEquals(0.0, huge.iobCalcForTreatment(bolus(1.0, huge), time = 30L * 60_000L).iobContrib)
    }

    @Test
    fun `peakMinutesForDose never returns NaN for an unusable model`() {
        val invalid = ICfg(
            insulinLabel = "broken",
            peak = 51,
            dia = 8.0,
            concentration = 1.0,
            insulinModel = InsulinModel.Pd(a0 = Double.NaN, a1 = 12.27, b1 = 0.05185)
        )
        assertTrue(invalid.peakMinutesForDose(1.0).isFinite())
        assertEquals(51.0, invalid.peakMinutesForDose(1.0), 0.0)
    }

    @Test
    fun `PD IOB duration covers the model duration when DIA is shorter`() {
        val model = InsulinModel.Pd(a0 = 51.0, a1 = 12.27, b1 = 0.05185, durationMinutes = 480)
        val pd5 = ICfg(insulinLabel = "Custom PD", peak = 51, dia = 5.0, concentration = 1.0, insulinModel = model)
        assertEquals(5.0, pd5.dia)
        assertEquals(8.0, pd5.iobDurationHours)
        assertEquals(8L * 3600 * 1000, pd5.iobEndTime)

        val pd8 = pd5.copy(insulinEndTime = 8L * 3600 * 1000)
        assertEquals(8.0, pd8.iobDurationHours)
        assertEquals(8L * 3600 * 1000, pd8.iobEndTime)

        val pd10 = pd5.copy(insulinEndTime = 10L * 3600 * 1000)
        assertEquals(10.0, pd10.iobDurationHours)
        assertEquals(10L * 3600 * 1000, pd10.iobEndTime)

        val bilinear = ICfg(insulinLabel = "rapid", peak = 75, dia = 5.0, concentration = 1.0)
        assertEquals(5.0, bilinear.iobDurationHours)
        assertEquals(5L * 3600 * 1000, bilinear.iobEndTime)

        val sentinel = ICfg(insulinLabel = "", insulinEndTime = -1, insulinPeakTime = -1, concentration = 1.0)
        assertEquals(0.0, sentinel.iobDurationHours)
        assertEquals(-1L, sentinel.iobEndTime)
    }

    @Test
    fun `a PD model needs a positive finite concentration`() {
        val model = InsulinModel.Pd(a0 = 51.0, a1 = 12.27, b1 = 0.05185, concentrationScalesPeak = false)

        assertTrue(ICfg(insulinLabel = "pd", peak = 51, dia = 8.0, concentration = 1.0, insulinModel = model).isUsable)
        assertFalse(ICfg(insulinLabel = "pd", peak = 51, dia = 8.0, concentration = 0.0, insulinModel = model).isUsable)
        assertFalse(ICfg(insulinLabel = "pd", peak = 51, dia = 8.0, concentration = -1.0, insulinModel = model).isUsable)
        assertFalse(ICfg(insulinLabel = "pd", peak = 51, dia = 8.0, concentration = Double.NaN, insulinModel = model).isUsable)
    }
}
