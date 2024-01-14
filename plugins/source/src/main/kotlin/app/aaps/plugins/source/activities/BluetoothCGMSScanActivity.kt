package app.aaps.plugins.source.activities

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.app.ActivityCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.extensions.safeEnable
import app.aaps.plugins.source.R
import app.aaps.plugins.source.databinding.BluetoothCgmsScannerActivityBinding
import javax.inject.Inject

class BluetoothCGMSScanActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var sp: SP
    @Inject lateinit var blePreCheck: BlePreCheck
    @Inject lateinit var context: Context
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var aapsLogger: AAPSLogger

    private var listAdapter: ListAdapter? = null
    private val devices = ArrayList<BluetoothDeviceItem>()
    private val bluetoothAdapter: BluetoothAdapter? get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private lateinit var binding: BluetoothCgmsScannerActivityBinding

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = BluetoothCgmsScannerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        title = rh.gs(R.string.bluetooth_cgms_pairing)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        blePreCheck.prerequisitesCheck(this)

        listAdapter = ListAdapter()
        binding.bleScannerListview.emptyView = binding.bleScannerNoDevice
        binding.bleScannerListview.adapter = listAdapter
        listAdapter?.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.safeEnable()
            startScan()
        } else {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            stopScan()
        }
    }

    private fun startScan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try {
                val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("0000181f-0000-1000-8000-00805f9b34fb")).build())
                val settings = ScanSettings.Builder().build()
                bluetoothLeScanner?.startScan(filters, settings, mBleScanCallback)
            } catch (ignore: IllegalStateException) {
                // Handle the exception
            }
        } else {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
        }
    }

    private fun stopScan() =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try {
                bluetoothLeScanner?.stopScan(mBleScanCallback)
            } catch (ignore: IllegalStateException) {
            } // ignore BT not on
        } else {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
        }

    @SuppressLint("MissingPermission")
    private fun addBleDevice(device: BluetoothDevice?) {
        if (device == null || device.name.isNullOrEmpty()) {
            return
        }
        val item = BluetoothDeviceItem(device)
        if (!devices.any { it.device.address == device.address }) {
            devices.add(item)
            Handler(Looper.getMainLooper()).post { listAdapter?.notifyDataSetChanged() }
        }
    }

    private val mBleScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addBleDevice(result.device)
        }
    }

    internal inner class ListAdapter : BaseAdapter() {

        override fun getCount(): Int = devices.size
        override fun getItem(i: Int): BluetoothDeviceItem = devices[i]
        override fun getItemId(i: Int): Long = 0

        override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
            var v = convertView
            val holder: ViewHolder
            if (v == null) {
                v = View.inflate(applicationContext, R.layout.bluetooth_cgms_scanner_item, null)
                holder = ViewHolder(v)
                v.tag = holder
            } else {
                // reuse view if already exists
                holder = v.tag as ViewHolder
            }
            val item = getItem(i)
            aapsLogger.debug(LTag.BGSOURCE, "Device: " + item.device.name + " " + item.device.address)
            holder.setData(item)
            return v!!
        }

        private inner class ViewHolder(v: View) : View.OnClickListener {

            private lateinit var item: BluetoothDeviceItem
            private val name: TextView = v.findViewById(R.id.ble_name)
            private val address: TextView = v.findViewById(R.id.ble_address)

            init {
                v.setOnClickListener(this@ViewHolder)
            }

            override fun onClick(v: View) {
                aapsLogger.debug(LTag.BGSOURCE, "Device: " + item.device.name + " " + item.device.address)
                sp.putString(app.aaps.plugins.source.R.string.key_bluetooth_cgms_name, item.device.name)
                sp.putString(app.aaps.plugins.source.R.string.key_bluetooth_cgms_address, item.device.address)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    item.device.createBond()
                } else {
                    ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
                }
                finish()
            }

            fun setData(data: BluetoothDeviceItem) {
                var tTitle = data.device.name ?: "(unknown)" 
                if (tTitle.isNotEmpty() && tTitle.length > 10) {
                    tTitle = tTitle.substring(0, 10)
                }
                aapsLogger.debug(LTag.BGSOURCE, "setData: Device: " + tTitle + " " + data.device.address)
                name.text = tTitle
                address.text = data.device.address
                item = data
            }
        }
    }

    inner class BluetoothDeviceItem internal constructor(val device: BluetoothDevice) {

        override fun equals(other: Any?): Boolean {
            if (other !is BluetoothDeviceItem) {
                return false
            }
            return device.address == other.device.address
        }

        override fun hashCode(): Int = device.hashCode()
    }
}
