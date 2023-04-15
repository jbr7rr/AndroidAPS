package info.nightscout.pump.medtrum.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import info.nightscout.core.utils.fabric.FabricPrivacy
import info.nightscout.pump.medtrum.code.EventType
import info.nightscout.pump.medtrum.ui.MedtrumBaseNavigator
import info.nightscout.pump.medtrum.ui.event.SingleLiveEvent
import info.nightscout.pump.medtrum.ui.event.UIEvent
import info.nightscout.pump.medtrum.ui.viewmodel.BaseViewModel
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.pump.medtrum.MedtrumPump
import info.nightscout.pump.medtrum.code.ConnectionState
import info.nightscout.pump.medtrum.comm.enums.MedtrumPumpState
import info.nightscout.rx.AapsSchedulers
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.events.EventPumpStatusChanged
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

class MedtrumOverviewViewModel @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
    private val profileFunction: ProfileFunction,
    private val medtrumPump: MedtrumPump
) : BaseViewModel<MedtrumBaseNavigator>() {

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _eventHandler = SingleLiveEvent<UIEvent<EventType>>()
    val eventHandler: LiveData<UIEvent<EventType>>
        get() = _eventHandler

    private val _bleStatus = SingleLiveEvent<String>()
    val bleStatus: LiveData<String>
        get() = _bleStatus

    private val _isPatchActivated = SingleLiveEvent<Boolean>()
    val isPatchActivated: LiveData<Boolean>
        get() = _isPatchActivated

    init {
        scope.launch {
            medtrumPump.connectionStateFlow.collect { state ->
                aapsLogger.debug(LTag.PUMP, "MedtrumViewModel connectionStateFlow: $state")
                when (state) {
                    ConnectionState.CONNECTING   -> {
                        _bleStatus.postValue("{fa-bluetooth-b spin}")
                    }

                    ConnectionState.CONNECTED    -> {
                        _bleStatus.postValue("{fa-bluetooth}")
                    }

                    ConnectionState.DISCONNECTED -> {
                        _bleStatus.postValue("{fa-bluetooth-b}")
                    }
                }
            }
        }
        scope.launch {
            medtrumPump.pumpStateFlow.collect { state ->
                aapsLogger.debug(LTag.PUMP, "MedtrumViewModel pumpStateFlow: $state")
                if (state > MedtrumPumpState.EJECTED && state < MedtrumPumpState.STOPPED) {
                    _isPatchActivated.postValue(true)
                } else {
                    _isPatchActivated.postValue(false)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }

    fun onClickActivation() {
        aapsLogger.debug(LTag.PUMP, "Start Patch clicked!")
        val profile = profileFunction.getProfile()
        if (profile == null) {
            _eventHandler.postValue(UIEvent(EventType.PROFILE_NOT_SET))
        } else {
            _eventHandler.postValue(UIEvent(EventType.ACTIVATION_CLICKED))
        }
    }

    fun onClickDeactivation() {
        aapsLogger.debug(LTag.PUMP, "Stop Patch clicked!")
        _eventHandler.postValue(UIEvent(EventType.DEACTIVATION_CLICKED))
    }
}