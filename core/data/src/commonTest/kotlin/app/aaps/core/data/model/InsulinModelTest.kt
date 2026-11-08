package app.aaps.core.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden characterization of [InsulinModel.Pd] against the Wavez 3.4 plugin curves.
 *
 * The expected numbers were captured on the 3.4 characterization branch from the
 * pre-port formulas:
 *
 *   tp      = (a0 + a1 * amount * concentrationScale) / (1 + b1 * amount)
 *   tpModel = 2 * tp^2
 *   activity(t) = (2 * amount / tpModel) * t * exp(-t^2 / tpModel)
 *   iob(t)      = amount * (exp(-t^2 / tpModel) - exp(-480^2 / tpModel))
 *
 * with the old 8 h truncation (zero at t >= 480). If 4.0 produces anything
 * else, the curve changed.
 *
 * In 4.0 the old `concentrationScale` is [ICfg.concentration], and the
 * Lyumjev model only puts it in the peak numerator when
 * [InsulinModel.Pd.concentrationScalesPeak] is true.
 */
class InsulinModelTest {

    private data class Golden(
        val amount: Double,
        val minutes: Double,
        val activity: Double,
        val iob: Double,
    )

    private data class PeakGolden(
        val amount: Double,
        val tp: Double,
        val activity: Double,
    )

    private val customPd = InsulinModel.Pd(
        a0 = 51.0,
        a1 = 12.27,
        b1 = 0.05185,
        concentrationScalesPeak = false,
        durationMinutes = 480,
    )

    private val lyumjevU100 = InsulinModel.Pd(
        a0 = 61.33,
        a1 = 12.27,
        b1 = 0.05185,
        concentrationScalesPeak = true,
        durationMinutes = 480,
    )

    private val lyumjevU200 = lyumjevU100
    private val u100Concentration = 1.0
    private val u200Concentration = 2.0

    @Test
    fun `custom PD matches the old curve`() {
        assertGolden(customPd, u100Concentration, customGolden)
        assertPeaks(customPd, u100Concentration, customPeaks)
    }

    @Test
    fun `Lyumjev U100 matches the old curve`() {
        assertGolden(lyumjevU100, u100Concentration, u100Golden)
        assertPeaks(lyumjevU100, u100Concentration, u100Peaks)
    }

    @Test
    fun `Lyumjev U200 matches the old curve with the scaled peak numerator`() {
        assertGolden(lyumjevU200, u200Concentration, u200Golden)
        assertPeaks(lyumjevU200, u200Concentration, u200Peaks)
    }

    @Test
    fun `U200 concentration doubles the peak numerator`() {
        // (a0 + a1 * amount * 2) / (1 + b1 * amount), not (a0 + a1 * amount) / ...
        assertEquals(81.63711555830204, lyumjevU200.peakMinutesForDose(1.0, u200Concentration), 1e-12)
        assertEquals(69.97195417597565, lyumjevU100.peakMinutesForDose(1.0, u100Concentration), 1e-12)
        assertEquals(60.151162237961685, customPd.peakMinutesForDose(1.0, u100Concentration), 1e-12)
        // Custom PD never scales its peak with the concentration.
        assertEquals(
            customPd.peakMinutesForDose(1.0, u100Concentration),
            customPd.peakMinutesForDose(1.0, u200Concentration),
            0.0
        )
    }

    @Test
    fun `curve is zero outside the configured duration`() {
        listOf(customPd, lyumjevU100).forEach { model ->
            assertEquals(0.0, model.activityContrib(1.0, 480.0, u100Concentration), 0.0)
            assertEquals(0.0, model.iobContrib(1.0, 480.0, u100Concentration), 0.0)
            assertEquals(0.0, model.activityContrib(1.0, 481.0, u100Concentration), 0.0)
            assertEquals(0.0, model.iobContrib(1.0, 481.0, u100Concentration), 0.0)
            assertEquals(0.0, model.activityContrib(1.0, -1.0, u100Concentration), 0.0)
            assertEquals(0.0, model.iobContrib(1.0, -1.0, u100Concentration), 0.0)
        }
    }

    @Test
    fun `a zero amount produces a zero contribution`() {
        listOf(customPd, lyumjevU100).forEach { model ->
            assertEquals(0.0, model.activityContrib(0.0, 30.0, u100Concentration), 0.0)
            assertEquals(0.0, model.iobContrib(0.0, 30.0, u100Concentration), 0.0)
        }
    }

    /**
     * The 3.4 Wave loop asks for negative doses when the future activity already
     * exceeds the target activity. The model must keep computing the curve for
     * negative amounts; clamping them to zero would change the live iteration
     * sequence captured in `WaveEngineTest`.
     */
    @Test
    fun `negative doses are not clamped`() {
        val expectedTp = mapOf(
            -1.617 to 34.01093620749378,
            -1.122 to 39.532915003361026,
            -1.062 to 40.181862186755005,
            -0.914 to 41.76447611092525,
        )
        val expectedPeakActivity = mapOf(
            -1.617 to -0.028836609223924663,
            -1.122 to -0.017214197337578504,
            -1.062 to -0.016030505445990523,
            -0.914 to -0.013273697519991839,
        )
        expectedTp.forEach { (amount, tp) ->
            assertEquals(tp, customPd.peakMinutesForDose(amount, u100Concentration), 1e-12, "tp for amount=$amount")
        }
        expectedPeakActivity.forEach { (amount, activity) ->
            assertEquals(
                activity,
                customPd.activityAtPeak(amount, u100Concentration),
                1e-12,
                "activityAtPeak for amount=$amount"
            )
        }
        assertEquals(
            -0.028421273565449927,
            customPd.activityContrib(-1.617, 30.0, u100Concentration),
            1e-12,
            "negative-dose activity at 30 min"
        )
    }

    @Test
    fun `extreme but finite doses stay finite`() {
        listOf(0.001, 50.0, 1_000.0, 1e300).forEach { amount ->
            listOf(0.0, 30.0, 300.0, 479.0).forEach { minutes ->
                val activity = customPd.activityContrib(amount, minutes, u100Concentration)
                val iob = customPd.iobContrib(amount, minutes, u100Concentration)
                assertTrue(activity.isFinite(), "activity for amount=$amount t=$minutes was $activity")
                assertTrue(iob.isFinite(), "iob for amount=$amount t=$minutes was $iob")
            }
        }
    }

    @Test
    fun `invalid parameters are not usable`() {
        assertTrue(customPd.isUsable)
        assertTrue(InsulinModel.Bilinear.isUsable)

        assertFalse(InsulinModel.Pd(Double.NaN, 12.27, 0.05185).isUsable)
        assertFalse(InsulinModel.Pd(51.0, Double.POSITIVE_INFINITY, 0.05185).isUsable)
        assertFalse(InsulinModel.Pd(51.0, 12.27, Double.NEGATIVE_INFINITY).isUsable)
        assertFalse(InsulinModel.Pd(0.0, 12.27, 0.05185).isUsable)
        assertFalse(InsulinModel.Pd(-1.0, 12.27, 0.05185).isUsable)
        assertFalse(InsulinModel.Pd(51.0, 0.0, 0.05185).isUsable)
        assertFalse(InsulinModel.Pd(51.0, -12.27, 0.05185).isUsable)
        assertFalse(InsulinModel.Pd(51.0, 12.27, 0.0).isUsable)
        assertFalse(InsulinModel.Pd(51.0, 12.27, -0.05185).isUsable)
        assertFalse(InsulinModel.Pd(51.0, 12.27, 0.05185, durationMinutes = 0).isUsable)
        assertFalse(InsulinModel.Pd(51.0, 12.27, 0.05185, durationMinutes = -1).isUsable)
    }

    private fun assertGolden(model: InsulinModel.Pd, concentration: Double, goldens: List<Golden>) {
        goldens.forEach { golden ->
            assertEquals(
                golden.activity,
                model.activityContrib(golden.amount, golden.minutes, concentration),
                1e-12,
                "activity for amount=${golden.amount} t=${golden.minutes}"
            )
            assertEquals(
                golden.iob,
                model.iobContrib(golden.amount, golden.minutes, concentration),
                1e-12,
                "iob for amount=${golden.amount} t=${golden.minutes}"
            )
        }
    }

    private fun assertPeaks(model: InsulinModel.Pd, concentration: Double, peaks: List<PeakGolden>) {
        peaks.forEach { peak ->
            assertEquals(
                peak.tp,
                model.peakMinutesForDose(peak.amount, concentration),
                1e-12,
                "tp for amount=${peak.amount}"
            )
            assertEquals(
                peak.activity,
                model.activityAtPeak(peak.amount, concentration),
                1e-12,
                "activityAtPeak for amount=${peak.amount}"
            )
        }
    }

    // --- captured 3.4 golden values ---

    private val customGolden = listOf(
        Golden(0.1, 0.0, 0.0, 0.1),
        Golden(0.1, 30.0, 0.0009406536495295526, 0.08464604126314618),
        Golden(0.1, 60.0, 0.0011409843716180417, 0.05133654148308889),
        Golden(0.1, 120.0, 0.00030873759219793057, 0.006945546583947366),
        Golden(0.1, 300.0, 6.404544846256245e-10, 5.763219731260773e-09),
        Golden(0.1, 480.0, 0.0, 0.0),
        Golden(0.5, 0.0, 0.0, 0.49999999999999994),
        Golden(0.5, 30.0, 0.004183174434304428, 0.4324719628839924),
        Golden(0.5, 60.0, 0.00541377035470996, 0.27984774824407876),
        Golden(0.5, 120.0, 0.0018983892352251845, 0.04906561582413375),
        Golden(0.5, 300.0, 2.4172041559931437e-08, 2.498994584845366e-07),
        Golden(0.5, 480.0, 0.0, 0.0),
        Golden(1.0, 0.0, 0.0, 0.9999999999999851),
        Golden(1.0, 30.0, 0.007321817591061022, 0.883050817049169),
        Golden(1.0, 60.0, 0.010083376671043167, 0.6080548919198635),
        Golden(1.0, 120.0, 0.004533820762027386, 0.13670082866958688),
        Golden(1.0, 300.0, 3.2900528822698256e-07, 3.967981773381151e-06),
        Golden(1.0, 480.0, 0.0, 0.0),
        Golden(2.0, 0.0, 0.0, 1.9999999999582463),
        Golden(2.0, 30.0, 0.011635346259749883, 1.8168117638088912),
        Golden(2.0, 60.0, 0.017444122210949882, 1.3619141938029313),
        Golden(2.0, 120.0, 0.011016366290265868, 0.43004014281758235),
        Golden(2.0, 300.0, 8.619719546383255e-06, 0.00013459335601347246),
        Golden(2.0, 480.0, 0.0, 0.0),
        Golden(5.0, 0.0, 0.0, 4.999997406801312),
        Golden(5.0, 30.0, 0.017808105467894336, 4.725181124996822),
        Golden(5.0, 60.0, 0.030060333757794017, 3.9880858615763466),
        Golden(5.0, 120.0, 0.03050760591657686, 2.023711313623471),
        Golden(5.0, 300.0, 0.0006607519802338062, 0.017529729396745575),
        Golden(5.0, 480.0, 0.0, 0.0),
    )

    private val customPeaks = listOf(
        PeakGolden(0.1, 51.95759984480468, 0.0011673569632244692),
        PeakGolden(0.5, 55.69120549747789, 0.005445479715285581),
        PeakGolden(1.0, 60.151162237961685, 0.01008344040491123),
        PeakGolden(2.0, 68.44251155205218, 0.01772379902369165),
        PeakGolden(5.0, 89.21977367480643, 0.03399082034904912),
    )

    private val u100Golden = listOf(
        Golden(0.1, 0.0, 0.0, 0.09999999999998792),
        Golden(0.1, 30.0, 0.0006896085177681687, 0.08903098758823158),
        Golden(0.1, 60.0, 0.0009733212055517195, 0.06282966780288855),
        Golden(0.1, 120.0, 0.0004828146857107468, 0.015583286452877269),
        Golden(0.1, 300.0, 6.969161255132788e-08, 8.997442532679699e-07),
        Golden(0.1, 480.0, 0.0, 0.0),
        Golden(0.5, 0.0, 0.0, 0.49999999999865236),
        Golden(0.5, 30.0, 0.0031258810900906225, 0.4505853030449507),
        Golden(0.5, 60.0, 0.004575341362708334, 0.32976007644423955),
        Golden(0.5, 120.0, 0.0026250507860654504, 0.0945980724989833),
        Golden(0.5, 300.0, 1.0492215572451544e-06, 1.5124176434856033e-05),
        Golden(0.5, 480.0, 0.0, 0.0),
        Golden(1.0, 0.0, 0.0, 0.9999999999395409),
        Golden(1.0, 30.0, 0.005589295652023078, 0.9121869064381016),
        Golden(1.0, 60.0, 0.008484740820869346, 0.6923653679291059),
        Golden(1.0, 120.0, 0.005632154834404101, 0.2297954077677354),
        Golden(1.0, 300.0, 6.246786436887158e-06, 0.0001019490427943646),
        Golden(1.0, 480.0, 0.0, 0.0),
        Golden(2.0, 0.0, 0.0, 1.9999999891412248),
        Golden(2.0, 30.0, 0.00920204346299525, 1.8567090900269225),
        Golden(2.0, 60.0, 0.014725019797356749, 1.485543845699755),
        Golden(2.0, 120.0, 0.012068473163376366, 0.608768140648685),
        Golden(2.0, 300.0, 5.855412018381749e-05, 0.0011814437635251584),
        Golden(2.0, 480.0, 0.0, 0.0),
        Golden(5.0, 0.0, 0.0, 4.999973225255509),
        Golden(5.0, 30.0, 0.01507220679050512, 4.768444873939056),
        Golden(5.0, 60.0, 0.02614775705628746, 4.136223474891095),
        Golden(5.0, 120.0, 0.029605666525605995, 2.3415975047980626),
        Golden(5.0, 300.0, 0.0013793919735293155, 0.04361375918408988),
        Golden(5.0, 480.0, 0.0, 0.0),
    )

    private val u100Peaks = listOf(
        PeakGolden(0.1, 62.23431507632923, 0.0009745920059837323),
        PeakGolden(0.5, 65.76016765358092, 0.00461168729760382),
        PeakGolden(1.0, 69.97195417597565, 0.00866819666329801),
        PeakGolden(2.0, 77.801938932681, 0.01559165923197469),
        PeakGolden(5.0, 97.42306928727416, 0.031128697963936004),
    )

    private val u200Golden = listOf(
        Golden(0.1, 0.0, 0.0, 0.09999999999996245),
        Golden(0.1, 30.0, 0.0006662751276810216, 0.08942600926769197),
        Golden(0.1, 60.0, 0.0009529610446571634, 0.06395218707011782),
        Golden(0.1, 120.0, 0.0004985070991200102, 0.016727136663871264),
        Golden(0.1, 300.0, 1.0436978366859821e-07, 1.4008286672563305e-06),
        Golden(0.1, 480.0, 0.0, 0.0),
        Golden(0.5, 0.0, 0.0, 0.49999999990495314),
        Golden(0.5, 30.0, 0.002670509170796405, 0.45813895586373515),
        Golden(0.5, 60.0, 0.004108712201179064, 0.3524348723944425),
        Golden(0.5, 120.0, 0.0028778113879678333, 0.1234256671098976),
        Golden(0.5, 300.0, 4.6482406191984e-06, 7.974275427989504e-05),
        Golden(0.5, 480.0, 0.0, 0.0),
        Golden(1.0, 0.0, 0.0, 0.9999999688767318),
        Golden(1.0, 30.0, 0.004207480051080645, 0.9347083009397166),
        Golden(1.0, 60.0, 0.006871956525431785, 0.7633161277074718),
        Golden(1.0, 120.0, 0.006112568900344089, 0.3394828073035744),
        Golden(1.0, 300.0, 5.259656181435607e-05, 0.001168422297042589),
        Golden(1.0, 480.0, 0.0, 0.0),
        Golden(2.0, 0.0, 0.0, 1.9999799745633788),
        Golden(2.0, 30.0, 0.005732016369271532, 1.912037269967692),
        Golden(2.0, 60.0, 0.010017287453655188, 1.6707382489642668),
        Golden(2.0, 120.0, 0.01167968168918368, 0.9739923963318975),
        Golden(2.0, 300.0, 0.0006682318014252236, 0.02227051612194458),
        Golden(2.0, 480.0, 0.0, 0.0),
        Golden(5.0, 0.0, 0.0, 4.977277861806121),
        Golden(5.0, 30.0, 0.006876816787010049, 4.873031241924756),
        Golden(5.0, 60.0, 0.01291118291577759, 4.573151894734297),
        Golden(5.0, 120.0, 0.020053508514178267, 3.5464085778147765),
        Golden(5.0, 300.0, 0.008540646279629234, 0.5853047903535683),
        Golden(5.0, 480.0, 0.0, 0.0),
    )

    private val u200Peaks = listOf(
        PeakGolden(0.1, 63.45498589811826, 0.0009558439752653385),
        PeakGolden(0.5, 71.74013694958208, 0.0042272755914788285),
        PeakGolden(1.0, 81.63711555830204, 0.007429594438322272),
        PeakGolden(2.0, 100.03624173235481, 0.012126218442619933),
        PeakGolden(5.0, 146.14254516577327, 0.020751337641773997),
    )
}
