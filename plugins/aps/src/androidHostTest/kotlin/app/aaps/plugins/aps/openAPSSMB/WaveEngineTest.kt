package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ported 3.4 characterization tests for [WaveEngine], verified against 4.0's [ICfg] + [InsulinModel.Pd].
 * Locks exact parity with the 3.4 daily-driver golden cycles and the safety guards (iteration cap, finite guard).
 */
class WaveEngineTest {

    private val activeSettings = WaveSettings(
        enabled = true,
        insulinReqPct = 65.0,
        startHour = 11,
        endHour = 21,
        maxMinutesOfBasalToLimitSmb = 60,
        useSmbCap = false,
        smbCap = 0.5,
        useAdjustedSens = false,
    )

    private val baseGlucose = WaveGlucoseStatus(glucose = 180.0, delta = 6.0, shortAvgDelta = 8.0)

    private val baseActivity = WaveActivitySnapshot(
        currentActivity = 0.05,
        futureActivity = 0.01,
        sensorLagActivity = 0.04,
        historicActivity = 0.03,
        dia = 5.0,
    )

    /** Live Wave settings from the 2026-11-08 daily driver. */
    private val liveWaveSettings = WaveSettings(
        enabled = true,
        insulinReqPct = 80.0,
        startHour = 8,
        endHour = 23,
        maxMinutesOfBasalToLimitSmb = 180,
        useSmbCap = false,
        smbCap = 0.5,
        useAdjustedSens = true,
    )

    private val customPdICfg = ICfg(
        insulinLabel = "Custom PD",
        peak = 51,
        dia = 8.0,
        concentration = 1.0,
        insulinModel = InsulinModel.Pd(
            a0 = 51.0,
            a1 = 12.27,
            b1 = 0.05185,
            concentrationScalesPeak = false,
            durationMinutes = 480
        )
    )

    private val customPdActivityAtPeak: (Double) -> Double = { customPdICfg.activityAtPeak(it) }

    private fun run(
        settings: WaveSettings = activeSettings,
        currentHour: Int = 12,
        glucose: WaveGlucoseStatus = baseGlucose,
        targetBg: Double = 100.0,
        adjustedSens: Double = 40.0,
        profileSens: Double = 40.0,
        currentBasal: Double = 1.0,
        iob: Double = 1.0,
        lastBolusAmount: Double? = 0.5,
        lastBolusAgeMinutes: Double = 60.0,
        activity: WaveActivitySnapshot = baseActivity,
        dia: Double = 5.0,
        peak: Int = 60,
        isPd: Boolean = false,
        activityAtPeak: (Double) -> Double = { 0.01 * it },
    ): WaveResult {
        val now = 1_000_000_000_000L
        return WaveEngine.determine(
            settings = settings,
            currentHour = currentHour,
            glucose = glucose,
            targetBg = targetBg,
            adjustedSens = adjustedSens,
            profileSens = profileSens,
            currentBasal = currentBasal,
            iob = iob,
            lastBolusAmount = lastBolusAmount,
            lastBolusTime = now - (lastBolusAgeMinutes * 60_000.0).toLong(),
            currentTimeMillis = now,
            activity = activity,
            dia = dia,
            peak = peak,
            isPd = isPd,
            activityAtPeak = activityAtPeak,
        )
    }

    @Test
    fun disabledWaveReturnsZeroAndInactive() {
        val result = run(settings = activeSettings.copy(enabled = false))
        assertEquals(0.0, result.insReq, 0.0)
        assertEquals(0.0, result.smbCap, 0.0)
        assertFalse(result.activityControllerActive)
        assertEquals(0, result.reason.length)
        assertTrue(result.logs.isEmpty())
    }

    @Test
    fun activeWaveProducesTheExpectedOldResult() {
        val result = run()
        assertTrue(result.activityControllerActive)
        assertEquals(1.3, result.insReq, 1e-12)
        assertEquals(1.0, result.smbCap, 1e-12)
        assertTrue(result.reason.contains("##TSUNAMI STATUS##"))
        assertTrue(result.logs.any { it == "WAVE STATUS" })
    }

    @Test
    fun timeWindowNormalHours() {
        assertTrue(run(currentHour = 11).activityControllerActive)
        assertTrue(run(currentHour = 12).activityControllerActive)
        assertTrue(run(currentHour = 21).activityControllerActive)
        assertFalse(run(currentHour = 10).activityControllerActive)
        assertFalse(run(currentHour = 22).activityControllerActive)
    }

    @Test
    fun timeWindowCrossMidnight() {
        val settings = activeSettings.copy(startHour = 22, endHour = 6)
        assertTrue(run(settings = settings, currentHour = 22).activityControllerActive)
        assertTrue(run(settings = settings, currentHour = 23).activityControllerActive)
        assertTrue(run(settings = settings, currentHour = 0).activityControllerActive)
        assertTrue(run(settings = settings, currentHour = 6).activityControllerActive)
        assertFalse(run(settings = settings, currentHour = 7).activityControllerActive)
        assertFalse(run(settings = settings, currentHour = 12).activityControllerActive)
    }

    @Test
    fun timeWindowStartEqualsEndIsOneInclusiveHour() {
        val settings = activeSettings.copy(startHour = 10, endHour = 10)
        assertFalse(run(settings = settings, currentHour = 9).activityControllerActive)
        assertTrue(run(settings = settings, currentHour = 10).activityControllerActive)
        assertFalse(run(settings = settings, currentHour = 11).activityControllerActive)
    }

    @Test
    fun deltaGate() {
        assertFalse(run(glucose = baseGlucose.copy(delta = 4.09)).activityControllerActive)
        assertTrue(run(glucose = baseGlucose.copy(delta = 4.1)).activityControllerActive)
    }

    @Test
    fun bgBelowTargetGate() {
        assertFalse(run(glucose = baseGlucose.copy(glucose = 99.0)).activityControllerActive)
        assertTrue(run(glucose = baseGlucose.copy(glucose = 100.0)).activityControllerActive)
    }

    @Test
    fun iobGate() {
        assertFalse(run(iob = 0.1).activityControllerActive)
        assertTrue(run(iob = 0.11).activityControllerActive)
    }

    @Test
    fun nonPositiveActivityGate() {
        assertFalse(run(activity = baseActivity.copy(sensorLagActivity = 0.0)).activityControllerActive)
        assertFalse(run(activity = baseActivity.copy(sensorLagActivity = -0.01)).activityControllerActive)
    }

    @Test
    fun incompatibleInsulinAndGlucoseStateGate() {
        val result = run(
            glucose = baseGlucose.copy(glucose = 300.0),
            adjustedSens = 100.0,
            profileSens = 40.0,
        )
        assertFalse(result.activityControllerActive)
        assertTrue(result.logs.any { it.contains("Incompatible insulin & glucose status") })
    }

    @Test
    fun fixedSmbCap() {
        val result = run(settings = activeSettings.copy(useSmbCap = true, smbCap = 2.5))
        assertEquals(2.5, result.smbCap, 1e-12)
    }

    @Test
    fun minutesOfBasalSmbCap() {
        val result = run(
            settings = activeSettings.copy(maxMinutesOfBasalToLimitSmb = 90),
            currentBasal = 2.0,
        )
        assertEquals(3.0, result.smbCap, 1e-12)
    }

    @Test
    fun recentLargeBolusReducesSmbCap() {
        val result = run(lastBolusAmount = 0.7, lastBolusAgeMinutes = 9.0)
        assertEquals(0.3, result.smbCap, 1e-12)
    }

    @Test
    fun bolusJustBelowSeventyPercentDoesNotReduceSmbCap() {
        val result = run(lastBolusAmount = 0.69, lastBolusAgeMinutes = 9.0)
        assertEquals(1.0, result.smbCap, 1e-12)
    }

    @Test
    fun bolusJustOverNineMinutesDoesNotReduceSmbCap() {
        val result = run(lastBolusAmount = 0.7, lastBolusAgeMinutes = 10.0)
        assertEquals(1.0, result.smbCap, 1e-12)
    }

    @Test
    fun insulinReqPctScalesTheResult() {
        assertEquals(2.0, run(settings = activeSettings.copy(insulinReqPct = 100.0)).insReq, 1e-12)
        assertEquals(1.0, run(settings = activeSettings.copy(insulinReqPct = 50.0)).insReq, 1e-12)
    }

    @Test
    fun deltaScoreScalesInsulinReqPct() {
        val result = run(glucose = baseGlucose.copy(shortAvgDelta = 2.0))
        assertEquals(0.65, result.insReq, 1e-12)
    }

    @Test
    fun bgScoreScalesSmbCap() {
        val result = run(glucose = baseGlucose.copy(glucose = 115.0))
        assertEquals(0.5, result.smbCap, 1e-12)
        assertTrue(result.activityControllerActive)
    }

    @Test
    fun pdBranchIsUsedAndIterates() {
        val activityAtPeak: (Double) -> Double = { 0.01 * (1 + 0.5 * it) }
        val bilinear = run(
            glucose = baseGlucose.copy(glucose = 130.0),
            isPd = false,
            activityAtPeak = activityAtPeak,
        )
        val pd = run(
            glucose = baseGlucose.copy(glucose = 130.0),
            isPd = true,
            activityAtPeak = activityAtPeak,
        )
        assertTrue(bilinear.activityControllerActive)
        assertTrue(pd.activityControllerActive)
        assertEquals(-1, bilinear.iterations)
        assertTrue(pd.iterations > 0)
        assertTrue(pd.insReq > bilinear.insReq)
    }

    @Test
    fun liveCustomPdCycle1441Matches() {
        val result = run(
            settings = liveWaveSettings,
            currentHour = 14,
            glucose = WaveGlucoseStatus(glucose = 119.8, delta = 4.96, shortAvgDelta = 4.6),
            targetBg = 90.0,
            adjustedSens = 16.9,
            profileSens = 16.9,
            currentBasal = 1.4,
            iob = 4.45,
            lastBolusAmount = 1.1,
            lastBolusAgeMinutes = 4.8,
            activity = WaveActivitySnapshot(
                currentActivity = 0.1716,
                futureActivity = 0.2181,
                sensorLagActivity = 0.1471,
            ),
            isPd = true,
            activityAtPeak = customPdActivityAtPeak,
        )
        assertTrue(result.activityControllerActive)
        assertEquals(0.02, result.insReq, 1e-12)
        assertEquals(4.17, result.smbCap, 1e-12)
        assertEquals(2, result.iterations)
        assertTrue(result.logs.any { it.contains("tsunami_insreq (1): 0.02") })
    }

    @Test
    fun liveCustomPdCycle1446Matches() {
        val result = run(
            settings = liveWaveSettings,
            currentHour = 14,
            glucose = WaveGlucoseStatus(glucose = 132.9, delta = 9.6, shortAvgDelta = 8.59),
            targetBg = 90.0,
            adjustedSens = 15.8,
            profileSens = 16.9,
            currentBasal = 1.4,
            iob = 4.323,
            lastBolusAmount = 1.1,
            lastBolusAgeMinutes = 10.0,
            activity = WaveActivitySnapshot(
                currentActivity = 0.1844,
                futureActivity = 0.2137,
                sensorLagActivity = 0.1583,
            ),
            isPd = true,
            activityAtPeak = customPdActivityAtPeak,
        )
        assertTrue(result.activityControllerActive)
        assertEquals(3.91, result.insReq, 1e-12)
        assertEquals(4.2, result.smbCap, 1e-12)
        assertEquals(4, result.iterations)
        assertTrue(result.logs.any { it.contains("tsunami_insreq (4): 4.888") })
    }

    @Test
    fun liveCustomPdNegativeActMissingCycle1421Matches() {
        val result = run(
            settings = liveWaveSettings,
            currentHour = 14,
            glucose = WaveGlucoseStatus(glucose = 103.5, delta = -0.98, shortAvgDelta = -0.81),
            targetBg = 90.0,
            adjustedSens = 19.0,
            profileSens = 19.0,
            currentBasal = 1.4,
            iob = 2.5,
            lastBolusAmount = 1.1,
            lastBolusAgeMinutes = 15.0,
            activity = WaveActivitySnapshot(
                currentActivity = 0.0,
                futureActivity = 0.0552,
                sensorLagActivity = 0.0,
            ),
            isPd = true,
            activityAtPeak = customPdActivityAtPeak,
        )
        assertFalse(result.activityControllerActive)
        assertEquals(0.0, result.insReq, 0.0)
        assertEquals(4, result.iterations)
        assertTrue(result.logs.any { it.contains("tsunami_insreq (1): -1.617") })
        assertTrue(result.logs.any { it.contains("tsunami_insreq (4): -1.062") })
    }

    @Test
    fun nonConvergingPdLoopStopsAtIterationCapAndIsFinite() {
        val result = run(isPd = true, activityAtPeak = { 0.5 })
        assertTrue(result.insReq.isFinite())
        assertTrue(result.logs.any { it.contains("iteration cap") })
        assertTrue(result.iterations <= WaveEngine.MAX_ITERATIONS)
        assertEquals(1.3, result.insReq, 1e-12)
    }

    @Test
    fun divergingHighPdLoopStaysFinite() {
        val result = run(isPd = true, activityAtPeak = { 0.0001 })
        assertTrue(result.insReq.isFinite())
        assertEquals(1.3, result.insReq, 1e-12)
        assertTrue(result.logs.any { it.contains("iteration cap") })
        assertTrue(result.iterations <= WaveEngine.MAX_ITERATIONS)
    }

    @Test
    fun roundNeverReturnsNonFinite() {
        assertEquals(0.0, WaveEngine.round(Double.NaN, 2), 0.0)
        assertEquals(0.0, WaveEngine.round(Double.POSITIVE_INFINITY, 2), 0.0)
        assertEquals(0.0, WaveEngine.round(Double.NEGATIVE_INFINITY, 2), 0.0)
        assertEquals(0.0, WaveEngine.round(1e307, 2), 0.0)
    }

    @Test
    fun nonFinitePdActivityIsGuarded() {
        val result = run(isPd = true, activityAtPeak = { Double.NaN })
        assertTrue(result.insReq.isFinite())
        assertTrue(result.logs.any { it.contains("non-finite") })
        assertEquals(1.3, result.insReq, 1e-12)
    }

    @Test
    fun nonFiniteBilinearResultIsGuarded() {
        val result = run(isPd = false, activityAtPeak = { Double.NaN })
        assertTrue(result.insReq.isFinite())
        assertTrue(result.logs.any { it.contains("non-finite") })
        assertEquals(1.3, result.insReq, 1e-12)
    }

    @Test
    fun waveUseAdjustedSensFalseUsesRoundedProfileSens() {
        val result = run(adjustedSens = 100.0, profileSens = 37.0)
        assertEquals(1.19, result.insReq, 1e-12)
    }

    @Test
    fun waveUseAdjustedSensTrueUsesAdjustedSens() {
        val falseRes = run(settings = activeSettings.copy(useAdjustedSens = false), adjustedSens = 100.0, profileSens = 37.0)
        val trueRes = run(settings = activeSettings.copy(useAdjustedSens = true), adjustedSens = 100.0, profileSens = 37.0)
        assertEquals(1.19, falseRes.insReq, 1e-12)
        assertEquals(0.52, trueRes.insReq, 1e-12)
    }
}
