package app.aaps.core.nssdk.remotemodel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire representation of an insulin action model on [RemoteICfg].
 *
 * Every field carries a default so older or partial documents never fail decoding a treatments page.
 */
@Serializable
data class RemoteInsulinModel(
    @SerialName("type") val type: String = "BILINEAR",
    @SerialName("a0") val a0: Double? = null,
    @SerialName("a1") val a1: Double? = null,
    @SerialName("b1") val b1: Double? = null,
    @SerialName("concentrationScalesPeak") val concentrationScalesPeak: Boolean? = null,
    @SerialName("durationMinutes") val durationMinutes: Int? = null
)
