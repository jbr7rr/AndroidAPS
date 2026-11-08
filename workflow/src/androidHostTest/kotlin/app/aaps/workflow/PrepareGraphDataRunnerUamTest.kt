package app.aaps.workflow

import androidx.collection.LongSparseArray
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensResult
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The UAM pass ported from 3.4 (`IobCobOref1Worker`).
 *
 * The old loop marked the previous 20 minutes when a UAM started, but indexed the table with
 * `initialIndex - past` without checking the lower bound, so a UAM at the start of the table threw.
 * These tests pin the old behaviour and the bounds fix.
 */
class PrepareGraphDataRunnerUamTest {

    // ---- factor ----

    @Test
    fun `a missing or nonsensical factor falls back to 2_0`() {
        assertThat(UamClassifier.factor(0.0)).isEqualTo(2.0)
        assertThat(UamClassifier.factor(-1.0)).isEqualTo(2.0)
        assertThat(UamClassifier.factor(Double.NaN)).isEqualTo(2.0)
        assertThat(UamClassifier.factor(Double.POSITIVE_INFINITY)).isEqualTo(2.0)
        assertThat(UamClassifier.factor(1.7)).isEqualTo(1.7)
    }

    @Test
    fun `the threshold is iob greater than factor times current basal`() {
        // 2 U of IOB, 1 U/h basal: 1x sees it, 2x is exactly equal and must not, 3x cannot.
        assertThat(classifyUam(iob = 2.0, basal = 1.0, factor = 1.0)).isTrue()
        assertThat(classifyUam(iob = 2.0, basal = 1.0, factor = 2.0)).isFalse()
        assertThat(classifyUam(iob = 2.0, basal = 1.0, factor = 3.0)).isFalse()

        assertThat(classifyUam(iob = 2.1, basal = 1.0, factor = 2.0)).isTrue()
        assertThat(classifyUam(iob = 1.9, basal = 1.0, factor = 2.0)).isFalse()
    }

    // ---- first UAM start ----

    @Test
    fun `the previous twenty minutes are marked when a UAM starts`() {
        val buckets = buckets(deviation = doubleArrayOf(10.0, -5.0, 0.0, 3.0, 1.0, 0.0, 2.0, 4.0))
        val table = tableOf(buckets)
        val previous = buckets.last()
        val current = bucket(time = 40L * 60_000, deviation = 5.0)

        UamClassifier.classify(
            current = current,
            previous = previous,
            table = table,
            iob = 3.0,
            currentBasal = 1.0,
            uamFactor = 2.0
        )

        // Indices 4..7 are the previous 20 minutes (five-minute buckets, previous included).
        assertThat(buckets[7].marked()).isTrue()
        assertThat(buckets[6].marked()).isTrue()
        assertThat(buckets[5].marked()).isFalse()  // deviation == 0
        assertThat(buckets[4].marked()).isTrue()
        assertThat(buckets[3].marked()).isFalse()  // outside the window and positive
        assertThat(buckets[1].marked()).isFalse()  // outside the window and negative

        assertThat(buckets[4].uam).isTrue()
        assertThat(buckets[4].type).isEqualTo("uam")
        assertThat(buckets[4].pastSensitivity).isEqualTo("+u")
        assertThat(buckets[4].validDeviation).isFalse()

        assertThat(current.type).isEqualTo("uam")
        assertThat(current.uam).isTrue()
        assertThat(current.mealStartCounter).isEqualTo(1)
    }

    @Test
    fun `a bucket with non-positive deviation is not marked`() {
        val buckets = buckets(deviation = doubleArrayOf(0.0, -1.0, 0.0, 0.0))
        val table = tableOf(buckets)

        UamClassifier.classify(
            current = bucket(time = 20L * 60_000, deviation = 2.0),
            previous = buckets.last(),
            table = table,
            iob = 3.0,
            currentBasal = 1.0,
            uamFactor = 2.0
        )

        for (bucket in buckets) {
            assertThat(bucket.uam).isFalse()
            assertThat(bucket.type).isEmpty()
            assertThat(bucket.pastSensitivity).isEqualTo("+")
            assertThat(bucket.validDeviation).isTrue()
        }
    }

    // ---- continuing an existing meal ----

    @Test
    fun `a csf or uam neighbour keeps the meal counter and does not re-mark`() {
        for (previousType in listOf("csf", "uam")) {
            val buckets = buckets(deviation = doubleArrayOf(5.0, 5.0, 5.0, 5.0))
            val table = tableOf(buckets)
            val previous = buckets.last().apply {
                type = previousType
                mealStartCounter = 42
            }
            val current = bucket(time = 20L * 60_000, deviation = 2.0)

            UamClassifier.classify(
                current = current,
                previous = previous,
                table = table,
                iob = 3.0,
                currentBasal = 1.0,
                uamFactor = 2.0
            )

            for (bucket in buckets) {
                assertThat(bucket.uam).isFalse()
                assertThat(bucket.pastSensitivity).isEqualTo("+")
            }
            assertThat(current.mealStartCounter).isEqualTo(43)
        }
    }

    // ---- bounds ----

    @Test
    fun `marking stops at the start of the table instead of indexing below zero`() {
        val buckets = buckets(deviation = doubleArrayOf(4.0, 6.0))
        val table = tableOf(buckets)

        // No exception is the main assertion; the reachable buckets must still be marked.
        UamClassifier.classify(
            current = bucket(time = 10L * 60_000, deviation = 2.0),
            previous = buckets[1],
            table = table,
            iob = 3.0,
            currentBasal = 1.0,
            uamFactor = 2.0
        )

        assertThat(buckets[1].marked()).isTrue()
        assertThat(buckets[0].marked()).isTrue()
    }

    @Test
    fun `a previous bucket that is not in the table is ignored`() {
        val buckets = buckets(deviation = doubleArrayOf(4.0, 6.0))
        val table = tableOf(buckets)

        UamClassifier.classify(
            current = bucket(time = 100L * 60_000, deviation = 2.0),
            previous = bucket(time = 99L * 60_000, deviation = 7.0),
            table = table,
            iob = 3.0,
            currentBasal = 1.0,
            uamFactor = 2.0
        )

        for (bucket in buckets) assertThat(bucket.uam).isFalse()
    }

    @Test
    fun `no previous bucket is safe`() {
        val buckets = buckets(deviation = doubleArrayOf(4.0, 6.0))
        val table = tableOf(buckets)

        UamClassifier.classify(
            current = bucket(time = 100L * 60_000, deviation = 2.0),
            previous = null,
            table = table,
            iob = 3.0,
            currentBasal = 1.0,
            uamFactor = 2.0
        )

        for (bucket in buckets) assertThat(bucket.uam).isFalse()
    }

    // ---- the non-UAM branch and the meal-counter gate ----

    @Test
    fun `no iob and an old meal counter means non-meal`() {
        val current = bucket(time = 100L * 60_000, deviation = 2.0).apply { mealStartCounter = 9 }

        val isUam = UamClassifier.classify(
            current = current,
            previous = null,
            table = tableOf(buckets(deviation = doubleArrayOf())),
            iob = 0.0,
            currentBasal = 1.0,
            uamFactor = 2.0
        )

        assertThat(isUam).isFalse()
        assertThat(current.type).isEqualTo("non-meal")
        assertThat(current.uam).isFalse()
        assertThat(current.mealStartCounter).isEqualTo(9)
    }

    @Test
    fun `the first nine buckets after a meal stay UAM`() {
        val current = bucket(time = 100L * 60_000, deviation = 2.0).apply { mealStartCounter = 8 }
        val previous = bucket(time = 95L * 60_000, deviation = 1.0).apply {
            type = "uam"
            mealStartCounter = 8
        }

        val isUam = UamClassifier.classify(
            current = current,
            previous = previous,
            table = tableOf(buckets(deviation = doubleArrayOf())),
            iob = 0.0,
            currentBasal = 1.0,
            uamFactor = 2.0
        )

        assertThat(isUam).isTrue()
        assertThat(current.type).isEqualTo("uam")
        assertThat(current.mealStartCounter).isEqualTo(9)
    }

    private fun classifyUam(iob: Double, basal: Double, factor: Double): Boolean {
        val current = bucket(time = 0L, deviation = 1.0).apply { mealStartCounter = 999 }
        return UamClassifier.classify(
            current = current,
            previous = null,
            table = tableOf(buckets(deviation = doubleArrayOf())),
            iob = iob,
            currentBasal = basal,
            uamFactor = factor
        )
    }

    private fun buckets(deviation: DoubleArray): List<FakeAutosensData> =
        deviation.mapIndexed { index, value -> bucket(time = index * 5L * 60_000, deviation = value) }

    private fun tableOf(buckets: List<FakeAutosensData>): LongSparseArray<AutosensData> =
        LongSparseArray<AutosensData>().apply {
            buckets.forEach { bucket -> put(bucket.time, bucket) }
        }

    private fun bucket(time: Long, deviation: Double) = FakeAutosensData(time).apply {
        this.deviation = deviation
        validDeviation = true
        pastSensitivity = "+"
    }

    private fun FakeAutosensData.marked() = uam && type == "uam" && pastSensitivity.endsWith("u")

    private class FakeAutosensData(override var time: Long) : AutosensData {

        override var bg = 0.0
        override var sens = 0.0
        override var pastSensitivity = ""
        override var deviation = 0.0
        override var validDeviation = false
        override var activeCarbsList: MutableList<AutosensData.CarbsInPast> = ArrayList()
        override var this5MinAbsorption = 0.0
        override var carbsFromBolus = 0.0
        override var cob = 0.0
        override var bgi = 0.0
        override var delta = 0.0
        override var avgDelta = 0.0
        override var slopeFromMaxDeviation = 0.0
        override var slopeFromMinDeviation = 0.0
        override var usedMinCarbsImpact = 0.0
        override var failOverToMinAbsorptionRate = false
        override var avgDeviation = 0.0
        override var absorbing = false
        override var mealCarbs = 0.0
        override var mealStartCounter = 999
        override var type = ""
        override var uam = false
        override var extraDeviation: MutableList<Double> = ArrayList()
        override var autosensResult = AutosensResult()

        override fun cloneCarbsList(): MutableList<AutosensData.CarbsInPast> = ArrayList(activeCarbsList)
        override fun deductAbsorbedCarbs() = Unit
        override fun removeOldCarbs(toTime: Long, isAAPSOrWeighted: Boolean) = Unit
    }
}
