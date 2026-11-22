package app.aaps.core.graph

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class InsulinGraphKeyTest {

    @Test
    fun `graph recomposition key changes when insulin model or PD parameters change`() {
        val bilinear = ICfg("Test", 8L * 3600 * 1000, 51L * 60 * 1000, 1.0, InsulinModel.Bilinear)
        val pd1 = bilinear.copy(insulinModel = InsulinModel.Pd(51.0, 12.27, 0.05185, false, 480))
        val pd2 = bilinear.copy(insulinModel = InsulinModel.Pd(55.0, 12.27, 0.05185, false, 480))
        val pd3 = bilinear.copy(insulinModel = InsulinModel.Pd(51.0, 12.27, 0.05185, true, 480))

        val keyBilinear = insulinGraphEffectKey(bilinear, null)
        val keyPd1 = insulinGraphEffectKey(pd1, null)
        val keyPd2 = insulinGraphEffectKey(pd2, null)
        val keyPd3 = insulinGraphEffectKey(pd3, null)

        assertThat(keyBilinear).isNotEqualTo(keyPd1)
        assertThat(keyPd1).isNotEqualTo(keyPd2)
        assertThat(keyPd1).isNotEqualTo(keyPd3)
        assertThat(keyPd1).contains(pd1.insulinModel)
    }
}
