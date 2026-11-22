package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.data.model.InsulinModel
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class OpenAPSSMBPluginTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var determineBasalSMB: DetermineBasalSMB
    @Mock lateinit var bgQualityCheck: BgQualityCheck
    @Mock lateinit var tddCalculator: TddCalculator
    @Mock lateinit var profiler: Profiler
    private lateinit var openAPSSMBPlugin: OpenAPSSMBPlugin

    private fun createPlugin(bus: RxBus = rxBus) = OpenAPSSMBPlugin(
        aapsLogger, bus, constraintChecker, rh, profileFunction, profileUtil, config, activePlugin,
        iobCobCalculator, hardLimits, preferences, dateUtil, processedTbrEbData, persistenceLayer, glucoseStatusProvider,
        tddCalculator, bgQualityCheck, notificationManager, determineBasalSMB, profiler,
        GlucoseStatusCalculatorSMB(aapsLogger, iobCobCalculator, dateUtil, decimalFormatter, deltaCalculator), { apsResultProvider() }, ch,
        fabricPrivacy
    )

    @BeforeEach fun prepare() {
        openAPSSMBPlugin = createPlugin()
    }

    /** See the note in `OpenAPSAMAPluginTest`: construction is the coverage this class still carries. */
    @Test
    fun `the plugin constructs and is an APS`() {
        assertThat(openAPSSMBPlugin.getType()).isEqualTo(PluginType.APS)
    }

    @Test
    fun `refuses to run with an unusable insulin`() = runBlocking {
        val bus = mock<RxBus>()
        val plugin = createPlugin(bus)
        val invalidICfg = someICfg.copy(
            insulinModel = InsulinModel.Pd(a0 = Double.NaN, a1 = 12.27, b1 = 0.05185)
        )
        doReturn("Insulin settings are invalid.").whenever(rh).gs(ApsStrings.openapsma_invalid_insulin)
        val invalidProfile = ProfileSealed.EPS(effectiveProfileSwitch.copy(iCfg = invalidICfg), activePlugin)
        whenever(profileFunction.getProfile()).thenReturn(invalidProfile)

        plugin.invoke("test", false)

        verify(bus).send(any<EventResetOpenAPSGui>())
        verifyNoInteractions(determineBasalSMB)
    }

    @Test
    fun `refuses to run when a contributing bolus has an unusable insulin model`() = runBlocking {
        val bus = mock<RxBus>()
        val plugin = createPlugin(bus)
        val now = 1_700_000_000_000L
        whenever(dateUtil.now()).thenReturn(now)
        val validProfile = ProfileSealed.EPS(effectiveProfileSwitch.copy(iCfg = someICfg), activePlugin)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        doReturn("Insulin settings are invalid.").whenever(rh).gs(ApsStrings.openapsma_invalid_insulin)
        val invalidBolus = app.aaps.core.data.model.BS(
            timestamp = now - 30L * 60 * 1000,
            isValid = true,
            amount = 1.5,
            type = app.aaps.core.data.model.BS.Type.NORMAL,
            iCfg = someICfg.copy(insulinModel = InsulinModel.Pd(a0 = 0.0, a1 = 12.27, b1 = 0.05185))
        )
        whenever(persistenceLayer.getBolusesFromTime(any(), any())).thenReturn(listOf(invalidBolus))

        plugin.invoke("test", false)

        verify(bus).send(any<EventResetOpenAPSGui>())
        verifyNoInteractions(determineBasalSMB)
    }

    @Test
    fun `gatherWaveSnapshot returns Disabled without IOB or bolus queries when Wave is off`() = runBlocking {
        whenever(preferences.get(app.aaps.core.keys.BooleanKey.ApsWaveEnable)).thenReturn(false)
        val validProfile = ProfileSealed.EPS(effectiveProfileSwitch.copy(iCfg = someICfg), activePlugin)

        val snapshot = openAPSSMBPlugin.gatherWaveSnapshot(1_700_000_000_000L, validProfile)

        assertThat(snapshot).isEqualTo(WaveSnapshot.Disabled)
        verifyNoInteractions(persistenceLayer)
        verifyNoInteractions(iobCobCalculator)
    }

    @Test
    fun `gatherWaveSnapshot uses profile iCfg peak, PD model, activityAtPeak, and newest bolus`() = runBlocking {
        val now = 1_700_000_000_000L
        val pdICfg = someICfg.copy(
            insulinLabel = "Lyumjev PD",
            insulinEndTime = 8L * 3600 * 1000,
            insulinPeakTime = 65L * 60 * 1000,
            concentration = 2.0,
            insulinModel = InsulinModel.Pd(61.33, 12.27, 0.05185, concentrationScalesPeak = true, durationMinutes = 480)
        )
        val pdProfile = ProfileSealed.EPS(effectiveProfileSwitch.copy(iCfg = pdICfg), activePlugin)
        whenever(preferences.get(app.aaps.core.keys.BooleanKey.ApsWaveEnable)).thenReturn(true)
        whenever(preferences.get(app.aaps.core.keys.DoubleKey.ApsWaveInsReqPct)).thenReturn(80.0)
        whenever(preferences.get(app.aaps.core.keys.IntKey.ApsWaveStartTime)).thenReturn(8)
        whenever(preferences.get(app.aaps.core.keys.IntKey.ApsWaveEndTime)).thenReturn(23)
        whenever(preferences.get(app.aaps.core.keys.IntKey.ApsWaveMaxMinutesOfBasalToLimitSmb)).thenReturn(180)
        whenever(preferences.get(app.aaps.core.keys.BooleanKey.ApsWaveUseSMBCap)).thenReturn(false)
        whenever(preferences.get(app.aaps.core.keys.DoubleKey.ApsWaveSmbCap)).thenReturn(0.5)
        whenever(preferences.get(app.aaps.core.keys.BooleanKey.ApsWaveUseAdjustedSens)).thenReturn(true)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(
            app.aaps.core.data.model.BS(
                timestamp = now - 5 * 60_000L,
                isValid = true,
                amount = 1.1,
                type = app.aaps.core.data.model.BS.Type.SMB,
                iCfg = pdICfg
            )
        )
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any())).thenReturn(
            app.aaps.core.interfaces.aps.IobTotal(time = now, activity = 0.01)
        )

        val start = kotlin.time.TimeSource.Monotonic.markNow()
        val snapshot = openAPSSMBPlugin.gatherWaveSnapshot(now, pdProfile)
        val elapsedMs = start.elapsedNow().inWholeMilliseconds

        assertThat(snapshot.settings.enabled).isTrue()
        assertThat(snapshot.isPd).isTrue()
        assertThat(snapshot.peak).isEqualTo(65)
        assertThat(snapshot.lastBolusAmount).isEqualTo(1.1)
        assertThat(snapshot.activityAtPeak(2.0)).isWithin(1e-9).of(pdICfg.activityAtPeak(2.0))
        // 5 samples per window * 0.01 = 0.05
        assertThat(snapshot.activity.currentActivity).isWithin(1e-6).of(0.05)
        assertThat(snapshot.activity.futureActivity).isWithin(1e-6).of(0.05)
        assertThat(snapshot.activity.sensorLagActivity).isWithin(1e-6).of(0.05)
        assertThat(snapshot.activity.historicActivity).isWithin(1e-6).of(0.05)
        // Performance budget check (< 2000 ms for the 20 Wave activity samples)
        assertThat(elapsedMs).isLessThan(2000L)
    }
}
