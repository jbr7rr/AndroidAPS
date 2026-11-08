package app.aaps.core.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.aaps.core.keys.interfaces.TextRef
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The empty-field contract of [NumberInputRow].
 *
 * Zero is the "no value" sentinel and renders as an empty field. Clearing a field must publish
 * that zero even when the range does not contain it; otherwise a user who tabs through a form
 * without typing silently receives `valueRange.start` as a real value.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NumberInputRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setRow(valueRange: ClosedFloatingPointRange<Double>, onValueChange: (Double) -> Unit) {
        compose.setContent {
            MaterialTheme {
                NumberInputRow(
                    labelRef = TextRef.Literal("ISF"),
                    value = 0.0,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    step = 1.0
                )
            }
        }
    }

    @Test
    fun clearingTheFieldPublishesZeroInsteadOfTheRangeMinimum() {
        val changes = mutableListOf<Double>()
        setRow(2.0..1000.0) { changes.add(it) }

        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput("5")
        field.performTextClearance()
        field.performImeAction()

        assertThat(changes.last()).isEqualTo(0.0)
    }

    @Test
    fun anOutOfRangeTypedValueStillClampsToTheRange() {
        val changes = mutableListOf<Double>()
        setRow(2.0..1000.0) { changes.add(it) }

        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput("1")
        field.performImeAction()

        assertThat(changes.last()).isEqualTo(2.0)
    }
}
