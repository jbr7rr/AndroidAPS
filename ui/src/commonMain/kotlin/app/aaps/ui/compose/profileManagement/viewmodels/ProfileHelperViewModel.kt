package app.aaps.ui.compose.profileManagement.viewmodels

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.observeChanges
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profile.PureProfile
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcProfile
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.profileHelper.ProfileType
import app.aaps.ui.compose.profileHelper.defaultProfile.DefaultProfile
import app.aaps.ui.compose.profileHelper.defaultProfile.DefaultProfileCircadian
import app.aaps.ui.compose.profileHelper.defaultProfile.DefaultProfileDPV
import app.aaps.ui.compose.stats.TddStatsData
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for ProfileHelperScreen managing profile comparison state and business logic.
 */
// Registers itself: @ViewModelKey infers the key from the class. No graph entry, and deliberately
// unscoped so each screen gets its own.
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class ProfileHelperViewModel(
    private val persistenceLayer: PersistenceLayer,
    private val profileRepository: ProfileRepository,
    private val profileFunction: ProfileFunction,
    val profileUtil: ProfileUtil,
    val rh: TextResolver,
    val dateUtil: DateUtil,
    private val tddCalculator: TddCalculator,
    private val defaultProfile: DefaultProfile,
    private val defaultProfileDPV: DefaultProfileDPV,
    private val defaultProfileCircadian: DefaultProfileCircadian,
    private val hardLimits: HardLimits,
    private val rxBus: RxBus,
    private val fabricPrivacy: FabricPrivacy
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileHelperUiState())
    val uiState: StateFlow<ProfileHelperUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
        observeProfileChanges()
    }

    private fun loadInitialData() {
        refreshCurrentProfile()
        viewModelScope.launch {
            val availableProfiles = profileRepository.profile.value?.getProfileList() ?: ArrayList()
            _uiState.update { it.copy(availableProfiles = availableProfiles) }
            loadTddStats()
        }
    }

    /**
     * Re-read the current profile whenever effective profile switches change so the screen does not
     * keep showing a previous EPS after a profile switch. Debounced to collapse rapid changes; the
     * delay also lets ProfileFunction's (undebounced) cache invalidation run first, so the reads
     * below see a fresh cache.
     */
    @OptIn(FlowPreview::class)
    private fun observeProfileChanges() {
        persistenceLayer.observeChanges<EPS>()
            .debounce(500L)
            .onEach { refreshCurrentProfile() }
            .launchIn(viewModelScope)
    }

    private fun refreshCurrentProfile() {
        viewModelScope.launch {
            val currentProfileName = profileFunction.getProfileName()
            val currentProfile = profileFunction.getProfile()?.convertToNonCustomizedProfile(dateUtil)
            val profileSwitches = withContext(aapsIoDispatcher) {
                persistenceLayer.getEffectiveProfileSwitchesFromTime(
                    dateUtil.now() - T.months(2).msecs(),
                    true
                )
            }

            _uiState.update {
                it.copy(
                    currentProfileName = currentProfileName,
                    currentProfile = currentProfile,
                    profileSwitches = profileSwitches
                )
            }
        }
    }

    private fun loadTddStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingStats = true) }
            try {
                val data = withContext(aapsIoDispatcher) {
                    val tdds = tddCalculator.calculate(7, allowMissingDays = true)
                    val averageTdd = tddCalculator.averageTDD(tdds)
                    val todayTdd = tddCalculator.calculateToday()
                    TddStatsData(tdds = tdds, averageTdd = averageTdd, todayTdd = todayTdd)
                }
                _uiState.update { it.copy(tddStatsData = data, isLoadingStats = false) }
            } catch (e: Exception) {
                fabricPrivacy.logException(e)
                _uiState.update { it.copy(isLoadingStats = false) }
            }
        }
    }

    fun getUnits(): GlucoseUnit = profileFunction.getUnits()

    /** IC limits for the current safety age, as used by the profile editor. */
    fun getIcRange(): ClosedFloatingPointRange<Double> = hardLimits.icRange()

    /**
     * A generated profile is only offered for comparison or cloning when AAPS would accept it.
     *
     * The Circadian factors divide ISF and IC, so a value the user entered inside the raw hard
     * limits can still land outside them in the generated blocks. This check also catches a basal
     * rate that is out of range. It is the same semantic validation the profile repository and the
     * setup wizard use, so the helper cannot create a profile that is rejected immediately after.
     */
    fun isProfileValid(profile: PureProfile): Boolean =
        ProfileSealed.Pure(profile, activePlugin = null).validateSemantic(rh, hardLimits).isValid

    /**
     * Get profile based on type and parameters
     */
    fun getProfile(
        age: Int,
        tdd: Double,
        weight: Double,
        basalPct: Double,
        profileType: ProfileType,
        profileIndex: Int,
        profileSwitchIndex: Int,
        isf: Double = 0.0,
        ic: Double = 0.0,
        timeshift: Double = 0.0
    ): PureProfile? {
        return try {
            when (profileType) {
                ProfileType.MOTOL_DEFAULT     -> defaultProfile.profile(age, tdd, weight, profileFunction.getUnits())
                ProfileType.DPV_DEFAULT       -> defaultProfileDPV.profile(age, tdd, basalPct, profileFunction.getUnits())
                ProfileType.CIRCADIAN_DEFAULT -> defaultProfileCircadian.profile(age, tdd, basalPct, isf, ic, timeshift, profileFunction.getUnits())
                ProfileType.CURRENT           -> uiState.value.currentProfile

                ProfileType.AVAILABLE_PROFILE -> {
                    // Snapshot once so the by-index lookup and the by-name lookup see the
                    // same store version.
                    val store = profileRepository.profile.value
                    val list = store?.getProfileList()
                    if (list != null && profileIndex < list.size)
                        store.getSpecificProfile(list[profileIndex].toString())
                    else null
                }

                ProfileType.PROFILE_SWITCH    -> {
                    val switches = uiState.value.profileSwitches
                    if (profileSwitchIndex < switches.size)
                        ProfileSealed.EPS(value = switches[profileSwitchIndex], activePlugin = null)
                            .convertToNonCustomizedProfile(dateUtil)
                    else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get profile name based on type and parameters
     */
    fun getProfileName(
        age: Int,
        tdd: Double,
        weight: Double,
        basalPct: Double,
        profileType: ProfileType,
        profileIndex: Int,
        profileSwitchIndex: Int
    ): String {
        return when (profileType) {
            ProfileType.MOTOL_DEFAULT     -> if (tdd > 0) rh.gs(UiStrings.format_with_tdd, age, tdd)
            else rh.gs(UiStrings.format_with_weight, age, weight)

            ProfileType.DPV_DEFAULT       -> rh.gs(UiStrings.format_with_tdd_and_pct, age, tdd, (basalPct * 100).toInt())
            ProfileType.CIRCADIAN_DEFAULT -> rh.gs(UiStrings.format_with_tdd_and_pct_circadian, age, tdd, (basalPct * 100).toInt())
            ProfileType.CURRENT           -> uiState.value.currentProfileName

            ProfileType.AVAILABLE_PROFILE -> {
                val list = profileRepository.profile.value?.getProfileList()
                if (list != null && profileIndex < list.size) list[profileIndex].toString() else ""
            }

            ProfileType.PROFILE_SWITCH    -> {
                val switches = uiState.value.profileSwitches
                if (profileSwitchIndex < switches.size) switches[profileSwitchIndex].originalCustomizedName else ""
            }
        }
    }

    /**
     * Copy generated profile to local profiles
     */
    fun copyToLocal(
        age: Int,
        tdd: Double,
        weight: Double,
        pct: Double,
        profileType: ProfileType,
        isf: Double = 0.0,
        ic: Double = 0.0,
        timeshift: Double = 0.0
    ) {
        val units = profileFunction.getUnits()
        val profile = when (profileType) {
            ProfileType.MOTOL_DEFAULT     -> defaultProfile.profile(age, tdd, weight, units)
            ProfileType.DPV_DEFAULT       -> defaultProfileDPV.profile(age, tdd, pct / 100.0, units)
            ProfileType.CIRCADIAN_DEFAULT -> defaultProfileCircadian.profile(age, tdd, pct / 100.0, isf, ic, timeshift, units)
            // CURRENT, AVAILABLE_PROFILE and PROFILE_SWITCH only display a profile; there is
            // nothing to generate or copy for them.
            else                          -> null
        }

        profile?.let {
            rxBus.send(
                EventShowDialog.OkCancel(
                    title = rh.gs(CoreUiStrings.careportal_profileswitch),
                    message = rh.gs(CoreUiStrings.copytolocalprofile),
                    icon = IcProfile,
                    onOk = {
                        viewModelScope.launch {
                            profileRepository.add(
                                profileRepository.copyFrom(
                                    it,
                                    "DefaultProfile " + dateUtil.dateAndTimeAndSecondsString(dateUtil.now()).replace(".", "/")
                                )
                            )
                        }
                    }
                )
            )
        }
    }
}

/**
 * UI state for ProfileHelperScreen
 */
@Stable
data class ProfileHelperUiState(
    val currentProfileName: String = "",
    val currentProfile: PureProfile? = null,
    val availableProfiles: List<CharSequence> = emptyList(),
    val profileSwitches: List<EPS> = emptyList(),
    val tddStatsData: TddStatsData? = null,
    val isLoadingStats: Boolean = true
)
