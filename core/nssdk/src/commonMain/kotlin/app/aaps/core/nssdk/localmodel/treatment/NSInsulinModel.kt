package app.aaps.core.nssdk.localmodel.treatment

import kotlinx.serialization.Serializable

/**
 * Optional insulin action model carried on [NSICfg]. Absent (`null`) means Bilinear.
 */
@Serializable
data class NSInsulinModel(
    val type: String = "BILINEAR",
    val a0: Double? = null,
    val a1: Double? = null,
    val b1: Double? = null,
    val concentrationScalesPeak: Boolean? = null,
    val durationMinutes: Int? = null
)
