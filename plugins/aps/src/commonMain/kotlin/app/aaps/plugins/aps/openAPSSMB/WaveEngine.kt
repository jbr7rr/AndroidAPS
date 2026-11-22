package app.aaps.plugins.aps.openAPSSMB

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Wave (formerly "Tsunami Activity Engine") settings snapshot.
 *
 * Mirrors the preference values exactly so the engine stays pure and testable.
 * The defaults match the 3.4 keys.
 */
data class WaveSettings(
    val enabled: Boolean = false,
    val insulinReqPct: Double = 65.0,
    val startHour: Int = 11,
    val endHour: Int = 21,
    val maxMinutesOfBasalToLimitSmb: Int = 60,
    val useSmbCap: Boolean = false,
    val smbCap: Double = 0.5,
    val useAdjustedSens: Boolean = false,
)

/** Glucose values the Wave engine needs, decoupled from AAPS' GlucoseStatusSMB. */
data class WaveGlucoseStatus(
    val glucose: Double,
    val delta: Double,
    val shortAvgDelta: Double,
)

/**
 * Activity samples gathered by [OpenAPSSMBPlugin] around the current time,
 * the sensor-lag time, the insulin peak and the historic window.
 */
data class WaveActivitySnapshot(
    val currentActivity: Double = 0.0,
    val futureActivity: Double = 0.0,
    val sensorLagActivity: Double = 0.0,
    val historicActivity: Double = 0.0,
    val dia: Double = 0.0,
)

/**
 * Pre-gathered Wave inputs passed from suspend [OpenAPSSMBPlugin.invoke] into pure,
 * non-suspend [DetermineBasalSMB.determine_basal]. Keeps `DetermineBasalSMB` free of
 * `Preferences`, `PersistenceLayer`, `IobCobCalculator`, `ProfileFunction`, and `ICfg`.
 */
data class WaveSnapshot(
    val settings: WaveSettings = WaveSettings(enabled = false),
    val currentHour: Int = 0,
    val lastBolusAmount: Double? = null,
    val activity: WaveActivitySnapshot = WaveActivitySnapshot(),
    val dia: Double = 0.0,
    val peak: Int = 0,
    val isPd: Boolean = false,
    val activityAtPeak: (Double) -> Double = { 0.0 },
) {
    companion object {
        val Disabled = WaveSnapshot()
    }
}

data class WaveResult(
    val insReq: Double = 0.0,
    val smbCap: Double = 0.0,
    val activityControllerActive: Boolean = false,
    val reason: StringBuilder = StringBuilder(),
    val logs: List<String> = emptyList(),
    val iterations: Int = 0,
)

/**
 * Pure Wave decision engine, ported from the 3.4 characterization branch.
 * All I/O (preferences, persistence, IOB calculation) stays in [OpenAPSSMBPlugin].
 *
 * The engine deliberately knows nothing about `Insulin`/`ICfg`: the caller passes
 * `isPd`, `peak`, `dia` and an `activityAtPeak` lambda (`iCfg.activityAtPeak`).
 */
object WaveEngine {

    /** Safety cap for the PD iterative dose estimation. The old 3.4 loop had none. */
    const val MAX_ITERATIONS = 100

    fun disabled(): WaveResult = WaveResult(reason = StringBuilder())

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun determine(
        settings: WaveSettings,
        currentHour: Int,
        glucose: WaveGlucoseStatus,
        targetBg: Double,
        adjustedSens: Double,
        profileSens: Double,
        currentBasal: Double,
        iob: Double,
        lastBolusAmount: Double?,
        lastBolusTime: Long,
        currentTimeMillis: Long,
        activity: WaveActivitySnapshot,
        dia: Double,
        peak: Int,
        isPd: Boolean,
        activityAtPeak: (Double) -> Double,
    ): WaveResult {
        if (!settings.enabled) return disabled()

        val logs = mutableListOf<String>()
        var insulinReqPct = settings.insulinReqPct / 100.0
        val deltaReductionPct = 0.5
        val usedSens = if (settings.useAdjustedSens) adjustedSens else round(profileSens, 1)
        val bg = glucose.glucose

        var startTime = settings.startHour.toDouble()
        var endTime = settings.endHour.toDouble()
        // Active hours redefinition, allowing end times < start times.
        var referenceTimer = currentHour.toDouble()
        if (endTime < startTime) {
            referenceTimer = if (currentHour < startTime) {
                currentHour - startTime + 24
            } else {
                currentHour - startTime
            }
            endTime = 24 - (startTime - endTime)
            startTime = 0.0
        }

        var smbCap = if (settings.useSmbCap) {
            settings.smbCap
        } else {
            round(currentBasal * settings.maxMinutesOfBasalToLimitSmb / 60, 1)
        }

        // Give SMBs that are 70% of SMBcap or more extra time to be absorbed
        // before delivering another large SMB.
        val lastBolusAge = round((currentTimeMillis - lastBolusTime) / 60000.0, 1)
        if (lastBolusAge <= 9 && lastBolusAmount != null && lastBolusAmount >= 0.70 * smbCap) {
            smbCap = max(smbCap - lastBolusAmount, 0.0)
        }

        val actCurr = activity.sensorLagActivity
        val actFuture = activity.futureActivity
        val pureDelta = round(min(glucose.delta + max(actCurr * usedSens, 0.0), 35.0), 1)
        val actTargetDelta = (pureDelta / usedSens) * deltaReductionPct
        val actMissing = round((actTargetDelta - max(actFuture, 0.0)) / 5, 4)

        var insulinReq: Double
        var iterations = 0
        if (!isPd) {
            // PK based model: calculate the insulin required to neutralise the
            // current delta at the model's peak time.
            val actAtPeak = activityAtPeak(1.0)
            insulinReq = actMissing / actAtPeak
        } else {
            // PD based model: iterative dose estimation (allowed rel. error +/- 2%).
            var actRatio = 1.0
            var actAtPeak = 0.000001 // Dummy value to enter the while loop.
            insulinReq = 1.0
            if (actMissing != 0.0) {
                while (round(actAtPeak / actMissing, 2) > 1.02 || round(actAtPeak / actMissing, 2) < 0.98) {
                    if (iterations >= MAX_ITERATIONS) {
                        logs.add("tsunami_insreq iteration cap ($MAX_ITERATIONS) reached, using 0")
                        insulinReq = 0.0
                        break
                    }
                    insulinReq /= actRatio
                    if (!insulinReq.isFinite()) {
                        logs.add("tsunami_insreq became non-finite, using 0")
                        insulinReq = 0.0
                        break
                    }
                    actAtPeak = activityAtPeak(insulinReq)
                    if (!actAtPeak.isFinite()) {
                        logs.add("tsunami_insreq activity became non-finite, using 0")
                        insulinReq = 0.0
                        break
                    }
                    actRatio = actAtPeak / actMissing
                    if (!actRatio.isFinite()) {
                        logs.add("tsunami_insreq activity ratio became non-finite, using 0")
                        insulinReq = 0.0
                        break
                    }
                    logs.add("tsunami_insreq ($iterations): ${round(insulinReq, 3)} | actRatio: ${round(actRatio, 3)}")
                    iterations++
                }
            } else {
                insulinReq = 0.0
            }
        }

        // The old loop counted one extra iteration.
        iterations -= 1

        // Safety guard: never let a non-finite estimate reach the SMB sizing code.
        if (!insulinReq.isFinite()) {
            logs.add("tsunami_insreq non-finite after calculation, using 0")
            insulinReq = 0.0
        }

        val bgCorrection = (bg - targetBg) / adjustedSens
        if (bgCorrection > iob && bgCorrection > insulinReq) {
            insulinReq = bgCorrection
        }
        insulinReq = round(insulinReq, 2)

        // deltaScore and bgScore
        val deltaScore = min(1.0, max(glucose.shortAvgDelta / 4, 0.0))
        insulinReqPct = round(insulinReqPct * deltaScore, 3)
        val bgScoreUpperThreshold = targetBg + 30
        val bgScoreLowerThreshold = targetBg
        val bgScore = round(min((bg - bgScoreLowerThreshold) / (bgScoreUpperThreshold - bgScoreLowerThreshold), 1.0), 3)
        smbCap = round(smbCap * bgScore, 2)

        var activityControllerActive = false
        val reason = StringBuilder()

        if (referenceTimer >= startTime &&
            referenceTimer <= endTime &&
            glucose.delta >= 4.1 &&
            bg >= targetBg &&
            iob > 0.1 &&
            actCurr > 0 &&
            insulinReq + iob >= (bg - targetBg) / usedSens
        ) {
            activityControllerActive = true

            logs.add("------------------------------")
            logs.add("WAVE STATUS")
            logs.add("------------------------------")
            logs.add("act. lag: ${activity.sensorLagActivity}")
            logs.add("act. now: $actCurr (${activity.currentActivity})")
            logs.add("act. future: ${activity.futureActivity}")
            logs.add("miss./act. future: $actMissing")
            logs.add("-------------")
            logs.add("bg: $bg")
            logs.add("delta: ${glucose.delta}")
            logs.add("pure delta: $pureDelta")
            logs.add("-------------")
            logs.add("deltaScore_live: ${round(deltaScore, 3)}")
            logs.add("bgScore_live: $bgScore")
            logs.add("insulinReqPCT_live: $insulinReqPct")
            logs.add("SMBcap_live: $smbCap")
            logs.add("tsunami_insreq: $insulinReq")
            logs.add("iterations: $iterations")
            if (bgCorrection > iob && bgCorrection > insulinReq) {
                logs.add("Mode: IOB too low, correcting for BG.")
            } else {
                logs.add("Mode: Building up activity.")
            }
            logs.add("------------------------------")

            reason.append(" ##TSUNAMI STATUS##")
            reason.append(" act. lag: ${activity.sensorLagActivity}")
            reason.append("; act. now: $actCurr (${activity.currentActivity})")
            reason.append("; act. future: ${activity.futureActivity}")
            reason.append("; miss./act. future: $actMissing")
            reason.append("; ###")
            reason.append(" bg: $bg")
            reason.append("; delta: ${glucose.delta}")
            reason.append("; pure delta: $pureDelta")
            reason.append("; ###")
            reason.append(" deltaScore_live: ${round(deltaScore, 3)}")
            reason.append("; bgScore_live: $bgScore")
            reason.append("; insulinReqPCT_live: $insulinReqPct")
            reason.append("; SMBcap_live: $smbCap")
            reason.append("; tsunami_insreq: $insulinReq")
            reason.append("; iterations: $iterations")
            reason.append("; ###")
            if (bgCorrection > iob && bgCorrection > insulinReq) {
                reason.append(" Mode: IOB too low, correcting for BG.")
            } else {
                reason.append(" Mode: Building up activity.")
            }
            reason.append(" ##TSUNAMI STATUS END##")
        } else {
            // Reporting if TAE is bypassed.
            logs.add("------------------------------")
            logs.add("WAVE STATUS")
            logs.add("------------------------------")
            logs.add("TAE bypassed - reasons:")
            if (referenceTimer < startTime || referenceTimer > endTime) {
                logs.add("Outside active hours.")
            }
            if (glucose.delta <= 4.1) {
                logs.add("Delta too low. (${glucose.delta})")
            }
            if (bg < targetBg) {
                logs.add("Glucose is below target.")
            }
            if (iob <= 0.1) {
                logs.add("IOB is below minimum of 0.1 U.")
            }
            if (actCurr <= 0) {
                logs.add("Insulin activity is negative or 0.")
            }
            if (insulinReq + iob < (bg - targetBg) / usedSens) {
                logs.add("Incompatible insulin & glucose status. Let oref1 take over for now.")
            }
            logs.add("------------------------------")
        }

        insulinReq = round(insulinReq * insulinReqPct, 2)
        return WaveResult(
            insReq = insulinReq,
            smbCap = smbCap,
            activityControllerActive = activityControllerActive,
            reason = reason,
            logs = logs,
            iterations = iterations
        )
    }

    /**
     * Same rounding as `DetermineBasalSMB.round`: ties round by `floor(x + 0.5)` semantics,
     * while guarding non-finite inputs and scaling overflow.
     */
    internal fun round(value: Double, digits: Int): Double {
        if (!value.isFinite()) return 0.0
        val scale = 10.0.pow(digits.toDouble())
        val scaled = value * scale
        if (!scaled.isFinite()) return 0.0
        return floor(scaled + 0.5) / scale
    }
}
