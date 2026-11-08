package app.aaps.ui.compose.profileHelper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.graph.profile.ProfileCompareContent
import app.aaps.core.graph.profile.buildBasalRows
import app.aaps.core.graph.profile.buildIcRows
import app.aaps.core.graph.profile.buildIsfRows
import app.aaps.core.graph.profile.buildTargetRows
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.bottomBarSafeArea
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.profileManagement.viewmodels.ProfileHelperViewModel
import app.aaps.ui.compose.stats.TddStatsCompose

/**
 * Enumeration of available profile calculation/source types.
 */
enum class ProfileType {

    MOTOL_DEFAULT,
    DPV_DEFAULT,
    CIRCADIAN_DEFAULT,
    CURRENT,
    AVAILABLE_PROFILE,
    PROFILE_SWITCH
}

/** The profile types this screen can generate, as opposed to the ones it only displays. */
private fun ProfileType.generatesProfile(): Boolean =
    this == ProfileType.MOTOL_DEFAULT || this == ProfileType.DPV_DEFAULT || this == ProfileType.CIRCADIAN_DEFAULT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileHelperScreen(
    viewModel: ProfileHelperViewModel,
    onBackClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    var selectedTab by remember { mutableIntStateOf(0) }

    val profileTypes = remember {
        mutableStateListOf(
            ProfileType.MOTOL_DEFAULT,
            ProfileType.CURRENT
        )
    }
    val ages = remember { mutableStateListOf(0, 0) }
    val weights = remember { mutableStateListOf(0.0, 0.0) }
    val tdds = remember { mutableStateListOf(0.0, 0.0) }
    val pcts = remember { mutableStateListOf(0.0, 0.0) }
    // Blank, not a unit-specific default: NumberInputRow shows zero as an empty field. The old
    // helper started ISF at 50.0 regardless of units, so an mmol/L user saw 50 mmol/L.
    val isfs = remember { mutableStateListOf(0.0, 0.0) }
    val ics = remember { mutableStateListOf(0.0, 0.0) }
    val timeshifts = remember { mutableStateListOf(0.0, 0.0) }
    val profileIndices = remember { mutableStateListOf(0, 0) }
    val profileSwitchIndices = remember { mutableStateListOf(0, 0) }

    val units = viewModel.getUnits()
    val profile0 = viewModel.getProfile(
        ages[0], tdds[0], weights[0], pcts[0] / 100.0,
        profileTypes[0], profileIndices[0], profileSwitchIndices[0],
        isfs[0], ics[0], timeshifts[0]
    )
    val profile1 = viewModel.getProfile(
        ages[1], tdds[1], weights[1], pcts[1] / 100.0,
        profileTypes[1], profileIndices[1], profileSwitchIndices[1],
        isfs[1], ics[1], timeshifts[1]
    )

    // A generated profile counts only when AAPS itself would accept it. This catches a blank
    // field, but also a value that the Circadian factors scaled outside the hard limits.
    val isCompareTabValid = run {
        val profile0Valid =
            if (profileTypes[0].generatesProfile()) profile0 != null && viewModel.isProfileValid(profile0) else true
        val profile1Valid =
            if (profileTypes[1].generatesProfile()) profile1 != null && viewModel.isProfileValid(profile1) else true
        profile0Valid && profile1Valid
    }

    // Determine which profile index to use for Clone action. On the comparison tab prefer a
    // generated profile that can actually be cloned.
    val cloneIndex = when {
        selectedTab == 2 -> if (profileTypes[0].generatesProfile() && profile0 != null && viewModel.isProfileValid(profile0)) 0 else 1
        else             -> selectedTab
    }
    val cloneProfile = if (cloneIndex == 0) profile0 else profile1
    val cloneTargetValid = profileTypes[cloneIndex].generatesProfile() &&
        cloneProfile != null && viewModel.isProfileValid(cloneProfile)

    // Determine if Clone button should be shown in top bar (visible on all tabs when comparison is available)
    val showCloneAction = isCompareTabValid && cloneTargetValid

    ProfileHelperContent(
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        profileTypes = profileTypes,
        onProfileTypeChange = { index, type -> profileTypes[index] = type },
        isCompareTabValid = isCompareTabValid,
        showCloneAction = showCloneAction,
        onCloneClick = {
            viewModel.copyToLocal(
                ages[cloneIndex],
                tdds[cloneIndex],
                weights[cloneIndex],
                pcts[cloneIndex],
                profileTypes[cloneIndex],
                isfs[cloneIndex],
                ics[cloneIndex],
                timeshifts[cloneIndex]
            )
        },
        onBackClick = onBackClick,
        focusManager = focusManager,
        comparisonContent = {
            if (profile0 != null && profile1 != null) {
                val name0 = viewModel.getProfileName(ages[0], tdds[0], weights[0], pcts[0] / 100.0, profileTypes[0], profileIndices[0], profileSwitchIndices[0])
                val name1 = viewModel.getProfileName(ages[1], tdds[1], weights[1], pcts[1] / 100.0, profileTypes[1], profileIndices[1], profileSwitchIndices[1])

                val sealed1 = ProfileSealed.Pure(profile0, null)
                val sealed2 = ProfileSealed.Pure(profile1, null)

                ProfileCompareContent(
                    profile1 = sealed1,
                    profile2 = sealed2,
                    icsRows = buildIcRows(sealed1, sealed2, viewModel.dateUtil),
                    icUnits = stringResource(InterfacesStrings.profile_carbs_per_unit),
                    isfsRows = buildIsfRows(sealed1, sealed2, viewModel.profileUtil, viewModel.dateUtil),
                    isfUnits = stringResource(if (units == GlucoseUnit.MGDL) InterfacesStrings.profile_isf_units_mgdl else InterfacesStrings.profile_isf_units_mmol),
                    basalsRows = buildBasalRows(sealed1, sealed2, viewModel.dateUtil),
                    basalUnits = stringResource(InterfacesStrings.profile_ins_units_per_hour),
                    targetsRows = buildTargetRows(sealed1, sealed2, viewModel.dateUtil, viewModel.profileUtil),
                    targetUnits = units.displayLabel,
                    profileName1 = name0,
                    profileName2 = name1
                )
            } else {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Text(
                        text = stringResource(CoreUiStrings.no_profile_set),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        },
        profileTabContent = { tabIndex ->
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                when (profileTypes[tabIndex]) {
                    ProfileType.MOTOL_DEFAULT, ProfileType.DPV_DEFAULT -> {
                        DefaultProfileContent(
                            age = ages[tabIndex],
                            onAgeChange = { ages[tabIndex] = it },
                            weight = weights[tabIndex],
                            onWeightChange = { weights[tabIndex] = it },
                            tdd = tdds[tabIndex],
                            onTddChange = { tdds[tabIndex] = it },
                            pct = pcts[tabIndex],
                            onPctChange = { pcts[tabIndex] = it },
                            showPct = profileTypes[tabIndex] == ProfileType.DPV_DEFAULT,
                            showWeight = tdds[tabIndex] == 0.0,
                            showTdd = weights[tabIndex] == 0.0,
                            modifier = Modifier.padding(16.dp)
                        )
                    }

                    ProfileType.CIRCADIAN_DEFAULT                      -> {
                        CircadianProfileContent(
                            age = ages[tabIndex],
                            onAgeChange = { ages[tabIndex] = it },
                            tdd = tdds[tabIndex],
                            onTddChange = { tdds[tabIndex] = it },
                            pct = pcts[tabIndex],
                            onPctChange = { pcts[tabIndex] = it },
                            isf = isfs[tabIndex],
                            onIsfChange = { isfs[tabIndex] = it },
                            ic = ics[tabIndex],
                            onIcChange = { ics[tabIndex] = it },
                            timeshift = timeshifts[tabIndex],
                            onTimeshiftChange = { timeshifts[tabIndex] = it },
                            units = units,
                            icRange = viewModel.getIcRange(),
                            modifier = Modifier.padding(16.dp)
                        )
                    }

                    ProfileType.CURRENT                                -> {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(UiStrings.active_profile),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = state.currentProfileName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    ProfileType.AVAILABLE_PROFILE                      -> {
                        AvailableProfileContent(
                            profiles = state.availableProfiles.map { it.toString() },
                            selectedIndex = profileIndices[tabIndex],
                            onProfileSelected = { profileIndices[tabIndex] = it },
                            modifier = Modifier.padding(16.dp)
                        )
                    }

                    ProfileType.PROFILE_SWITCH                         -> {
                        ProfileSwitchContent(
                            profileSwitches = state.profileSwitches.map { it.originalCustomizedName },
                            selectedIndex = profileSwitchIndices[tabIndex],
                            onProfileSwitchSelected = { profileSwitchIndices[tabIndex] = it },
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            if (profileTypes[tabIndex].generatesProfile()) {
                Spacer(modifier = Modifier.height(16.dp))

                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when {
                            state.isLoadingStats       -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(CoreUiStrings.loading),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            state.tddStatsData != null -> {
                                state.tddStatsData?.let { data ->
                                    TddStatsCompose(tddStatsData = data)
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

/**
 * @see ProfileHelperCurrentPreview
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileHelperContent(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    profileTypes: List<ProfileType>,
    onProfileTypeChange: (Int, ProfileType) -> Unit,
    isCompareTabValid: Boolean,
    showCloneAction: Boolean,
    onCloneClick: () -> Unit,
    onBackClick: () -> Unit,
    focusManager: FocusManager,
    comparisonContent: @Composable () -> Unit,
    profileTabContent: @Composable (Int) -> Unit,
) {
    val tabTitles = listOf(
        stringResource(UiStrings.profile_tab_1),
        stringResource(UiStrings.profile_tab_2),
        stringResource(UiStrings.comparation)
    )

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text((stringResourceOrNull(ElementType.PROFILE_HELPER.label()) ?: "")) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(CoreUiStrings.close))
                    }
                },
                actions = {}
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onCloneClick()
                },
                enabled = showCloneAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .bottomBarSafeArea()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (showCloneAction) stringResource(UiStrings.clone_label)
                    else stringResource(CoreUiStrings.ok)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .clearFocusOnTap(focusManager)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            Surface(tonalElevation = 2.dp) {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { onTabSelected(index) },
                            enabled = index != 2 || isCompareTabValid,
                            text = {
                                Text(
                                    text = title,
                                    color = if (index == 2 && !isCompareTabValid) MaterialTheme.colorScheme.error else LocalContentColor.current
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == 2) {
                comparisonContent()
            } else {
                // Profile type dropdown
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    ProfileTypeDropdown(
                        selectedType = profileTypes[selectedTab],
                        onTypeSelected = { onProfileTypeChange(selectedTab, it) },
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                profileTabContent(selectedTab)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileTypeDropdown(
    selectedType: ProfileType,
    onTypeSelected: (ProfileType) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val profileTypeOptions = listOf(
        ProfileType.MOTOL_DEFAULT to stringResource(UiStrings.motol_default_profile),
        ProfileType.DPV_DEFAULT to stringResource(UiStrings.dpv_default_profile),
        ProfileType.CIRCADIAN_DEFAULT to stringResource(UiStrings.circadian_default_profile),
        ProfileType.CURRENT to stringResource(UiStrings.current_profile),
        ProfileType.AVAILABLE_PROFILE to stringResource(UiStrings.available_profile),
        ProfileType.PROFILE_SWITCH to stringResource(CoreUiStrings.careportal_profileswitch)
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = getProfileTypeDisplayName(selectedType),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(UiStrings.profile_type)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profileTypeOptions.forEach { (type, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onTypeSelected(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun getProfileTypeDisplayName(type: ProfileType): String {
    return when (type) {
        ProfileType.MOTOL_DEFAULT     -> stringResource(UiStrings.motol_default_profile)
        ProfileType.DPV_DEFAULT       -> stringResource(UiStrings.dpv_default_profile)
        ProfileType.CIRCADIAN_DEFAULT -> stringResource(UiStrings.circadian_default_profile)
        ProfileType.CURRENT           -> stringResource(UiStrings.current_profile)
        ProfileType.AVAILABLE_PROFILE -> stringResource(UiStrings.available_profile)
        ProfileType.PROFILE_SWITCH    -> stringResource(CoreUiStrings.careportal_profileswitch)
    }
}

/**
 * @see ProfileHelperMotolPreview
 * @see DefaultProfileContentPreview
 */
@Composable
fun DefaultProfileContent(
    age: Int, onAgeChange: (Int) -> Unit,
    weight: Double, onWeightChange: (Double) -> Unit,
    tdd: Double, onTddChange: (Double) -> Unit,
    pct: Double, onPctChange: (Double) -> Unit,
    showPct: Boolean, showWeight: Boolean, showTdd: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(text = stringResource(UiStrings.profile_parameters), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 16.dp))
        NumberInputRow(
            labelRef = UiStrings.age,
            value = age.toDouble(),
            onValueChange = { onAgeChange(it.toInt()) },
            valueRange = 1.0..99.0,
            step = 1.0,
            unitLabel = CoreUiStrings.units_years
        )
        if (showTdd) NumberInputRow(
            labelRef = CoreUiStrings.tdd_total,
            value = tdd,
            onValueChange = onTddChange,
            valueRange = 0.0..200.0,
            step = 1.0,
            unitLabel = CoreUiStrings.units_insulin
        )
        if (showWeight) NumberInputRow(
            labelRef = UiStrings.weight_label,
            value = weight,
            onValueChange = onWeightChange,
            valueRange = 0.0..150.0,
            step = 1.0,
            unitLabel = CoreUiStrings.units_kg
        )
        if (showPct) NumberInputRow(
            labelRef = UiStrings.basal_pct_from_tdd_label,
            value = pct,
            onValueChange = onPctChange,
            valueRange = 32.0..37.0,
            step = 1.0,
            unitLabel = CoreUiStrings.units_percent
        )
    }
}

/**
 * Inputs for the Circadian generator.
 *
 * The ISF field is in the current glucose unit and changes its step with it: 1.0 for mg/dL,
 * 0.1 for mmol/L. A zero value is rendered as an empty field by [NumberInputRow], so an untouched
 * ISF is blank rather than a unit-blind 50.
 *
 * ISF and IC are nominal values: the generator divides them by the age-band factor. The note under
 * the fields warns about that, and the screen validates the generated blocks before offering them.
 *
 * @see DefaultProfileCircadianPreview
 */
@Composable
fun CircadianProfileContent(
    age: Int, onAgeChange: (Int) -> Unit,
    tdd: Double, onTddChange: (Double) -> Unit,
    pct: Double, onPctChange: (Double) -> Unit,
    isf: Double, onIsfChange: (Double) -> Unit,
    ic: Double, onIcChange: (Double) -> Unit,
    timeshift: Double, onTimeshiftChange: (Double) -> Unit,
    units: GlucoseUnit,
    icRange: ClosedFloatingPointRange<Double>,
    modifier: Modifier = Modifier
) {
    val isfMin = if (units == GlucoseUnit.MGDL) HardLimits.LIMIT_ISF.start else HardLimits.LIMIT_ISF.start * Constants.MGDL_TO_MMOLL
    val isfMax = if (units == GlucoseUnit.MGDL) HardLimits.LIMIT_ISF.endInclusive else HardLimits.LIMIT_ISF.endInclusive * Constants.MGDL_TO_MMOLL
    val isfStep = if (units == GlucoseUnit.MGDL) 1.0 else 0.1
    val isfDecimals = if (units == GlucoseUnit.MGDL) 0 else 1
    Column(modifier = modifier) {
        Text(text = stringResource(UiStrings.profile_parameters), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 16.dp))
        NumberInputRow(
            labelRef = UiStrings.age,
            value = age.toDouble(),
            onValueChange = { onAgeChange(it.toInt()) },
            valueRange = 1.0..100.0,
            step = 1.0,
            unitLabel = CoreUiStrings.units_years
        )
        NumberInputRow(
            labelRef = CoreUiStrings.tdd_total,
            value = tdd,
            onValueChange = onTddChange,
            valueRange = 0.0..200.0,
            step = 1.0,
            unitLabel = CoreUiStrings.units_insulin
        )
        NumberInputRow(
            labelRef = UiStrings.basal_pct_from_tdd_label,
            value = pct,
            onValueChange = onPctChange,
            valueRange = 30.0..60.0,
            step = 1.0,
            unitLabel = CoreUiStrings.units_percent
        )
        NumberInputRow(
            labelRef = CoreUiStrings.isf_long_label,
            value = isf,
            onValueChange = onIsfChange,
            valueRange = isfMin..isfMax,
            step = isfStep,
            decimalPlaces = isfDecimals,
            unitLabel = if (units == GlucoseUnit.MGDL) InterfacesStrings.profile_isf_units_mgdl else InterfacesStrings.profile_isf_units_mmol
        )
        NumberInputRow(
            labelRef = CoreUiStrings.ic_long_label,
            value = ic,
            onValueChange = onIcChange,
            valueRange = icRange,
            step = 0.1,
            decimalPlaces = 1,
            unitLabel = InterfacesStrings.profile_carbs_per_unit
        )
        NumberInputRow(
            labelRef = CoreUiStrings.timeshift_label,
            value = timeshift,
            onValueChange = onTimeshiftChange,
            valueRange = Constants.CPP_TIMESHIFT_RANGE,
            step = 1.0,
            unitLabel = CoreUiStrings.units_hours
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(UiStrings.circadian_values_scaled_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * @see AvailableProfileContentPreview
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvailableProfileContent(profiles: List<String>, selectedIndex: Int, onProfileSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProfile = if (profiles.isNotEmpty() && selectedIndex < profiles.size) profiles[selectedIndex] else ""

    Column(modifier = modifier) {
        Text(text = stringResource(UiStrings.available_profiles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedProfile, onValueChange = {}, readOnly = true,
                label = { Text(stringResource(UiStrings.selected_profile)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                profiles.forEachIndexed { index, profile ->
                    DropdownMenuItem(text = { Text(profile) }, onClick = { onProfileSelected(index); expanded = false })
                }
            }
        }
    }
}

/**
 * @see ProfileSwitchContentPreview
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitchContent(profileSwitches: List<String>, selectedIndex: Int, onProfileSwitchSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val selectedSwitch = if (profileSwitches.isNotEmpty() && selectedIndex < profileSwitches.size) profileSwitches[selectedIndex] else ""

    Column(modifier = modifier) {
        Text(text = stringResource(UiStrings.profile_switches), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedSwitch, onValueChange = {}, readOnly = true,
                label = { Text(stringResource(CoreUiStrings.careportal_profileswitch)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                profileSwitches.forEachIndexed { index, profileSwitch ->
                    DropdownMenuItem(text = { Text(profileSwitch) }, onClick = { onProfileSwitchSelected(index); expanded = false })
                }
            }
        }
    }
}
