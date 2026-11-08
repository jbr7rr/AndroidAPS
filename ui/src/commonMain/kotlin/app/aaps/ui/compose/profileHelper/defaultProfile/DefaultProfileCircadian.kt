package app.aaps.ui.compose.profileHelper.defaultProfile

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.data.Block
import app.aaps.core.data.model.data.TargetBlock
import app.aaps.core.data.time.T
import app.aaps.core.data.time.systemUtcOffsetAt
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profile.PureProfile
import app.aaps.core.interfaces.utils.DateUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs

@Suppress("LocalVariableName")
@SingleIn(AppScope::class)
@Inject
class DefaultProfileCircadian(
    private val dateUtil: DateUtil,
    private val profileUtil: ProfileUtil
) {

    // Factors (Gary Scheiner, Think like a pancreas, Da Capo Lifelong Books ISBN 978-0738215143)
    private val oneToTen = doubleArrayOf(1.12, 1.12, 1.11, 1.11, 1.11, 1.09, 1.09, 1.12, 1.11, 0.98, 0.90, 0.84, 0.81, 0.81, 0.86, 0.86, 0.88, 0.90, 0.93, 0.97, 1.05, 1.07, 1.09, 1.07)
    private val elevenToTwenty = doubleArrayOf(1.09, 1.11, 1.09, 1.09, 1.09, 1.09, 1.11, 1.11, 1.09, 1.04, 0.96, 0.88, 0.84, 0.84, 0.85, 0.86, 0.91, 0.92, 0.92, 0.94, 0.99, 1.04, 1.05, 1.06)
    private val twentyToSixty = doubleArrayOf(1.01, 1.04, 1.12, 1.15, 1.20, 1.26, 1.21, 1.20, 1.15, 1.06, 0.98, 0.88, 0.86, 0.85, 0.85, 0.83, 0.83, 0.86, 0.89, 0.89, 0.94, 0.97, 0.98, 0.98)
    private val sixtyOneAndHigher = doubleArrayOf(0.95, 1.02, 1.07, 1.14, 1.19, 1.38, 1.43, 1.36, 1.24, 1.24, 1.14, 1.00, 0.95, 0.93, 0.90, 0.79, 0.79, 0.79, 0.76, 0.74, 0.74, 0.79, 0.79, 0.88)

    /**
     * Builds a 24-hour profile from the age band's fixed circadian factors.
     *
     * [isf] is entered in [units], like every other profile field. [ic] is g/U. The target is
     * always 108 mg/dL converted to [units]. TDD, basal percentage, ISF and IC must be positive:
     * the helper screen leaves its fields blank instead of supplying defaults, so a zero here is
     * an unfinished form and must not produce a profile that could be activated.
     *
     * Basal uses the time shift as entered; ISF and IC use the old `timeshift + 1` rule. The
     * profile is built directly rather than through a JSON document, so the values stay exactly
     * what the factors produce.
     */
    fun profile(
        age: Int,
        tdd: Double,
        basalSumPct: Double,
        isf: Double,
        ic: Double,
        timeshift: Double,
        units: GlucoseUnit
    ): PureProfile? {
        if (age < 1) return null
        if (!tdd.isFinite() || tdd <= 0.0) return null
        if (!basalSumPct.isFinite() || basalSumPct <= 0.0) return null
        if (!isf.isFinite() || isf <= 0.0) return null
        if (!ic.isFinite() || ic <= 0.0) return null
        if (!timeshift.isFinite()) return null

        val factors = when (age) {
            in 1..10   -> oneToTen
            in 11..20  -> elevenToTwenty
            in 21..60  -> twentyToSixty
            else       -> sixtyOneAndHigher
        }
        val averageBasal = tdd * basalSumPct / 24.0
        val basalBlocks = hourlyBlocks(factors, timeshift) { factor -> averageBasal * factor }
        val isfBlocks = hourlyBlocks(factors, timeshift + 1) { factor -> isf / factor }
        val icBlocks = hourlyBlocks(factors, timeshift + 1) { factor -> ic / factor }
        val target = profileUtil.fromMgdlToUnits(108.0, units)
        return PureProfile(
            basalBlocks = basalBlocks,
            isfBlocks = isfBlocks,
            icBlocks = icBlocks,
            targetBlocks = listOf(TargetBlock(T.hours(24).msecs(), target, target)),
            glucoseUnit = units,
            utcOffset = systemUtcOffsetAt(dateUtil.now())
        )
    }

    private fun hourlyBlocks(factors: DoubleArray, shift: Double, valueAtFactor: (Double) -> Double): List<Block> =
        (0..23).map { hour ->
            // Exactly the old `(i + abs(timeshift.toInt() - 24)) % 24` rule.
            val index = (hour + abs(shift.toInt() - 24)) % 24
            Block(T.hours(1).msecs(), valueAtFactor(factors[index]))
        }
}
