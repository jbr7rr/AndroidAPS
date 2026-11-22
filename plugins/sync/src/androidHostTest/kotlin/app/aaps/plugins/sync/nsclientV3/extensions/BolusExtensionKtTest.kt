package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSICfg
import app.aaps.core.nssdk.localmodel.treatment.NSInsulinModel
import app.aaps.core.nssdk.mapper.convertToRemoteAndBack
import app.aaps.plugins.sync.extensions.contentEqualsTo
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class BolusExtensionKtTest : TestBase() {


    val iCfg = ICfg(insulinLabel = "Fake", insulinEndTime = 9 * 3600 * 1000, insulinPeakTime = 60 * 60 * 1000, concentration = 1.0)

    @Test
    fun toBolus() {
        var bolus = BS(
            timestamp = 10000,
            isValid = true,
            amount = 1.0,
            type = BS.Type.SMB,
            notes = "aaaa",
            isBasalInsulin = false,
            ids = IDs(
                nightscoutId = "nightscoutId",
                pumpId = 11000,
                pumpType = PumpType.DANA_I,
                pumpSerial = "bbbb"
            ),
            iCfg = iCfg
        )

        var bolus2 = (bolus.toNSBolus().convertToRemoteAndBack() as NSBolus).toBolus(iCfg)
        assertThat(bolus.contentEqualsTo(bolus2)).isTrue()
        assertThat(bolus.ids.contentEqualsTo(bolus2.ids)).isTrue()

        bolus = BS(
            timestamp = 10000,
            isValid = false,
            amount = 1.0,
            type = BS.Type.NORMAL,
            notes = "aaaa",
            isBasalInsulin = true,
            ids = IDs(
                nightscoutId = "nightscoutId",
                pumpId = 11000,
                pumpType = PumpType.DANA_I,
                pumpSerial = "bbbb"
            ),
            iCfg = iCfg
        )

        bolus2 = (bolus.toNSBolus().convertToRemoteAndBack() as NSBolus).toBolus(iCfg)
        assertThat(bolus.contentEqualsTo(bolus2)).isTrue()
        assertThat(bolus.ids.contentEqualsTo(bolus2.ids)).isTrue()
    }

    @Test
    fun pdInsulinModelRoundTripsThroughBolusSyncMapper() {
        val pd = InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false, durationMinutes = 480)
        val pdICfg = ICfg("Custom PD 51m 8h U100", 8L * 3600 * 1000, 51L * 60 * 1000, 1.0, pd)
        val bolus = BS(
            timestamp = 10000,
            isValid = true,
            amount = 2.0,
            type = BS.Type.NORMAL,
            iCfg = pdICfg
        )

        val restored = (bolus.toNSBolus().convertToRemoteAndBack() as NSBolus).toBolus(iCfg)

        assertThat(restored.iCfg).isEqualTo(pdICfg)
        assertThat(bolus.contentEqualsTo(restored)).isTrue()
    }

    @Test
    fun invalidPdInSyncedBolusStaysUnusable() {
        val ns = BS(timestamp = 10000, isValid = true, amount = 1.0, type = BS.Type.NORMAL, iCfg = iCfg).toNSBolus().copy(
            iCfg = NSICfg(
                insulinLabel = "Broken PD",
                insulinEndTime = 8L * 3600 * 1000,
                insulinPeakTime = 51L * 60 * 1000,
                concentration = 1.0,
                insulinModel = NSInsulinModel(type = "PD", a0 = 0.0, a1 = 12.27, b1 = 0.05185)
            )
        )

        val restored = ns.toBolus(iCfg)

        assertThat(restored.iCfg.insulinModel).isInstanceOf(InsulinModel.Pd::class.java)
        assertThat(restored.iCfg.isUsable).isFalse()
    }
}
