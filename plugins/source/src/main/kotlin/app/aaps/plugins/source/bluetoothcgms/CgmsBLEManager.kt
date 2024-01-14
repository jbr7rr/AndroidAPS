package app.aaps.plugins.source.bluetoothcgms

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.source.bluetoothcgms.extension.toByteArray
import no.nordicsemi.android.ble.BleManager
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

interface CGMSCallback {

    fun onCGMDataReceived(data: ByteArray?)
    fun onCGMSessionStarted()
}

@Singleton
class CgmsBLEManager @Inject internal constructor(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
) : BleManager(context) {

    // CGM Service & Characteristics
    private val _cgmServiceUuid = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
    private val _cgmMeasurementUuid = UUID.fromString("00002aa7-0000-1000-8000-00805f9b34fb")
    private val _cgmFeatureUuid = UUID.fromString("00002aa8-0000-1000-8000-00805f9b34fb")
    private val _cgmRacpUuid = UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")
    private val _cgmSessionStartTimeUuid = UUID.fromString("00002aaa-0000-1000-8000-00805f9b34fb")
    private val _cgmSessionRuntimeUuid = UUID.fromString("00002aab-0000-1000-8000-00805f9b34fb")
    private val _cgmControlPointUuid = UUID.fromString("00002aac-0000-1000-8000-00805f9b34fb")

    private var _cgmMeasurementChar: BluetoothGattCharacteristic? = null
    private var _cgmFeatureChar: BluetoothGattCharacteristic? = null
    private var _cgmRacpChar: BluetoothGattCharacteristic? = null
    private var _cgmSessionStartTimeChar: BluetoothGattCharacteristic? = null
    private var _cgmSessionRuntimeChar: BluetoothGattCharacteristic? = null
    private var _cgmControlPointChar: BluetoothGattCharacteristic? = null

    private var mCallback: CGMSCallback? = null
    fun setCallback(callback: CGMSCallback?) {
        this.mCallback = callback
    }

    override fun initialize() {
        aapsLogger.debug(LTag.BGSOURCE, "BLEManager initialize")
        beginAtomicRequestQueue()
            .add(enableNotifications(_cgmMeasurementChar))
            .done {
                aapsLogger.debug(LTag.BGSOURCE, "Initialized CGMS BLEManager")
            }
            .enqueue()
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        aapsLogger.debug(LTag.BGSOURCE, "isRequiredServiceSupported")
        val cgmService = gatt.getService(_cgmServiceUuid)

        if (cgmService != null) {
            aapsLogger.debug(LTag.BGSOURCE, "Required Services found, initializing characteristics")
            _cgmMeasurementChar = cgmService.getCharacteristic(_cgmMeasurementUuid)
            _cgmFeatureChar = cgmService.getCharacteristic(_cgmFeatureUuid)
            _cgmRacpChar = cgmService.getCharacteristic(_cgmRacpUuid)
            _cgmSessionStartTimeChar = cgmService.getCharacteristic(_cgmSessionStartTimeUuid)
            _cgmSessionRuntimeChar = cgmService.getCharacteristic(_cgmSessionRuntimeUuid)
            _cgmControlPointChar = cgmService.getCharacteristic(_cgmControlPointUuid)

            setNotificationCallback(_cgmMeasurementChar).with { device, data ->
                aapsLogger.debug(LTag.BGSOURCE, "Received CGM data: $data")
                mCallback?.onCGMDataReceived(data.value)
            }

            return true
        }
        return false
    }

    override fun onServicesInvalidated() {
        aapsLogger.debug(LTag.BGSOURCE, "onServicesInvalidated")
    }

    fun startSensor() {

        beginAtomicRequestQueue()
            // .add(writeCharacteristic(_cgmControlPointChar, byteArrayOf(0x1B), WRITE_TYPE_DEFAULT))
            // .add(writeCharacteristic(_cgmControlPointChar, byteArrayOf(0x0105), WRITE_TYPE_DEFAULT)) // Set 5 min session time for AAPS
            .add(enableNotifications(_cgmMeasurementChar))
            .add(writeCharacteristic(_cgmControlPointChar, byteArrayOf(0x1A), WRITE_TYPE_DEFAULT))
            .add(
                writeCharacteristic(
                    _cgmSessionStartTimeChar,
                    getCurrentTimeByteArray(),
                    WRITE_TYPE_DEFAULT
                ).done {
                    aapsLogger.debug(LTag.BGSOURCE, "Wrote current time to CGM session start time characteristic")
                }.fail { _, code ->
                    aapsLogger.debug(LTag.BGSOURCE, "Failed to write current time to CGM session start time characteristic: $code")
                })
            .done {
                aapsLogger.debug(LTag.BGSOURCE, "Started new CGM session")
                mCallback?.onCGMSessionStarted()
            }
            .enqueue()
    }

    fun stopSensor() {
        beginAtomicRequestQueue()
            .add(writeCharacteristic(_cgmControlPointChar, byteArrayOf(0x1B), WRITE_TYPE_DEFAULT))
            .done {
                aapsLogger.debug(LTag.BGSOURCE, "Stopped CGM session")
            }
            .enqueue()
    }

    fun sendCalibration(bg: Double, timeOffsetMinutes: Int) {
        // TODO: Proper sfloat implementation
        val exponent = 15 // This is very crude hardcoded (-1 in 4 bit two complements)
        val mantissa = (bg * 10).toInt()
        val sfloat: Int = (exponent shl 12) or mantissa
        sfloat.toByteArray(2)

        val calibrationByteArray = byteArrayOf(0x04) + sfloat.toByteArray(2) + timeOffsetMinutes.toByteArray(2) + byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        aapsLogger.debug(LTag.BGSOURCE, "Calibration byte array: ${calibrationByteArray.contentToString()}")

        beginAtomicRequestQueue()
            .add(writeCharacteristic(_cgmControlPointChar, calibrationByteArray, WRITE_TYPE_DEFAULT))
            .done {
                aapsLogger.debug(LTag.BGSOURCE, "Sent calibration to CGM")
            }
            .enqueue()
    }

    private fun getCurrentTimeByteArray(adjusted: Boolean = false): ByteArray {
        val c = GregorianCalendar.getInstance()

        val year = c.get(Calendar.YEAR)
        val yearArray = byteArrayOf(
            (year shr 24).toByte(),
            (year shr 16).toByte(),
            (year shr 8).toByte(),
            year.toByte()
        )

        var minute = c.get(Calendar.MINUTE)
        if (adjusted) {
            // Adjust for FW time correction
            // TODO: Needed?
            minute += 1
            if (minute >= 60) minute -= 60
        }

        aapsLogger.debug(LTag.BGSOURCE, "Constructed byte array with first 2 values: ${yearArray[3]}, ${yearArray[2]}")

        return byteArrayOf(
            yearArray[3],
            yearArray[2],
            (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(),
            minute.toByte(),
            c.get(Calendar.SECOND).toByte(),
            0x80.toByte(),
            0xFF.toByte()
        )
    }
}
