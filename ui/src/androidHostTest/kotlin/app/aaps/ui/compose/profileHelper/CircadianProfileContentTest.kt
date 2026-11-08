package app.aaps.ui.compose.profileHelper

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.ui.R
import app.aaps.ui.UiStringIds
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.interfaces.R as InterfacesR
import app.aaps.core.ui.R as CoreUiR

/**
 * Robolectric composable test for [CircadianProfileContent].
 *
 * The ISF field is the one that used to carry a unit-blind 50 default, so these tests render it
 * blank and assert that no 50 is shown in either unit, while the unit label and step come from the
 * current glucose unit.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CircadianProfileContentTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        // MainApp does this in production; a Robolectric test has no MainApp, so a TextRef.Named
        // would have no id to resolve to and the screen would render blank text.
        TextRefIdRegistry.register("ui") { name -> UiStringIds.idOf(name) }
        ctx = RuntimeEnvironment.getApplication()
    }

    private fun setContent(units: GlucoseUnit) {
        compose.setContent {
            MaterialTheme {
                CircadianProfileContent(
                    age = 0, onAgeChange = {},
                    tdd = 0.0, onTddChange = {},
                    pct = 0.0, onPctChange = {},
                    isf = 0.0, onIsfChange = {},
                    ic = 0.0, onIcChange = {},
                    timeshift = 0.0, onTimeshiftChange = {},
                    units = units
                )
            }
        }
    }

    @Test
    fun rendersAllCircadianInputsBlankInMgdl() {
        setContent(GlucoseUnit.MGDL)

        compose.onNodeWithText(ctx.getString(R.string.profile_parameters)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.age)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(CoreUiR.string.tdd_total)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.basal_pct_from_tdd_label)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(CoreUiR.string.isf_long_label)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(CoreUiR.string.ic_long_label)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(CoreUiR.string.timeshift_label)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(InterfacesR.string.profile_isf_units_mgdl)).assertIsDisplayed()
        // The old helper initialised ISF to 50.0 and showed it as 50 mmol/L too.
        compose.onNodeWithText("50").assertDoesNotExist()
    }

    @Test
    fun rendersTheMmolUnitAndStep() {
        setContent(GlucoseUnit.MMOL)

        compose.onNodeWithText(ctx.getString(InterfacesR.string.profile_isf_units_mmol)).assertIsDisplayed()
        compose.onNodeWithText("50").assertDoesNotExist()
    }
}
