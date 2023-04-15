package info.nightscout.pump.medtrum

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dagger.android.HasAndroidInjector
import info.nightscout.core.ui.toast.ToastUtils
import info.nightscout.core.utils.fabric.FabricPrivacy
import info.nightscout.interfaces.notifications.Notification
import info.nightscout.interfaces.plugin.PluginDescription
import info.nightscout.interfaces.plugin.PluginType
import info.nightscout.interfaces.profile.Profile
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.interfaces.pump.DetailedBolusInfo
import info.nightscout.interfaces.pump.Pump
import info.nightscout.interfaces.pump.PumpEnactResult
import info.nightscout.interfaces.pump.PumpPluginBase
import info.nightscout.interfaces.pump.PumpSync
import info.nightscout.interfaces.pump.actions.CustomAction
import info.nightscout.interfaces.pump.actions.CustomActionType
import info.nightscout.interfaces.pump.defs.ManufacturerType
import info.nightscout.interfaces.pump.defs.PumpDescription
import info.nightscout.interfaces.pump.defs.PumpType
import info.nightscout.interfaces.queue.CommandQueue
import info.nightscout.interfaces.queue.CustomCommand
import info.nightscout.interfaces.ui.UiInteraction
import info.nightscout.interfaces.utils.TimeChangeType
import info.nightscout.pump.medtrum.comm.enums.MedtrumPumpState
import info.nightscout.pump.medtrum.ui.MedtrumOverviewFragment
import info.nightscout.pump.medtrum.services.MedtrumService
import info.nightscout.rx.AapsSchedulers
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.events.EventAppExit
import info.nightscout.rx.events.EventAppInitialized
import info.nightscout.rx.events.EventDismissNotification
import info.nightscout.rx.events.EventOverviewBolusProgress
import info.nightscout.rx.events.EventPreferenceChange
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.shared.sharedPreferences.SP
import info.nightscout.shared.utils.DateUtil
import info.nightscout.shared.utils.T
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class MedtrumPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    commandQueue: CommandQueue,
    private val sp: SP,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    private val context: Context,
    private val fabricPrivacy: FabricPrivacy,
    private val dateUtil: DateUtil,
    private val pumpSync: PumpSync,
    private val medtrumPump: MedtrumPump,
    private val uiInteraction: UiInteraction,
    private val profileFunction: ProfileFunction
) : PumpPluginBase(
    PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(MedtrumOverviewFragment::class.java.name)
        .pluginIcon(info.nightscout.core.ui.R.drawable.ic_eopatch2_128) // TODO
        .pluginName(R.string.medtrum)
        .shortName(R.string.medtrum_pump_shortname)
        .preferencesId(R.xml.pref_medtrum_pump)
        .description(R.string.medtrum_pump_description), injector, aapsLogger, rh, commandQueue
), Pump {

    private val disposable = CompositeDisposable()
    private var medtrumService: MedtrumService? = null

    override fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "MedtrumPlugin onStart()")
        val intent = Intent(context, MedtrumService::class.java)
        context.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ context.unbindService(mConnection) }, fabricPrivacy::logException)
    }

    override fun onStop() {
        aapsLogger.debug(LTag.PUMP, "MedtrumPlugin onStop()")
        context.unbindService(mConnection)
        disposable.clear()
        super.onStop()
    }

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.PUMP, "Service is disconnected")
            medtrumService = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.PUMP, "Service is connected")
            val mLocalBinder = service as MedtrumService.LocalBinder
            medtrumService = mLocalBinder.serviceInstance
        }
    }

    fun getService(): MedtrumService? {
        return medtrumService
    }

    override fun isInitialized(): Boolean {
        return medtrumPump.pumpState > MedtrumPumpState.EJECTED && medtrumPump.pumpState < MedtrumPumpState.STOPPED
    }

    override fun isSuspended(): Boolean {
        return medtrumPump.pumpState < MedtrumPumpState.ACTIVE || medtrumPump.pumpState > MedtrumPumpState.ACTIVE_ALT
    }

    override fun isBusy(): Boolean {
        return false
    }

    override fun isConnected(): Boolean {
        // This is a workaround to prevent AAPS to trigger connects when we have no patch activated
        return if (!medtrumPump.patchActivated) true else medtrumService?.isConnected ?: false
    }

    override fun isConnecting(): Boolean = medtrumService?.isConnecting ?: false
    override fun isHandshakeInProgress(): Boolean = false

    override fun finishHandshaking() {
    }

    override fun connect(reason: String) {
        if (medtrumPump.patchActivated) {
            aapsLogger.debug(LTag.PUMP, "Medtrum connect - reason:$reason")
            aapsLogger.debug(LTag.PUMP, "Medtrum connect - service::$medtrumService")
            // aapsLogger.debug(LTag.PUMP, "Medtrum connect - mDeviceSN:$mDeviceSN")
            if (medtrumService != null) {
                aapsLogger.debug(LTag.PUMP, "Medtrum connect - Attempt connection!")
                val success = medtrumService?.connect(reason) ?: false
                if (!success) ToastUtils.errorToast(context, info.nightscout.core.ui.R.string.ble_not_supported_or_not_paired)
            }
        }
    }

    override fun disconnect(reason: String) {
        if (isInitialized()) {
            aapsLogger.debug(LTag.PUMP, "Medtrum disconnect from: $reason")
            medtrumService?.disconnect(reason)
        }
    }

    override fun stopConnecting() {
        medtrumService?.stopConnecting()
    }

    override fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Medtrum getPumpStatus - reason:$reason")
        if (isInitialized()) {
            medtrumService?.readPumpStatus()
        }
    }

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult {
        // New profile will be set when patch is activated
        if (!isInitialized()) return PumpEnactResult(injector).success(true).enacted(true)

        return if (medtrumService?.updateBasalsInPump(profile) == true) {
            rxBus.send(EventDismissNotification(Notification.FAILED_UPDATE_PROFILE))
            uiInteraction.addNotificationValidFor(Notification.PROFILE_SET_OK, rh.gs(info.nightscout.core.ui.R.string.profile_set_ok), Notification.INFO, 60)
            PumpEnactResult(injector).success(true).enacted(true)
        } else {
            uiInteraction.addNotification(Notification.FAILED_UPDATE_PROFILE, rh.gs(info.nightscout.core.ui.R.string.failed_update_basal_profile), Notification.URGENT)
            PumpEnactResult(injector)
        }
    }

    override fun isThisProfileSet(profile: Profile): Boolean {
        if (!isInitialized()) return true
        var result = false
        val profileBytes = medtrumPump.buildMedtrumProfileArray(profile)
        if (profileBytes?.size == medtrumPump.actualBasalProfile.size) {
            result = true
            for (i in profileBytes.indices) {
                if (profileBytes[i] != medtrumPump.actualBasalProfile[i]) {
                    result = false
                    break
                }
            }
        }
        return result
    }

    override fun lastDataTime(): Long {
        return medtrumPump.lastTimeReceivedFromPump * 1000L
    }

    override val baseBasalRate: Double
        get() = 0.0 // TODO

    override val reservoirLevel: Double
        get() = medtrumPump.reservoir

    override val batteryLevel: Int
        get() = 0 // TODO

    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        return PumpEnactResult(injector) // TODO
    }

    override fun stopBolusDelivering() {
        // TODO
    }

    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        return PumpEnactResult(injector) // TODO
    }

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "setTempBasalPercent - percent: $percent, durationInMinutes: $durationInMinutes, enforceNew: $enforceNew")
        return PumpEnactResult(injector).success(false).enacted(false).comment("Medtrum driver does not support percentage temp basals")
    }

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "setExtendedBolus - insulin: $insulin, durationInMinutes: $durationInMinutes")
        return PumpEnactResult(injector).success(false).enacted(false).comment("Medtrum driver does not support extended boluses")
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        return PumpEnactResult(injector) // TODO
    }

    override fun cancelExtendedBolus(): PumpEnactResult {
        return PumpEnactResult(injector) // TODO
    }

    override fun getJSONStatus(profile: Profile, profileName: String, version: String): JSONObject {
        return JSONObject() // TODO
    }

    override fun manufacturer(): ManufacturerType {
        return ManufacturerType.Medtrum
    }

    override fun model(): PumpType {
        return medtrumPump.pumpType
    }

    override fun serialNumber(): String {
        return medtrumPump.pumpSN.toString(radix = 16)
    }

    override val pumpDescription: PumpDescription
        get() = PumpDescription(medtrumPump.pumpType)

    override fun shortStatus(veryShort: Boolean): String {
        return ""// TODO
    }

    override val isFakingTempsByExtendedBoluses: Boolean = false //TODO

    override fun loadTDDs(): PumpEnactResult {
        return PumpEnactResult(injector) // TODO
    }

    override fun canHandleDST(): Boolean {
        return false
    }

    override fun getCustomActions(): List<CustomAction>? {
        return null
    }

    override fun executeCustomAction(customActionType: CustomActionType) {
    }

    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? {
        return null
    }

    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
    }

    private fun readTBR(): PumpSync.PumpState.TemporaryBasal? {
        return pumpSync.expectedPumpState().temporaryBasal // TODO
    }
}