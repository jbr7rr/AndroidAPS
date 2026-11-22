package app.aaps.core.interfaces.insulin

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef

enum class InsulinType(
    val value: Int,
    val insulinEndTime: Long,
    val insulinPeakTime: Long,
    val label: TextRef,
    val comment: TextRef,
    val insulinModel: InsulinModel = InsulinModel.Bilinear
) {
    UNKNOWN(-1, 0, 0, InterfacesStrings.unknown, InterfacesStrings.unknown),

    // int FAST_ACTING_INSULIN = 0; // old model no longer available
    // int FAST_ACTING_INSULIN_PROLONGED = 1; // old model no longer available
    OREF_RAPID_ACTING(2, 8 * 3600 * 1000L, 75 * 60000L, InterfacesStrings.rapid_acting_oref, InterfacesStrings.fast_acting_insulin_comment),
    OREF_ULTRA_RAPID_ACTING(3, 8 * 3600 * 1000L, 55 * 60000L, InterfacesStrings.ultra_rapid_oref, InterfacesStrings.ultra_fast_acting_insulin_comment),
    OREF_FREE_PEAK(4, 8 * 3600 * 1000L, 50 * 60000L, InterfacesStrings.free_peak_oref, InterfacesStrings.insulin_peak_time),
    OREF_LYUMJEV(5, 8 * 3600 * 1000L, 45 * 60000L, InterfacesStrings.lyumjev, InterfacesStrings.lyumjev),
    CUSTOM_PD(
        value = 6,
        insulinEndTime = 8 * 3600 * 1000L,
        insulinPeakTime = 51 * 60000L,
        label = InterfacesStrings.custom_pd,
        comment = InterfacesStrings.custom_pd_comment,
        insulinModel = InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false, durationMinutes = 480)
    ),
    LYUMJEV_PD(
        value = 7,
        insulinEndTime = 8 * 3600 * 1000L,
        insulinPeakTime = 65 * 60000L,
        label = InterfacesStrings.lyumjev_pd,
        comment = InterfacesStrings.lyumjev_pd_comment,
        insulinModel = InsulinModel.Pd(61.33, 12.27, 0.05185, concentrationScalesPeak = true, durationMinutes = 480)
    );

    val iCfg: ICfg
        get() = ICfg(this.name, insulinEndTime, insulinPeakTime, 1.0, insulinModel)

    /** Provide iCfg with a default friendly name on insulin creation from template */
    fun getICfg(rh: TextResolver): ICfg = ICfg(rh.gs(this.label), insulinEndTime, insulinPeakTime, 1.0, insulinModel)

    companion object {

        private val map = entries.associateBy(InsulinType::value)
        fun fromInt(type: Int) = map[type] ?: OREF_RAPID_ACTING
        fun fromPeak(insulinPeakTime: Long) =
            entries.firstOrNull { it.insulinModel == InsulinModel.Bilinear && it.insulinPeakTime == insulinPeakTime } ?: OREF_FREE_PEAK

        /** Model-aware preset lookup so PD insulins are never mislabeled as Free-Peak Oref. */
        fun fromICfg(iCfg: ICfg): InsulinType = when (val model = iCfg.insulinModel) {
            is InsulinModel.Pd -> if (model.concentrationScalesPeak) LYUMJEV_PD else CUSTOM_PD
            InsulinModel.Bilinear -> fromPeak(iCfg.insulinPeakTime)
        }
    }
}
