package app.aaps.core.nssdk.mapper

import app.aaps.core.nssdk.localmodel.treatment.NSICfg
import app.aaps.core.nssdk.localmodel.treatment.NSInsulinModel
import app.aaps.core.nssdk.remotemodel.RemoteICfg
import app.aaps.core.nssdk.remotemodel.RemoteInsulinModel

internal fun RemoteICfg?.toNSICfg(): NSICfg? {

    this ?: return null

    return NSICfg(
        insulinLabel = insulinLabel,
        insulinEndTime = insulinEndTime,
        insulinPeakTime = insulinPeakTime,
        concentration = concentration,
        insulinModel = insulinModel.toNSInsulinModel()
    )
}

internal fun NSICfg?.toRemoteICfg(): RemoteICfg? {

    this ?: return null

    return RemoteICfg(
        insulinLabel = insulinLabel,
        insulinEndTime = insulinEndTime,
        insulinPeakTime = insulinPeakTime,
        concentration = concentration,
        insulinModel = insulinModel.toRemoteInsulinModel()
    )
}

internal fun RemoteInsulinModel?.toNSInsulinModel(): NSInsulinModel? {
    this ?: return null
    return NSInsulinModel(
        type = type,
        a0 = a0,
        a1 = a1,
        b1 = b1,
        concentrationScalesPeak = concentrationScalesPeak,
        durationMinutes = durationMinutes
    )
}

internal fun NSInsulinModel?.toRemoteInsulinModel(): RemoteInsulinModel? {
    this ?: return null
    return RemoteInsulinModel(
        type = type,
        a0 = a0,
        a1 = a1,
        b1 = b1,
        concentrationScalesPeak = concentrationScalesPeak,
        durationMinutes = durationMinutes
    )
}