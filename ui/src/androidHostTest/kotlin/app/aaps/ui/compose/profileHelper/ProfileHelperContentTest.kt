package app.aaps.ui.compose.profileHelper

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.ui.R
import app.aaps.ui.UiStringIds
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Robolectric composable test for [ProfileHelperContent]: renders the tab row and fires the tab-select
 *  callback (child tab content is stubbed) — headless JVM, no emulator. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProfileHelperContentTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var tab1: String
    private lateinit var tab2: String
    private lateinit var defaultProfile: String
    private lateinit var circadianProfile: String

    @Before
    fun setUp() {
        // MainApp does this in production; a Robolectric test has no MainApp, so a TextRef.Named
        // would have no id to resolve to and the screen would render blank text.
        TextRefIdRegistry.register("ui") { name -> UiStringIds.idOf(name) }
        val ctx: Context = RuntimeEnvironment.getApplication()
        tab1 = ctx.getString(R.string.profile_tab_1)
        tab2 = ctx.getString(R.string.profile_tab_2)
        defaultProfile = ctx.getString(R.string.motol_default_profile)
        circadianProfile = ctx.getString(R.string.circadian_default_profile)
    }

    @Test
    fun rendersTabsAndFiresTabSelected() {
        var selectedIdx = -1
        compose.setContent {
            MaterialTheme {
                ProfileHelperContent(
                    selectedTab = 0,
                    onTabSelected = { selectedIdx = it },
                    profileTypes = listOf(ProfileType.MOTOL_DEFAULT, ProfileType.CURRENT),
                    onProfileTypeChange = { _, _ -> },
                    isCompareTabValid = false,
                    showCloneAction = false,
                    onCloneClick = {},
                    onBackClick = {},
                    focusManager = LocalFocusManager.current,
                    comparisonContent = {},
                    profileTabContent = {}
                )
            }
        }

        compose.onNodeWithText(tab1).assertIsDisplayed()
        compose.onNodeWithText(tab2).performClick()
        assertThat(selectedIdx).isEqualTo(1)
    }

    @Test
    fun dropdownOffersTheCircadianDefaultProfile() {
        compose.setContent {
            MaterialTheme {
                ProfileHelperContent(
                    selectedTab = 0,
                    onTabSelected = {},
                    profileTypes = listOf(ProfileType.MOTOL_DEFAULT, ProfileType.CURRENT),
                    onProfileTypeChange = { _, _ -> },
                    isCompareTabValid = true,
                    showCloneAction = false,
                    onCloneClick = {},
                    onBackClick = {},
                    focusManager = LocalFocusManager.current,
                    comparisonContent = {},
                    profileTabContent = {}
                )
            }
        }

        compose.onNodeWithText(defaultProfile).performClick()
        compose.onNodeWithText(circadianProfile).assertIsDisplayed()
    }
}
