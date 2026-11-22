package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.data.model.InsulinModelCodec
import app.aaps.core.nssdk.localmodel.treatment.NSICfg
import app.aaps.core.nssdk.localmodel.treatment.NSInsulinModel

internal fun NSICfg.toICfg(): ICfg = ICfg(
    insulinLabel = insulinLabel,
    insulinEndTime = insulinEndTime,
    insulinPeakTime = insulinPeakTime,
    concentration = concentration,
    insulinModel = insulinModel.toInsulinModel()
)

internal fun ICfg.toNSICfg(): NSICfg = NSICfg(
    insulinLabel = insulinLabel,
    insulinEndTime = insulinEndTime,
    insulinPeakTime = insulinPeakTime,
    concentration = concentration,
    insulinModel = insulinModel.toNSInsulinModel()
)

private fun NSInsulinModel?.toInsulinModel(): InsulinModel {
    val model = this ?: return InsulinModel.Bilinear
    val type = model.type.trim()
    return when {
        type.isEmpty() || type == InsulinModelCodec.TYPE_BILINEAR -> InsulinModel.Bilinear
        type == InsulinModelCodec.TYPE_PD -> InsulinModel.Pd(
            a0 = model.a0 ?: Double.NaN,
            a1 = model.a1 ?: Double.NaN,
            b1 = model.b1 ?: Double.NaN,
            concentrationScalesPeak = model.concentrationScalesPeak ?: false,
            durationMinutes = model.durationMinutes ?: 480
        )
        else -> InsulinModel.Bilinear
    }
}

private fun InsulinModel.toNSInsulinModel(): NSInsulinModel? = when (this) {
    InsulinModel.Bilinear -> null
    is InsulinModel.Pd -> NSInsulinModel(
        type = InsulinModelCodec.TYPE_PD,
        a0 = a0.takeIf { it.isFinite() },
        a1 = a1.takeIf { it.isFinite() },
        b1 = b1.takeIf { it.isFinite() },
        concentrationScalesPeak = concentrationScalesPeak,
        durationMinutes = durationMinutes
    )
}
