package app.aaps.plugins.source

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.plugins.source.bluetoothcgms.CGMSService
import app.aaps.plugins.source.bluetoothcgms.ConnectionState
import app.aaps.plugins.source.databinding.BluetoothCgmsFragmentBinding
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject
import dagger.android.support.DaggerFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BluetoothCGMSFragment : DaggerFragment() {

    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var bluetoothCGMSPlugin: BluetoothCGMSPlugin

    private val cgmService: CGMSService?
        get() = bluetoothCGMSPlugin.getCGMSService()

    private val disposable = CompositeDisposable()
    private var _binding: BluetoothCgmsFragmentBinding? = null
    private var pumpStatusIcon = "{fa-bluetooth-b}"

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        disposable.clear()
        scope.cancel()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BluetoothCgmsFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scope.launch {
            cgmService?.connectionStateFlow?.collect { state ->
                aapsLogger.debug(LTag.BGSOURCE, "Connection state: $state")
                updateConnectionState(state)
            }
        }
        scope.launch {
            cgmService?.sensorStartedFlow?.collect { state ->
                aapsLogger.debug(LTag.BGSOURCE, "Sensor state: $state")
                updateSensorState(state)
            }
        }
        scope.launch {
            cgmService?.lastReadingFlow?.collect { reading ->
                aapsLogger.debug(LTag.BGSOURCE, "Last reading: $reading")
                updateLastReading(reading)
            }
        }

        binding.startSensor.setOnClickListener {
            bluetoothCGMSPlugin.startSensor()
        }
        binding.stopSensor.setOnClickListener {
            bluetoothCGMSPlugin.stopSensor()
        }

        updateConnectionState(cgmService?.connectionStateFlow?.value ?: ConnectionState.DISCONNECTED)
        updateSensorState(cgmService?.sensorStartedFlow?.value ?: false)
    }

    private fun updateConnectionState(state: ConnectionState) {
        pumpStatusIcon = when (state) {
            // TODO: Hardcoded strings
            ConnectionState.CONNECTED     -> "{fa-bluetooth} Connected"
            ConnectionState.DISCONNECTED  -> "{fa-bluetooth-b} Disconnected"
            ConnectionState.CONNECTING    -> "{fa-bluetooth-b spin}"
            ConnectionState.DISCONNECTING -> "{fa-bluetooth-b spin}"
        }
        binding.bleStatus.text = pumpStatusIcon
    }

    private fun updateSensorState(state: Boolean) {
        // TODO: Hardcoded strings
        binding.sensorStatus.text = if (state) "Sensor started" else "Sensor stopped"
    }
    
    private fun updateLastReading(reading: Double) {
        binding.lastReading.text = String.format("%.1f %s", reading, rh.gs(app.aaps.core.ui.R.string.mgdl))
    }
}
