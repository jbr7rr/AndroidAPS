package app.aaps.plugins.source.bluetoothcgms

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import app.aaps.core.data.configuration.Constants.MMOLL_TO_MGDL
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.plugins.source.R
import app.aaps.plugins.source.bluetoothcgms.extension.sFloatToFloat
import app.aaps.plugins.source.bluetoothcgms.extension.toInt
import dagger.android.DaggerService
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import no.nordicsemi.android.ble.observer.ConnectionObserver

enum class ConnectionState {
    CONNECTED,
    DISCONNECTED,
    CONNECTING,
    DISCONNECTING;
}

class CGMSService : DaggerService() {

    @Inject lateinit var injector: HasAndroidInjector
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var sp: SP
    @Inject lateinit var context: Context
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction

    private var bleManager: CgmsBLEManager? = null

    private val disposable = CompositeDisposable()
    private val mBinder: IBinder = LocalBinder()
    private val bluetoothAdapter: BluetoothAdapter? get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private val mConnectionObserver = LocalConnectionObserver()
    private val mCGMSCallback = LocalCGMSCallback()

    // Connection state flow
    private val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionStateFlow: StateFlow<ConnectionState> = connectionState

    override fun onCreate() {
        super.onCreate()
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event ->
                           if (event.isChanged(rh.gs(R.string.key_bluetooth_cgms_address))) {
                               connect(sp.getString(R.string.key_bluetooth_cgms_address, ""))
                           }
                       }, fabricPrivacy::logException)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    fun startSensor() {
        aapsLogger.debug(LTag.BGSOURCE, "Starting sensor")
        if (bleManager?.isConnected == false) {
            aapsLogger.debug(LTag.BGSOURCE, "Not connected")
            connect(sp.getString(R.string.key_bluetooth_cgms_address, ""))
        }
        bleManager?.startSensor()
    }

    fun stopSensor() {
        aapsLogger.debug(LTag.BGSOURCE, "Stopping sensor")
        bleManager?.stopSensor()
        sp.remove(R.string.key_bluetooth_cgms_starttime)
    }

    fun sendCalibration(bg: Double): Boolean {
        val bgMgdl = fromUnitsToMgdl(bg)
        val startTime = if (sp.contains(R.string.key_bluetooth_cgms_starttime)) {
            sp.getLong(R.string.key_bluetooth_cgms_starttime, 0)
        } else {
            return false
        }
        val timeOffsetMinutes = ((dateUtil.now() - startTime) / T.mins(1).msecs()).toInt()
        aapsLogger.debug(LTag.BGSOURCE, "Sending calibration: $bgMgdl, $timeOffsetMinutes")
        bleManager?.sendCalibration(bgMgdl, timeOffsetMinutes)
        return true
    }

    fun initialize() {
        aapsLogger.debug(LTag.BGSOURCE, "initialize")
        bleManager = CgmsBLEManager(aapsLogger, context)
        bleManager?.connectionObserver = mConnectionObserver
        bleManager?.setCallback(mCGMSCallback)
        connect(sp.getString(R.string.key_bluetooth_cgms_address, ""))
    }

    fun connect(address: String?) {
        aapsLogger.debug(LTag.BGSOURCE, "connect")
        if (address != null && address != "") {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            if (device == null) {
                aapsLogger.error(LTag.BGSOURCE, "Device not found.  Unable to connect.")
                return
            }
            bleManager?.connect(device)?.retry(3, 100)?.timeout(20000)?.useAutoConnect(true)?.enqueue()
        }
    }

    private fun fromUnitsToMgdl(value: Double): Double =
        if (profileFunction.getUnits() == GlucoseUnit.MGDL) value else value * MMOLL_TO_MGDL

    /** Service stuff */
    inner class LocalBinder : Binder() {

        val serviceInstance: CGMSService
            get() = this@CGMSService
    }

    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return Service.START_STICKY
    }

    /** ConnectionObserver stuff */
    inner class LocalConnectionObserver : ConnectionObserver {

        override fun onDeviceConnecting(device: BluetoothDevice) {
            aapsLogger.debug(LTag.BGSOURCE, "onDeviceConnecting")
            connectionState.value = ConnectionState.CONNECTING
        }

        override fun onDeviceConnected(device: BluetoothDevice) {
            aapsLogger.debug(LTag.BGSOURCE, "onDeviceConnected")
            connectionState.value = ConnectionState.CONNECTED
        }

        override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
            aapsLogger.debug(LTag.BGSOURCE, "onDeviceFailedToConnect")
            connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onDeviceReady(device: BluetoothDevice) {
            aapsLogger.debug(LTag.BGSOURCE, "onDeviceReady")
        }

        override fun onDeviceDisconnecting(device: BluetoothDevice) {
            aapsLogger.debug(LTag.BGSOURCE, "onDeviceDisconnecting")
            connectionState.value = ConnectionState.DISCONNECTING
        }

        override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
            aapsLogger.debug(LTag.BGSOURCE, "onDeviceDisconnected")
            connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    inner class LocalCGMSCallback : CGMSCallback {

        override fun onCGMDataReceived(data: ByteArray?) {
            val size = data?.get(0)?.toInt() ?: 0
            val flags = data?.get(1)?.toInt() ?: 0
            val glucoseConcentration = (data?.copyOfRange(2, 4)?.sFloatToFloat()) ?: 0.0
            val timeOffsetMinutes = data?.copyOfRange(4, 6)?.toInt() ?: 0
            aapsLogger.debug(LTag.BGSOURCE, "Received CGM data: $size, $flags, $glucoseConcentration, $timeOffsetMinutes")

            val timestamp = if (sp.contains(R.string.key_bluetooth_cgms_starttime)) {
                sp.getLong(R.string.key_bluetooth_cgms_starttime, 0) + T.mins(timeOffsetMinutes.toLong()).msecs()
            } else {
                aapsLogger.debug(LTag.BGSOURCE, "No start time found")
                dateUtil.now()
            }
            // val timestamp = dateUtil.now()

            if (glucoseConcentration.toDouble() > 30.0) {
                val glucoseValues = mutableListOf<GV>()
                glucoseValues += GV(
                    timestamp = timestamp,
                    value = glucoseConcentration.toDouble(),
                    raw = null,
                    noise = null,
                    trendArrow = TrendArrow.NONE,
                    sourceSensor = SourceSensor.UNKNOWN
                )
                persistenceLayer.insertCgmSourceData(Sources.BG, glucoseValues, emptyList(), null)
                    .doOnError { aapsLogger.debug(LTag.BGSOURCE, "Error inserting CGM data") }
                    .blockingGet()
            }
        }

        override fun onCGMSessionStarted() {
            aapsLogger.debug(LTag.BGSOURCE, "onCGMSessionStarted")
            sp.putLong(R.string.key_bluetooth_cgms_starttime, dateUtil.now())
        }
    }
}
