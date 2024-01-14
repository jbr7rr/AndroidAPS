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

    private val scope = CoroutineScope(Dispatchers.Default)

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

        binding.startSensor.setOnClickListener {
            bluetoothCGMSPlugin.startSensor()
        }
        binding.stopSensor.setOnClickListener {
            bluetoothCGMSPlugin.stopSensor()
        }

        pumpStatusIcon = when (cgmService?.connectionStateFlow?.value) {
            ConnectionState.CONNECTED     -> "{fa-bluetooth} Connected"
            ConnectionState.DISCONNECTED  -> "{fa-bluetooth-b} Disconnected"
            ConnectionState.CONNECTING    -> "{fa-bluetooth-b spin}"
            ConnectionState.DISCONNECTING -> "{fa-bluetooth-b spin}"
            else                          -> "{fa-bluetooth-b}"
        }

        binding.bleStatus.text = pumpStatusIcon

        scope.launch {
            cgmService?.connectionStateFlow?.collect { state ->
                aapsLogger.debug(LTag.PUMP, "MedtrumViewModel connectionStateFlow: $state")
                pumpStatusIcon = when (state) {
                    ConnectionState.CONNECTED     -> "{fa-bluetooth} Connected"
                    ConnectionState.DISCONNECTED  -> "{fa-bluetooth-b} Disconnected"
                    ConnectionState.CONNECTING    -> "{fa-bluetooth-b spin}"
                    ConnectionState.DISCONNECTING -> "{fa-bluetooth-b spin}"
                }
                binding.bleStatus.text = pumpStatusIcon
            }
        }
    }
}
