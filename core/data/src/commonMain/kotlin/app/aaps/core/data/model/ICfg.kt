package app.aaps.core.data.model

import app.aaps.core.data.iob.Iob
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Insulin configuration holds info about insulin
 */
data class ICfg(
    /**
     * Insulin name
     */
    var insulinLabel: String,
    /**
     * Aka DIA before in milliseconds
     */
    var insulinEndTime: Long,
    /**
     * Peak time from start in milliseconds
     */
    var insulinPeakTime: Long,
    /**
     * Insulin concentration (0.2 for U20, 2.0 for U200 insulin)
     */
    var concentration: Double = 1.0,
    /**
     * Insulin action model. [InsulinModel.Bilinear] is the default and keeps
     * every existing insulin unchanged. PD insulins set [InsulinModel.Pd].
     */
    var insulinModel: InsulinModel = InsulinModel.Bilinear
) {

    constructor(
        insulinLabel: String,
        peak: Int,
        dia: Double,
        concentration: Double,
        insulinModel: InsulinModel = InsulinModel.Bilinear
    )
        : this(
            insulinLabel = insulinLabel,
            insulinEndTime = (dia * 3600 * 1000).toLong(),
            insulinPeakTime = (peak * 60000).toLong(),
            concentration = concentration,
            insulinModel = insulinModel
        )

    /**
     * Used in InsulinPlugin (insulin editor)
     */
    fun isEqual(iCfg: ICfg?): Boolean {
        iCfg?.let { iCfg ->
            if (insulinLabel != iCfg.insulinLabel)
                return false
            if (insulinEndTime != iCfg.insulinEndTime)
                return false
            if (insulinPeakTime != iCfg.insulinPeakTime)
                return false
            if (concentration != iCfg.concentration)
                return false
            if (insulinModel != iCfg.insulinModel)
                return false
            return true
        }
        return false
    }

    /**
     * Same insulin action (DIA, peak, concentration, model), whatever the name. Use this to ask "is this
     * insulin in the list", because a name can change while the insulin stays the same: the list rebuilds
     * every label at start, and a profile switch keeps the label it was made with.
     */
    fun hasSameCurve(iCfg: ICfg?): Boolean =
        iCfg != null &&
            insulinEndTime == iCfg.insulinEndTime &&
            insulinPeakTime == iCfg.insulinPeakTime &&
            concentration == iCfg.concentration &&
            insulinModel == iCfg.insulinModel

    /**
     * DIA (insulinEndTime) in hours rounded to 1 decimal place
     */
    val dia: Double
        get() = (insulinEndTime / 3600.0 / 100.0).roundToInt() / 10.0

    /**
     * Peak time in minutes
     */
    val peak: Int
        get() = (insulinPeakTime / 60000).toInt()

    /**
     * False for the `insulinEndTime = -1` sentinel the DB v33 migration writes into rows that predate ICfg
     * (and for anything else non-positive). Such a record is not an insulin: [dia] rounds to `0.0`, which is
     * outside the hard limits, so an APS run that accepts it aborts every cycle instead of dosing.
     *
     * Also false for an invalid insulin model: non-finite or non-positive PD parameters, a non-positive
     * duration, a bad concentration, or a peak that cannot produce a real curve. Such a model must never be
     * dosed with the bilinear curve instead.
     *
     * Treat it as "no insulin" — the same as absent — so callers fall through to their normal
     * ask-or-refuse path rather than propagating a degenerate curve.
     */
    val isUsable: Boolean
        get() = insulinEndTime > 0 && insulinPeakTime > 0 && when (val model = insulinModel) {
            InsulinModel.Bilinear -> true
            is InsulinModel.Pd -> model.isUsable &&
                concentration.isFinite() && concentration > 0.0 &&
                model.peakMinutesForDose(1.0, concentration).let { it.isFinite() && it > 0.0 }
        }

    /**
     * Set insulinEndTime aka DIA
     * @param hours duration in hours
     */
    fun setDia(hours: Double) {
        insulinEndTime = (hours * 3600 * 1000).toLong()
    }

    /**
     * Set insulinPeakTime aka peak
     * @param minutes peak tme in minutes
     */
    fun setPeak(minutes: Int) {
        insulinPeakTime = (minutes * 60000).toLong()
    }

    /**
     * insulinNickname is only used in insulin editor
     */
    var insulinNickname: String = ""

    /**
     * deepClone is only used in insulin editor
     */
    fun deepClone(): ICfg = ICfg(insulinLabel, insulinEndTime, insulinPeakTime, concentration, insulinModel)
        .also { it.insulinNickname = insulinNickname }

    /**
     * IOB and activity contribution of [bolus] at [time], using this ICfg's [insulinModel].
     */
    fun iobCalcForTreatment(bolus: BS, time: Long): Iob = when (val model = insulinModel) {
        InsulinModel.Bilinear -> bilinearIobCalcForTreatment(bolus, time)
        is InsulinModel.Pd -> pdIobCalcForTreatment(bolus, time, model)
    }

    /**
     * The classic AAPS bilinear curve. Kept byte-for-byte identical to the pre-PD code, so existing
     * insulins produce the same numbers.
     */
    private fun bilinearIobCalcForTreatment(bolus: BS, time: Long): Iob {
        devAssert(insulinEndTime != 0L)
        devAssert(insulinPeakTime != 0L)
        val result = Iob()
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            // Defensive clamp: the bilinear IOB model is only well-defined for 0 < tp < td/2. Corrupt
            // iCfg (the v33 migration sentinel -1 before its repair, or a malformed NS-imported insulin
            // config) would otherwise divide by zero, produce a negative tau, or — when td <= 0 — make
            // the `t < td` gate never fire and silently contribute ZERO IOB, all of which mislead the
            // loop into overdelivery. These bounds are MATH-validity floors only, NOT the medical limits
            // (which are enforced upstream): legitimate peaks below HardLimits.LIMIT_PEAK (e.g. 30 min) and
            // any dia >= MIN_DIA are preserved unchanged; only degenerate values are sanitized.
            val td = (dia * 60).coerceAtLeast(MIN_DIA_MINUTES)
            val tp = peak.toDouble().coerceIn(MIN_PEAK_MINUTES, td / 2.0 - 1.0)
            // force the IOB to 0 if over DIA hours have passed
            if (t < td) {
                val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
                val a = 2 * tau / td
                val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
                result.activityContrib = bolus.amount * (s / tau.pow(2.0)) * t * (1 - t / td) * exp(-t / tau)
                result.iobContrib = bolus.amount * (1 - s * (1 - a) * ((t.pow(2.0) / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1))
            }
        }
        return result
    }

    /**
     * The PD curve uses [InsulinModel.Pd.durationMinutes], not [insulinEndTime]. The 3.4 plugins always
     * used 8 h, so an old profile with a shorter DIA keeps the same curve. An unusable model returns zero
     * instead of NaN; [isUsable] also refuses such an insulin upstream.
     */
    private fun pdIobCalcForTreatment(bolus: BS, time: Long, model: InsulinModel.Pd): Iob {
        if (bolus.amount == 0.0 || !isUsable) return Iob()
        val t = (time - bolus.timestamp) / 1000.0 / 60.0
        return Iob(
            iobContrib = model.iobContrib(bolus.amount, t, concentration),
            activityContrib = model.activityContrib(bolus.amount, t, concentration)
        )
    }

    /**
     * Activity at the insulin's peak time. Wave uses this as the amplitude of a dose.
     *
     * Bilinear: the existing curve evaluated at [peak], the same value the old
     * `iobCalcPeakForTreatment` returned. PD: the model's dose-dependent peak.
     */
    fun activityAtPeak(amount: Double): Double = when (val model = insulinModel) {
        InsulinModel.Bilinear -> bilinearActivityAtPeak(amount)
        is InsulinModel.Pd -> if (isUsable) model.activityAtPeak(amount, concentration) else 0.0
    }

    private fun bilinearActivityAtPeak(amount: Double): Double {
        if (amount == 0.0) return 0.0
        val td = (dia * 60).coerceAtLeast(MIN_DIA_MINUTES)
        val tp = peak.toDouble().coerceIn(MIN_PEAK_MINUTES, td / 2.0 - 1.0)
        // The old `iobCalcPeakForTreatment` evaluated the curve at the configured peak, not at the
        // defensive clamp. Keep that: only the curve parameters use the clamp.
        val t = peak.toDouble()
        if (t >= td) return 0.0
        val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
        val a = 2 * tau / td
        val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
        val activity = amount * (s / tau.pow(2.0)) * t * (1 - t / td) * exp(-t / tau)
        return if (activity.isFinite()) activity else 0.0
    }

    /**
     * Time to peak in minutes for [amount], for the UI and the insulin graph only.
     *
     * Wave deliberately uses [peak] (the fixed profile value), not this
     * dose-dependent value, to keep the 3.4 future window.
     */
    fun peakMinutesForDose(amount: Double): Double = when (val model = insulinModel) {
        InsulinModel.Bilinear -> peak.toDouble()
        is InsulinModel.Pd -> model.peakMinutesForDose(amount, concentration)
    }

    companion object {

        // Math-validity floors for iobCalcForTreatment. They only engage for corrupt/degenerate iCfg
        // and are no-ops for real configs; they are NOT the medical HardLimits.
        private const val MIN_DIA_MINUTES = 300.0 // 5 h (mirrors HardLimits.MIN_DIA); floors corrupt/sentinel DIA <= 0
        private const val MIN_PEAK_MINUTES = 1.0  // just keeps tp > 0; real peaks (incl. sub-MIN_PEAK like 30 min) pass through
    }
}

/**
 * Index of the list entry for [iCfg], or -1. An entry with the same label and curve wins. If there is none,
 * the first entry with the same curve is taken, so an insulin still matches after its label has changed
 * (see [ICfg.hasSameCurve]).
 */
fun List<ICfg>.indexOfInsulin(iCfg: ICfg?): Int =
    indexOfFirst { it.isEqual(iCfg) }.takeIf { it >= 0 } ?: indexOfFirst { it.hasSameCurve(iCfg) }

/**
 * Label of the list entry for [iCfg] (see [indexOfInsulin]), or the label of [iCfg] itself when no entry has
 * the same curve. Use it to find or mark the running insulin in the list. The running insulin keeps the label
 * it was made with, which the list may no longer have (an older name, or an insulin renamed since).
 */
fun List<ICfg>.labelOfInsulin(iCfg: ICfg?): String? =
    getOrNull(indexOfInsulin(iCfg))?.insulinLabel ?: iCfg?.insulinLabel