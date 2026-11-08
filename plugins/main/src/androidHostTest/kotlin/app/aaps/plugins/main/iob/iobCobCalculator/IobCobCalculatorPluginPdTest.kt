package app.aaps.plugins.main.iob.iobCobCalculator

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * The IOB query window must cover the whole PD curve. The 3.4 PD plugins always used 8 h,
 * but 4.0 selects boluses and builds the IOB array from the profile DIA. A shorter DIA must
 * not hide the PD tail: it is real IOB and leaving it out can cause over-delivery.
 */
class IobCobCalculatorPluginPdTest {

    private val persistenceLayer = mock<PersistenceLayer>()
    private val activePlugin = mock<ActivePlugin>()
    private val dateUtil = mock<DateUtil>()
    private val preferences = mock<Preferences>()
    private val profileFunction = mock<ProfileFunction>()
    private val profile = mock<EffectiveProfile>()
    private lateinit var sut: IobCobCalculatorPlugin

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val pdModel = InsulinModel.Pd(a0 = 51.0, a1 = 12.27, b1 = 0.05185, durationMinutes = 480)
    private val pd5 = ICfg(insulinLabel = "Custom PD", peak = 51, dia = 5.0, concentration = 1.0, insulinModel = pdModel)

    @BeforeEach
    fun setUp() = runBlocking {
        whenever(dateUtil.now()).thenReturn(now)
        whenever(preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)).thenReturn(2.0)
        whenever(profile.iCfg).thenReturn(pd5)
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(persistenceLayer.getBolusesFromTime(any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getExtendedBolusesStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        val pump = mock<PumpWithConcentration>()
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(false)
        whenever(activePlugin.activePump).thenReturn(pump)
        sut = IobCobCalculatorPlugin(
            AAPSLoggerTest(), mock<RxBus>(), preferences, mock<TextResolver>(), profileFunction, activePlugin, dateUtil,
            persistenceLayer, mock<OverviewData>(), mock<CalculationWorkflow>(), mock<DecimalFormatter>(), mock<ProcessedTbrEbData>(),
            mock<CalculationSignalsEmitter>(), mock<NotificationManager>()
        ) { mock<OverviewDataCache>() }
    }

    @Test
    fun `PD bolus range covers the model duration when the DIA is shorter`() = runBlocking {
        sut.calculateFromTreatmentsAndTemps(now, profile)

        verifyBlocking(persistenceLayer, atLeastOnce()) { getBolusesFromTime(eq(now - 8 * hour), eq(true)) }
    }

    @Test
    fun `bilinear bolus range still uses the DIA`() = runBlocking {
        val bilinear = ICfg(insulinLabel = "rapid", peak = 75, dia = 5.0, concentration = 1.0)
        whenever(profile.iCfg).thenReturn(bilinear)

        sut.calculateFromTreatmentsAndTemps(now, profile)

        verifyBlocking(persistenceLayer, atLeastOnce()) { getBolusesFromTime(eq(now - 5 * hour), eq(true)) }
    }

    @Test
    fun `PD IOB array covers the model duration when the DIA is shorter`() = runBlocking {
        val array = sut.calculateIobArrayInDia(profile)

        // 8 h curve plus the usual 30 min tail, in 5 min steps.
        assertThat(array.size).isEqualTo(((8.0 * 60 + 30) / 5).toInt())
    }
}
