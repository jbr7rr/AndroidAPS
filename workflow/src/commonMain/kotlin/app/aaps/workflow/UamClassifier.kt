package app.aaps.workflow

import androidx.collection.LongSparseArray
import app.aaps.core.interfaces.aps.AutosensData

/**
 * UAM (unannounced meal) classification for the oref1 autosens pass.
 *
 * Ported from the 3.4 fork's `IobCobOref1Worker` so the behaviour can be tested without a
 * calculation chain. The old code:
 *
 * - detected UAM when `iob > factor * currentBasal`, when the previous bucket was already UAM, or
 *   during the first nine buckets after a meal;
 * - on the first UAM bucket marked the previous four five-minute buckets (20 minutes) whose
 *   deviation was positive;
 * - logged every bucket it looked at with the AUTOSENS tag.
 *
 * Two deliberate changes from the old code:
 *
 * 1. **Bounds check.** The old `while (past < 4 && initialIndex != null)` indexed
 *    `initialIndex - past` without a lower bound, so a UAM at the start of the table read index -1
 *    and threw. Marking now stops at index 0.
 * 2. **Invalid factor fallback.** A factor that is zero, negative or not finite would make the
 *    threshold meaningless (or invert it), so it falls back to [DEFAULT_FACTOR].
 */
internal object UamClassifier {

    const val DEFAULT_FACTOR = 2.0

    /** Number of previous five-minute buckets marked when a UAM starts. */
    const val PREVIOUS_BUCKETS = 4

    /** Buckets that always remain UAM after a meal, as in the old code. */
    const val MEAL_START_BUCKETS = 9

    /** Only a finite, positive factor means anything; anything else uses the default. */
    fun factor(configured: Double): Double =
        if (configured.isFinite() && configured > 0.0) configured else DEFAULT_FACTOR

    /**
     * Applies the old UAM/meal classification to [current].
     *
     * @param log receives the old debug line for every previous bucket considered. Production wires
     *   this to [app.aaps.core.interfaces.logging.LTag.AUTOSENS].
     * @return true when [current] is classified as UAM
     */
    fun classify(
        current: AutosensData,
        previous: AutosensData?,
        table: LongSparseArray<AutosensData>,
        iob: Double,
        currentBasal: Double,
        uamFactor: Double,
        log: (String) -> Unit = {}
    ): Boolean {
        val factor = factor(uamFactor)
        val isUam = iob > factor * currentBasal || current.uam || current.mealStartCounter < MEAL_START_BUCKETS
        if (!isUam) {
            current.type = "non-meal"
            return false
        }

        if (previous != null && (previous.type == "csf" || previous.type == "uam")) {
            current.mealStartCounter = previous.mealStartCounter
        } else {
            current.mealStartCounter = 0
            markPreviousBuckets(previous, table, log)
        }
        current.mealStartCounter++
        current.uam = current.deviation > 0
        current.type = "uam"
        return true
    }

    private fun markPreviousBuckets(
        previous: AutosensData?,
        table: LongSparseArray<AutosensData>,
        log: (String) -> Unit
    ) {
        val initialIndex = previous?.let { table.indexOfKey(it.time) } ?: return
        if (initialIndex < 0) return
        for (past in 0 until PREVIOUS_BUCKETS) {
            val index = initialIndex - past
            if (index < 0 || index >= table.size()) break
            val bucket = table.valueAt(index)
            log("Setting past uam: $past, ad: $bucket")
            if (bucket.deviation > 0) {
                bucket.uam = true
                bucket.type = "uam"
                bucket.pastSensitivity += "u"
                bucket.validDeviation = false
            }
        }
    }
}
