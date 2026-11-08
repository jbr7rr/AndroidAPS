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
}
