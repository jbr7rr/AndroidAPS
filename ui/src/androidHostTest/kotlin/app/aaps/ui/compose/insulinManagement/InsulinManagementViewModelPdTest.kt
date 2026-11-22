package app.aaps.ui.compose.insulinManagement

import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.ConcentrationType
import app.aaps.core.interfaces.insulin.InsulinManager
import app.aaps.core.interfaces.insulin.InsulinManager.UpdateResult
import app.aaps.core.interfaces.insulin.InsulinType
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.SingleProfile
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class InsulinManagementViewModelPdTest {

    @Mock private lateinit var insulinManager: InsulinManager
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var hardLimits: HardLimits
    private val rh = generatedTextResolver()
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var profileRepository: ProfileRepository
    @Mock private lateinit var config: Config
    @Mock private lateinit var batchExecutor: BatchExecutor

    private val testDispatcher = StandardTestDispatcher()
    private val configFlow = MutableStateFlow("")
    private lateinit var sut: InsulinManagementViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        whenever(preferences.get(StringNonKey.InsulinConfiguration)).thenReturn("")
        whenever(profileRepository.profiles).thenReturn(MutableStateFlow(emptyList<SingleProfile>()))
        whenever(persistenceLayer.observeChanges(EPS::class)).thenReturn(emptyFlow())
        whenever(preferences.observe(StringNonKey.InsulinConfiguration)).thenReturn(configFlow)
        whenever(hardLimits.diaRange()).thenReturn(5.0..9.0)
        whenever(hardLimits.peakRange()).thenReturn(35..120)
        sut = InsulinManagementViewModel(
            insulinManager, preferences, profileFunction, dateUtil, hardLimits,
            rh, rxBus, persistenceLayer, profileRepository, config, batchExecutor,
            CoroutineScope(UnconfinedTestDispatcher())
        )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun loadSingle(iCfg: ICfg) {
        whenever(insulinManager.insulins).thenReturn(arrayListOf(iCfg))
        whenever(insulinManager.insulinIndex(anyOrNull())).thenReturn(0)
        sut.loadData(reload = false)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `CUSTOM_PD and LYUMJEV_PD presets produce expected model, peak, DIA and concentration`() {
        val custom = InsulinType.CUSTOM_PD.getICfg(rh)
        assertThat(custom.peak).isEqualTo(51)
        assertThat(custom.dia).isEqualTo(8.0)
        assertThat(custom.concentration).isEqualTo(1.0)
        assertThat(custom.insulinModel).isEqualTo(
            InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false, durationMinutes = 480)
        )

        val lyumjev = InsulinType.LYUMJEV_PD.getICfg(rh)
        assertThat(lyumjev.peak).isEqualTo(65)
        assertThat(lyumjev.dia).isEqualTo(8.0)
        assertThat(lyumjev.concentration).isEqualTo(1.0)
        assertThat(lyumjev.insulinModel).isEqualTo(
            InsulinModel.Pd(61.33, 12.27, 0.05185, concentrationScalesPeak = true, durationMinutes = 480)
        )
    }

    @Test
    fun `fromICfg identifies PD presets instead of falling back to Free Peak`() {
        assertThat(InsulinType.fromICfg(InsulinType.CUSTOM_PD.iCfg)).isEqualTo(InsulinType.CUSTOM_PD)
        assertThat(InsulinType.fromICfg(InsulinType.LYUMJEV_PD.iCfg)).isEqualTo(InsulinType.LYUMJEV_PD)
        assertThat(InsulinType.fromICfg(InsulinType.OREF_RAPID_ACTING.iCfg)).isEqualTo(InsulinType.OREF_RAPID_ACTING)
    }

    @Test
    fun `loading Lyumjev PD preset and switching to U200 scales peak denominator by concentration`() {
        loadSingle(ICfg("Rapid 75m 5h U100", 5L * 3600 * 1000, 75L * 60 * 1000, 1.0))

        sut.loadPeakFromPreset(InsulinType.LYUMJEV_PD)
        sut.updateEditorConcentration(ConcentrationType.U200)

        val built = sut.buildEditorICfg()
        assertThat(built.dia).isEqualTo(8.0)
        assertThat(built.peak).isEqualTo(65)
        assertThat(built.concentration).isEqualTo(2.0)
        assertThat(built.insulinModel).isEqualTo(
            InsulinModel.Pd(61.33, 12.27, 0.05185, concentrationScalesPeak = true, durationMinutes = 480)
        )
        // 4 U actual U200 bolus (2 U pump volume) matches 3.4 cU=2.0 peak (~100.04 min) without double-scaling.
        assertThat(built.peakMinutesForDose(4.0)).isWithin(1e-6).of((61.33 + 12.27 * 4.0) / (1.0 + 0.05185 * 4.0 / 2.0))
    }

    @Test
    fun `PD locks DIA at 8h and ignores edit attempts to shorten DIA`() {
        loadSingle(ICfg("Rapid 75m 5h U100", 5L * 3600 * 1000, 75L * 60 * 1000, 1.0))

        sut.loadPeakFromPreset(InsulinType.CUSTOM_PD)
        assertThat(sut.uiState.value.editorDiaHours).isEqualTo(8.0)

        sut.updateEditorDia(5.0)
        assertThat(sut.uiState.value.editorDiaHours).isEqualTo(8.0)
        assertThat(sut.buildEditorICfg().dia).isEqualTo(8.0)
    }

    @Test
    fun `model and PD parameter edits trigger unsaved changes and persist on save`() {
        val original = ICfg(
            insulinLabel = "Custom PD 51m 8h U100",
            insulinEndTime = 8L * 3600 * 1000,
            insulinPeakTime = 51L * 60 * 1000,
            concentration = 1.0,
            insulinModel = InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false, durationMinutes = 480)
        ).also { it.insulinNickname = "Custom PD" }
        loadSingle(original)

        assertThat(sut.hasUnsavedChanges()).isFalse()

        sut.updateEditorPdA0(55.0)
        sut.updateEditorPdA1(11.0)
        sut.updateEditorPdB1(0.06)
        sut.updateEditorPdConcentrationScalesPeak(true)
        assertThat(sut.hasUnsavedChanges()).isTrue()

        whenever(insulinManager.updateInsulin(eq(original.insulinLabel), any())).thenReturn(UpdateResult.Updated("Custom PD 51m 8h U100"))
        val saved = sut.saveCurrentInsulin()
        assertThat(saved).isTrue()

        val captor = argumentCaptor<ICfg>()
        verify(insulinManager).updateInsulin(eq(original.insulinLabel), captor.capture())
        assertThat(captor.firstValue.insulinModel).isEqualTo(
            InsulinModel.Pd(55.0, 11.0, 0.06, concentrationScalesPeak = true, durationMinutes = 480)
        )
        assertThat(captor.firstValue.dia).isEqualTo(8.0)
    }

    @Test
    fun `validation rejects out-of-range and non-finite PD parameters`() {
        val original = ICfg(
            insulinLabel = "Custom PD 51m 8h U100",
            insulinEndTime = 8L * 3600 * 1000,
            insulinPeakTime = 51L * 60 * 1000,
            concentration = 1.0,
            insulinModel = InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false, durationMinutes = 480)
        ).also { it.insulinNickname = "Custom PD" }
        loadSingle(original)

        // a0 out of 10..120
        sut.updateEditorPdA0(5.0)
        assertThat(sut.saveCurrentInsulin()).isFalse()
        sut.updateEditorPdA0(Double.NaN)
        assertThat(sut.saveCurrentInsulin()).isFalse()
        sut.updateEditorPdA0(51.0)

        // a1 out of 3..20
        sut.updateEditorPdA1(25.0)
        assertThat(sut.saveCurrentInsulin()).isFalse()
        sut.updateEditorPdA1(12.27)

        // b1 out of 0.02..0.1
        sut.updateEditorPdB1(0.01)
        assertThat(sut.saveCurrentInsulin()).isFalse()
        sut.updateEditorPdB1(0.05185)

        // concentration out of 0.2..2.0 (U10 = 0.1)
        sut.updateEditorConcentration(ConcentrationType.U10)
        assertThat(sut.saveCurrentInsulin()).isFalse()

        verify(insulinManager, never()).updateInsulin(any(), any())
    }

    @Test
    fun `bilinear has no PD parameter validation even if hidden PD fields are out of bounds`() {
        val original = ICfg(
            insulinLabel = "Rapid 75m 5h U100",
            insulinEndTime = 5L * 3600 * 1000,
            insulinPeakTime = 75L * 60 * 1000,
            concentration = 1.0,
            insulinModel = InsulinModel.Bilinear
        ).also { it.insulinNickname = "Rapid" }
        loadSingle(original)

        sut.updateEditorPdA0(Double.NaN)
        sut.updateEditorConcentration(ConcentrationType.U10)
        whenever(insulinManager.updateInsulin(eq(original.insulinLabel), any())).thenReturn(UpdateResult.Updated("Rapid 75m 5h U10"))

        assertThat(sut.saveCurrentInsulin()).isTrue()
    }
}
