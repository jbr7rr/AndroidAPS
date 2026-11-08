package app.aaps.ui.compose.profileHelper

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.time.T
import app.aaps.core.data.time.systemUtcOffsetAt
import app.aaps.core.interfaces.profile.PureProfile
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.shared.tests.TestBaseWithProfile
import app.aaps.ui.compose.profileHelper.defaultProfile.DefaultProfileCircadian
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Pins the Circadian generator against the tables the old 3.4 helper used.
 *
 * The factor tables follow Gary Scheiner (Think like a pancreas). Basal uses the time shift as
 * entered, ISF and IC use `timeshift + 1`, exactly like the old `arrayToJson`/`arrayToJsonDiv`.
 * The values are kept unrounded, so the generated blocks can be compared directly with the old
 * JSON-derived profile.
 */
class DefaultProfileCircadianTest : TestBaseWithProfile() {

    private val oneToTen = doubleArrayOf(
        1.12, 1.12, 1.11, 1.11, 1.11, 1.09, 1.09, 1.12, 1.11, 0.98, 0.90, 0.84,
        0.81, 0.81, 0.86, 0.86, 0.88, 0.90, 0.93, 0.97, 1.05, 1.07, 1.09, 1.07
    )
    private val elevenToTwenty = doubleArrayOf(
        1.09, 1.11, 1.09, 1.09, 1.09, 1.09, 1.11, 1.11, 1.09, 1.04, 0.96, 0.88,
        0.84, 0.84, 0.85, 0.86, 0.91, 0.92, 0.92, 0.94, 0.99, 1.04, 1.05, 1.06
    )
    private val twentyToSixty = doubleArrayOf(
        1.01, 1.04, 1.12, 1.15, 1.20, 1.26, 1.21, 1.20, 1.15, 1.06, 0.98, 0.88,
        0.86, 0.85, 0.85, 0.83, 0.83, 0.86, 0.89, 0.89, 0.94, 0.97, 0.98, 0.98
    )
    private val sixtyOneAndHigher = doubleArrayOf(
        0.95, 1.02, 1.07, 1.14, 1.19, 1.38, 1.43, 1.36, 1.24, 1.24, 1.14, 1.00,
        0.95, 0.93, 0.90, 0.79, 0.79, 0.79, 0.76, 0.74, 0.74, 0.79, 0.79, 0.88
    )

    private fun profile(
        age: Int = 5,
        tdd: Double = 48.0,
        basalSumPct: Double = 0.5,
        isf: Double = 50.0,
        ic: Double = 10.0,
        timeshift: Double = 0.0,
        units: GlucoseUnit = GlucoseUnit.MGDL
    ): PureProfile? =
        DefaultProfileCircadian(dateUtil, profileUtil)
            .profile(age, tdd, basalSumPct, isf, ic, timeshift, units)

    private fun factorAt(factors: DoubleArray, hour: Int, shift: Double): Double =
        factors[(hour + abs(shift.toInt() - 24)) % 24]

    private fun requireProfile(profile: PureProfile?): PureProfile {
        assertThat(profile).isNotNull()
        return profile!!
    }

    @Test
    fun `age bands cover one to ten eleven to twenty twenty one to sixty and above sixty`() {
        assertThat(requireProfile(profile(age = 1)).basalBlocks[0].amount).isWithin(1e-9).of(1.12)
        assertThat(requireProfile(profile(age = 10)).basalBlocks[0].amount).isWithin(1e-9).of(1.12)
        assertThat(requireProfile(profile(age = 11)).basalBlocks[0].amount).isWithin(1e-9).of(1.09)
        assertThat(requireProfile(profile(age = 20)).basalBlocks[0].amount).isWithin(1e-9).of(1.09)
        assertThat(requireProfile(profile(age = 21)).basalBlocks[0].amount).isWithin(1e-9).of(1.01)
        assertThat(requireProfile(profile(age = 60)).basalBlocks[0].amount).isWithin(1e-9).of(1.01)
        assertThat(requireProfile(profile(age = 61)).basalBlocks[0].amount).isWithin(1e-9).of(0.95)
        assertThat(requireProfile(profile(age = 100)).basalBlocks[0].amount).isWithin(1e-9).of(0.95)
    }

    @Test
    fun `ages outside one to one hundred have no profile`() {
        assertThat(profile(age = 0)).isNull()
        assertThat(profile(age = -3)).isNull()
    }

    @Test
    fun `all three schedules have twenty four one hour blocks`() {
        val p = requireProfile(profile())
        assertThat(p.basalBlocks).hasSize(24)
        assertThat(p.isfBlocks).hasSize(24)
        assertThat(p.icBlocks).hasSize(24)
        p.basalBlocks.forEach { block -> assertThat(block.duration).isEqualTo(T.hours(1).msecs()) }
        p.isfBlocks.forEach { block -> assertThat(block.duration).isEqualTo(T.hours(1).msecs()) }
        p.icBlocks.forEach { block -> assertThat(block.duration).isEqualTo(T.hours(1).msecs()) }
        assertThat(p.targetBlocks).hasSize(1)
        assertThat(p.targetBlocks[0].duration).isEqualTo(T.hours(24).msecs())
    }

    @Test
    fun `basal is tdd times basal percentage divided by twenty four and scaled per hour`() {
        val p = requireProfile(profile(age = 5, tdd = 42.0, basalSumPct = 0.4))
        val average = 42.0 * 0.4 / 24.0
        for (hour in 0..23) {
            assertThat(p.basalBlocks[hour].amount).isWithin(1e-12).of(average * oneToTen[hour])
        }
    }

    @Test
    fun `isf and ic divide by the factor at the timeshift plus one index`() {
        // timeshift 0 makes the ISF/IC index start at factor[23] because of the old +1 rule.
        val p = requireProfile(profile(age = 5, isf = 50.0, ic = 10.0, timeshift = 0.0))
        assertThat(p.isfBlocks[0].amount).isWithin(1e-12).of(50.0 / oneToTen[23])
        assertThat(p.icBlocks[0].amount).isWithin(1e-12).of(10.0 / oneToTen[23])
        // The next hour wraps to factor[0].
        assertThat(p.isfBlocks[1].amount).isWithin(1e-12).of(50.0 / oneToTen[0])
        assertThat(p.icBlocks[1].amount).isWithin(1e-12).of(10.0 / oneToTen[0])
    }

    @Test
    fun `positive timeshift delays the basal schedule`() {
        val p = requireProfile(profile(age = 5, timeshift = 6.0))
        assertThat(p.basalBlocks[0].amount).isWithin(1e-12).of(factorAt(oneToTen, 0, 6.0))
        assertThat(p.basalBlocks[6].amount).isWithin(1e-12).of(oneToTen[0])
        assertThat(p.basalBlocks[23].amount).isWithin(1e-12).of(factorAt(oneToTen, 23, 6.0))
    }

    @Test
    fun `negative timeshift advances the basal schedule`() {
        val p = requireProfile(profile(age = 5, timeshift = -6.0))
        assertThat(p.basalBlocks[0].amount).isWithin(1e-12).of(oneToTen[6])
        assertThat(p.basalBlocks[18].amount).isWithin(1e-12).of(oneToTen[0])
        assertThat(p.basalBlocks[23].amount).isWithin(1e-12).of(factorAt(oneToTen, 23, -6.0))
    }

    @Test
    fun `timeshift twenty four wraps basal to the unshifted table but isf and ic keep the plus one rule`() {
        val unshifted = requireProfile(profile(timeshift = 0.0))
        val wrapped = requireProfile(profile(timeshift = 24.0))
        assertThat(wrapped.basalBlocks.map { it.amount })
            .isEqualTo(unshifted.basalBlocks.map { it.amount })
        // The old code passed timeshift + 1 to the divisor helper, so 24 becomes 25 and the
        // ISF/IC schedules move by one hour while basal does not.
        assertThat(wrapped.isfBlocks[0].amount).isWithin(1e-12).of(50.0 / oneToTen[1])
    }

    @Test
    fun `negative timeshift values below zero hours wrap without crashing`() {
        val p = requireProfile(profile(age = 5, timeshift = -30.0))
        assertThat(p.basalBlocks[0].amount).isWithin(1e-12).of(oneToTen[6])
        assertThat(p.isfBlocks[0].amount).isWithin(1e-12).of(50.0 / oneToTen[5])
    }

    @Test
    fun `target is one hundred eight mgdl converted to the profile units`() {
        val mgdl = requireProfile(profile(units = GlucoseUnit.MGDL))
        assertThat(mgdl.targetBlocks[0].lowTarget).isWithin(1e-9).of(108.0)
        assertThat(mgdl.targetBlocks[0].highTarget).isWithin(1e-9).of(108.0)
        assertThat(mgdl.glucoseUnit).isEqualTo(GlucoseUnit.MGDL)

        val mmol = requireProfile(profile(units = GlucoseUnit.MMOL))
        val expected = profileUtil.fromMgdlToUnits(108.0, GlucoseUnit.MMOL)
        assertThat(mmol.targetBlocks[0].lowTarget).isWithin(1e-12).of(expected)
        assertThat(mmol.targetBlocks[0].highTarget).isWithin(1e-12).of(expected)
        assertThat(mmol.glucoseUnit).isEqualTo(GlucoseUnit.MMOL)
    }

    @Test
    fun `isf is used in the units passed in and is not converted by the helper`() {
        val mgdl = requireProfile(profile(isf = 50.0, units = GlucoseUnit.MGDL))
        assertThat(mgdl.isfBlocks[0].amount).isWithin(1e-12).of(50.0 / oneToTen[23])

        val mmolValue = profileUtil.fromMgdlToUnits(50.0, GlucoseUnit.MMOL)
        val mmol = requireProfile(profile(isf = mmolValue, units = GlucoseUnit.MMOL))
        assertThat(mmol.isfBlocks[0].amount).isWithin(1e-12).of(mmolValue / oneToTen[23])
        assertThat(mmol.glucoseUnit).isEqualTo(GlucoseUnit.MMOL)
    }

    @Test
    fun `a profile whose scaled values stay inside the hard limits passes semantic validation`() {
        val p = requireProfile(profile(age = 61, tdd = 30.0, basalSumPct = 0.4, isf = 50.0, ic = 10.0))
        val check = ProfileSealed.Pure(p, activePlugin).validateSemantic(baseText, hardLimits)
        assertThat(check.isValid).isTrue()
    }

    @Test
    fun `a profile whose scaled ISF falls below the hard limit is rejected by semantic validation`() {
        // entered 2.0 mg/dL is at the raw LIMIT_ISF start, but the 61+ band divides by up to 1.43.
        val p = requireProfile(profile(age = 61, isf = 2.0))
        val check = ProfileSealed.Pure(p, activePlugin).validateSemantic(baseText, hardLimits)
        assertThat(check.isValid).isFalse()
    }

    @Test
    fun `a profile whose scaled IC falls below the hard limit is rejected by semantic validation`() {
        // entered 2.0 g/U is at the adult hard minimum, but the 61+ band divides by up to 1.43.
        val p = requireProfile(profile(age = 61, ic = 2.0))
        val check = ProfileSealed.Pure(p, activePlugin).validateSemantic(baseText, hardLimits)
        assertThat(check.isValid).isFalse()
    }

    @Test
    fun `utcOffset is the system offset at the current time`() {
        val p = requireProfile(profile())
        assertThat(p.utcOffset).isEqualTo(systemUtcOffsetAt(dateUtil.now()))
    }

    @Test
    fun `blank or invalid inputs do not produce a dosed profile`() {
        assertThat(profile(tdd = 0.0)).isNull()
        assertThat(profile(tdd = -20.0)).isNull()
        assertThat(profile(basalSumPct = 0.0)).isNull()
        assertThat(profile(isf = 0.0)).isNull()
        assertThat(profile(ic = 0.0)).isNull()
        assertThat(profile(tdd = Double.NaN)).isNull()
        assertThat(profile(isf = Double.POSITIVE_INFINITY)).isNull()
    }
}
