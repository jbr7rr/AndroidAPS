package app.aaps.plugins.insulin

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.iob.Iob
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.Preferences
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.pow

@Singleton
class InsulinCustomPDPlugin @Inject constructor(
    private val preferences: Preferences,
    rh: ResourceHelper,
    profileFunction: ProfileFunction,
    rxBus: RxBus,
    aapsLogger: AAPSLogger,
    config: Config,
    hardLimits: HardLimits,
    uiInteraction: UiInteraction
) : InsulinOrefBasePlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction) {

    override val id get(): Insulin.InsulinType = Insulin.InsulinType.CUSTOM_PD
    override val friendlyName get(): String = rh.gs(R.string.custom_PD)

    override fun configuration(): JSONObject = 
        JSONObject()
            .put(DoubleKey.InsulinPDFreePeakA0, preferences)
            .put(DoubleKey.InsulinPDFreePeakA1, preferences)
            .put(DoubleKey.InsulinPDFreePeakB1, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(DoubleKey.InsulinPDFreePeakA0, preferences)
            .store(DoubleKey.InsulinPDFreePeakA1, preferences)
            .store(DoubleKey.InsulinPDFreePeakB1, preferences)
    }

    override fun commentStandardText(): String {
        return rh.gs(R.string.custom_PD)
    }

    override val peak: Int = 45
    override val isPD = true

    private val a0: Double
        get() = preferences.get(DoubleKey.InsulinPDFreePeakA0)
    private val a1: Double
        get() = preferences.get(DoubleKey.InsulinPDFreePeakA1)
    private val b1: Double
        get() = preferences.get(DoubleKey.InsulinPDFreePeakB1)

    init {
        pluginDescription
            .pluginIcon(R.drawable.ic_insulin)
            .pluginName(R.string.custom_PD)
            .preferencesId(PluginDescription.PREFERENCE_SCREEN)
            .description(R.string.description_insulin_custom_PD)
    }

    override fun iobCalcForTreatment(bolus: BS, time: Long, dia: Double): Iob {
        assert(dia != 0.0)
        assert(peak != 0)
        val result = Iob()
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val td = 8 * 60.0
            val t = (time - bolusTime) / 1000.0 / 60.0
            if (t < td) {
                val PDresult = pdModelIobCalculation(bolus, t)
                result.iobContrib = PDresult.iobContrib
                result.activityContrib = PDresult.activityContrib
            }
        }
        return result
    }

    override fun iobCalcPeakForTreatment(bolus: BS, dia: Double): Iob {
        assert(dia != 0.0)
        assert(peak != 0)
        return pdModelIobCalculation(bolus, 0.0, true)
    }

    private fun pdModelIobCalculation(bolus: BS, time: Double, calcForPeak: Boolean = false): Iob {
        //MP Model for estimation of PD-based peak time: (a0 + a1*X)/(1+b1*X), where X = bolus size
        // val a0 = 61.33 //MP Units = min
        // val a1 = 12.27
        // val b1 = 0.05185
        val result = Iob()

        val tp: Double = (a0 + a1 * bolus.amount) / (1 + b1 * bolus.amount) //MP Units = min
        val t = if (calcForPeak) {
            tp
        } else {
            time
        }

        val tp_model = tp.pow(2.0) * 2 //MP The peak time in the model is defined as half of the square root of this variable - thus the tp entered into the model must be transformed first

        result.activityContrib = (2 * bolus.amount / tp_model) * t * exp(-t.pow(2.0) / tp_model)

        //MP New IOB formula - integrated version of the above activity curve
        val lowerLimit = t //MP lower integration limit, in min
        val upperLimit = 8.0 * 60 //MP upper integration limit, in min
        result.iobContrib = bolus.amount * (exp(-lowerLimit.pow(2.0)/tp_model) - exp(-upperLimit.pow(2.0)/tp_model))

        return result
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "insulin_custom_pd_settings"
            title = rh.gs(R.string.custom_PD)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.InsulinPDFreePeakA0, title = R.string.PD_a0))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.InsulinPDFreePeakA1, title = R.string.PD_a1))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.InsulinPDFreePeakB1, title = R.string.PD_b1))
        }
    }
}
