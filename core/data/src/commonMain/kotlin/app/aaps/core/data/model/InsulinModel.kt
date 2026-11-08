package app.aaps.core.data.model

import kotlin.math.exp
import kotlin.math.pow

/**
 * Insulin action model of an [ICfg].
 *
 * [Bilinear] is the classic AAPS curve and the default for every insulin that
 * existed before the Wavez port. [Pd] is the Wavez pharmacodynamic curve.
 * The model is stored with the insulin configuration, so a bolus keeps the
 * curve it was given with even after the active insulin changes.
 */
sealed interface InsulinModel {

    /**
     * False when the parameters cannot produce a real curve. Such a model must
     * not be used for dosing: [ICfg.isUsable] rejects it and the curve methods
     * return zero instead of NaN.
     */
    val isUsable: Boolean

    data object Bilinear : InsulinModel {
        override val isUsable: Boolean
            get() = true
    }

    /**
     * The "MP" pharmacodynamic model used by the Wavez PD insulins.
     *
     * For a dose `amount` and an insulin concentration `c`:
     *
     * ```
     * peakScale = if (concentrationScalesPeak) c else 1.0
     * tp        = (a0 + a1 * amount * peakScale) / (1 + b1 * amount)
     * tpModel   = 2 * tp^2
     * activity  = (2 * amount / tpModel) * t * exp(-t^2 / tpModel)
     * iob       = amount * (exp(-t^2 / tpModel) - exp(-duration^2 / tpModel))
     * ```
     *
     * @param a0 base time to peak, in minutes
     * @param a1 dose-dependent part of the time to peak
     * @param b1 damping of the dose-dependent time to peak
     * @param concentrationScalesPeak true for Lyumjev PD, where [ICfg.concentration]
     *   scales the dose in the peak numerator (1.0 for U100, 2.0 for U200).
     *   Custom PD keeps this false, so its peak does not depend on concentration.
     * @param durationMinutes fixed curve duration. The 3.4 PD plugins always used
     *   480 min (8 h), independent of the profile DIA. 4.0 keeps that. The PD
     *   presets use DIA 8 h so the IOB query window covers the whole curve.
     */
    data class Pd(
        val a0: Double,
        val a1: Double,
        val b1: Double,
        val concentrationScalesPeak: Boolean = false,
        val durationMinutes: Int = 480,
    ) : InsulinModel {

        override val isUsable: Boolean
            get() = a0.isFinite() && a0 > 0.0 &&
                a1.isFinite() && a1 > 0.0 &&
                b1.isFinite() && b1 > 0.0 &&
                durationMinutes > 0

        /**
         * Dose-dependent time to peak activity, in minutes.
         *
         * [concentration] is 1.0 for U100 and 2.0 for U200. It only enters the
         * formula when [concentrationScalesPeak] is true.
         */
        fun peakMinutesForDose(amount: Double, concentration: Double = 1.0): Double {
            val peakScale = if (concentrationScalesPeak) concentration else 1.0
            return (a0 + a1 * amount * peakScale) / (1 + b1 * amount)
        }

        /** Activity contribution for [amount] units, [timeMinutes] after the dose. */
        fun activityContrib(amount: Double, timeMinutes: Double, concentration: Double = 1.0): Double {
            if (amount == 0.0 || timeMinutes < 0.0 || timeMinutes >= durationMinutes) return 0.0
            val tpModel = tpModelForDose(amount, concentration) ?: return 0.0
            val activity = (2 * amount / tpModel) * timeMinutes * exp(-timeMinutes.pow(2.0) / tpModel)
            return if (activity.isFinite()) activity else 0.0
        }

        /** Remaining IOB contribution for [amount] units, [timeMinutes] after the dose. */
        fun iobContrib(amount: Double, timeMinutes: Double, concentration: Double = 1.0): Double {
            if (amount == 0.0 || timeMinutes < 0.0 || timeMinutes >= durationMinutes) return 0.0
            val tpModel = tpModelForDose(amount, concentration) ?: return 0.0
            val duration = durationMinutes.toDouble()
            val iob = amount * (exp(-timeMinutes.pow(2.0) / tpModel) - exp(-duration.pow(2.0) / tpModel))
            return if (iob.isFinite()) iob else 0.0
        }

        /** Activity contribution at the dose-dependent peak time. */
        fun activityAtPeak(amount: Double, concentration: Double = 1.0): Double =
            activityContrib(amount, peakMinutesForDose(amount, concentration), concentration)

        private fun tpModelForDose(amount: Double, concentration: Double): Double? {
            if (!isUsable || !concentration.isFinite() || concentration <= 0.0) return null
            val tp = peakMinutesForDose(amount, concentration)
            if (!tp.isFinite() || tp == 0.0) return null
            val tpModel = 2 * tp.pow(2.0)
            return tpModel.takeIf { it.isFinite() && it > 0.0 }
        }
    }
}
