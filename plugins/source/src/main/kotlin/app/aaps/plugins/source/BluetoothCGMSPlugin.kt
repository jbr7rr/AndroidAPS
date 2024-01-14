package app.aaps.plugins.source

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.aaps.core.data.plugin.PluginDescription
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.CGMSSource
import app.aaps.plugins.source.bluetoothcgms.CGMSService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothCGMSPlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val context: Context
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BluetoothCGMSFragment::class.java.name)
        .pluginIcon((app.aaps.core.objects.R.drawable.ic_blooddrop_48))
        .preferencesId(R.xml.pref_bluetoothcgms)
        .pluginName(R.string.bluetooth_cgms)
        .shortName(R.string.bluetooth_cgms_short)
        .preferencesVisibleInSimpleMode(true)
        .description(R.string.description_bluetooth_cgms),
    aapsLogger, rh
), BgSource, CGMSSource {

    private var advancedFiltering = true
    private var cgmsService: CGMSService? = null
    override var sensorBatteryLevel = -1

    override fun advancedFilteringSupported(): Boolean = advancedFiltering

    override fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.BGSOURCE, "onStart")
        val intent = Intent(context, CGMSService::class.java)
        context.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        context.unbindService(mConnection)
    }

    fun getCGMSService(): CGMSService? {
        return cgmsService
    }

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.BGSOURCE, "Service is disconnected")
            cgmsService = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.BGSOURCE, "Service is connected")
            val mLocalBinder = service as CGMSService.LocalBinder
            cgmsService = mLocalBinder.serviceInstance
            cgmsService?.initialize()
        }
    }

    override fun sendCalibration(bg: Double): Boolean {
        return cgmsService?.sendCalibration(bg) ?: false
    }

    fun startSensor() {
        cgmsService?.startSensor()
    }

    fun stopSensor() {
        cgmsService?.stopSensor()
    }
}
