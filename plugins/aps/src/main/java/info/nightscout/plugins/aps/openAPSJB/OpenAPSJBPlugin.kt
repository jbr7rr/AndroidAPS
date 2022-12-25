package info.nightscout.plugins.aps.openAPSJB

import android.content.Context
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.annotations.OpenForTesting
import info.nightscout.database.impl.AppRepository
import info.nightscout.interfaces.Config
import info.nightscout.interfaces.aps.DetermineBasalAdapter
import info.nightscout.interfaces.bgQualityCheck.BgQualityCheck
import info.nightscout.interfaces.constraints.Constraints
import info.nightscout.interfaces.iob.GlucoseStatusProvider
import info.nightscout.interfaces.iob.IobCobCalculator
import info.nightscout.interfaces.plugin.ActivePlugin
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.interfaces.profiling.Profiler
import info.nightscout.interfaces.utils.HardLimits
import info.nightscout.plugins.aps.R
import info.nightscout.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import info.nightscout.plugins.aps.utils.ScriptReader
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.shared.sharedPreferences.SP
import info.nightscout.shared.utils.DateUtil
import javax.inject.Inject
import javax.inject.Singleton

@OpenForTesting
@Singleton
class OpenAPSJBPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rxBus: RxBus,
    constraintChecker: Constraints,
    rh: ResourceHelper,
    profileFunction: ProfileFunction,
    context: Context,
    activePlugin: ActivePlugin,
    iobCobCalculator: IobCobCalculator,
    hardLimits: HardLimits,
    profiler: Profiler,
    sp: SP,
    dateUtil: DateUtil,
    repository: AppRepository,
    glucoseStatusProvider: GlucoseStatusProvider,
    private val config: Config,
    private val bgQualityCheck: BgQualityCheck
) : OpenAPSSMBPlugin(
    injector,
    aapsLogger,
    rxBus,
    constraintChecker,
    rh,
    profileFunction,
    context,
    activePlugin,
    iobCobCalculator,
    hardLimits,
    profiler,
    sp,
    dateUtil,
    repository,
    glucoseStatusProvider,
    bgQualityCheck
) {

    init {
        pluginDescription
            .pluginName(R.string.openaps_jb)
            .description(R.string.description_jb)
            .shortName(R.string.jb_shortname)
            .preferencesId(R.xml.pref_openapsjb)
            .setDefault(false)
            .showInList(config.isEngineeringMode() && config.isDev())
    }

    override fun specialEnableCondition(): Boolean = config.isEngineeringMode() && config.isDev()

    override fun provideDetermineBasalAdapter(): DetermineBasalAdapter = DetermineBasalAdapterJBJS(ScriptReader(context), injector)
}