package app.aaps.implementation.insulin

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.InsulinType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.LongComposedKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class InsulinImplPdTest {

    private val rh = generatedTextResolver()

    private fun sut(storedJson: String = "{}"): InsulinImpl {
        val preferences = mock<Preferences>()
        val flow = MutableStateFlow(storedJson)
        whenever(preferences.get(StringNonKey.InsulinConfiguration)).thenAnswer { flow.value }
        whenever(preferences.observe(StringNonKey.InsulinConfiguration)).thenReturn(flow)
        whenever(preferences.get(eq(LongComposedKey.SyncedPrefModified), any())).thenReturn(0L)
        val config = mock<Config>()
        whenever(config.AAPSCLIENT).thenReturn(false)
        return InsulinImpl(
            preferences = preferences,
            rh = rh,
            profileFunction = mock<ProfileFunction>(),
            aapsLogger = mock<AAPSLogger>(),
            config = config,
            hardLimits = mock<HardLimits>(),
            uel = mock<UserEntryLogger>(),
            appScope = CoroutineScope(Dispatchers.Unconfined)
        )
    }

    @Test
    fun `template list includes Custom PD and Lyumjev PD without a separate U200 preset`() {
        val templates = sut().insulinTemplateList()

        assertThat(templates).containsAtLeast(InsulinType.CUSTOM_PD, InsulinType.LYUMJEV_PD)
        assertThat(templates.count { it.insulinModel is InsulinModel.Pd }).isEqualTo(2)
    }

    @Test
    fun `adding PD insulins with blank nickname does not fall back to Free Peak`() {
        val manager = sut()
        val custom = ICfg(
            insulinLabel = "",
            insulinEndTime = 8L * 3600 * 1000,
            insulinPeakTime = 51L * 60 * 1000,
            concentration = 1.0,
            insulinModel = InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false)
        )
        val lyumjev = ICfg(
            insulinLabel = "",
            insulinEndTime = 8L * 3600 * 1000,
            insulinPeakTime = 65L * 60 * 1000,
            concentration = 2.0,
            insulinModel = InsulinModel.Pd(61.33, 12.27, 0.05185, concentrationScalesPeak = true)
        )

        val addedCustom = manager.addNewInsulin(custom)
        val addedLyumjev = manager.addNewInsulin(lyumjev)

        assertThat(addedCustom.insulinNickname).isEqualTo(rh.gs(InsulinType.CUSTOM_PD.label))
        assertThat(addedLyumjev.insulinNickname).isEqualTo(rh.gs(InsulinType.LYUMJEV_PD.label))
        assertThat(addedCustom.insulinNickname).isNotEqualTo(rh.gs(InsulinType.OREF_FREE_PEAK.label))
        assertThat(addedLyumjev.insulinNickname).isNotEqualTo(rh.gs(InsulinType.OREF_FREE_PEAK.label))
    }
}
