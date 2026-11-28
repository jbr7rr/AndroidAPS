package app.aaps.plugins.dexcomoneplus.identity

import app.aaps.plugins.dexcomoneplus.parse.OnePlusFastCrc16

/**
 * Reads the serial CRC-16 and "phone slot held" bit from a G7 / ONE+ advertisement.
 *
 * A G7 keeps one connection slot per kind of display. Two phones cannot both hold the phone slot,
 * so a sensor whose phone slot is held by another phone refuses us, and refusals in a row make it
 * lock itself for a while (see `OnePlusAuthRefusals`). The advertisement says whether the slot is
 * held before anyone connects, so the scan list can offer free sensors first.
 *
 * The manufacturer data of company 0x00D0 (Dexcom) is
 * `CRC16(serial) little endian (2 bytes) | types in use (1 byte) | ...`.
 * `CRC16(serial)` is Dexcom's CRC-16/XMODEM ([OnePlusFastCrc16]) over the ASCII bytes of the
 * applicator GS1 AI `21` serial number, allowing a scanned barcode to identify its exact
 * transmitter among several nearby sensors before connecting.
 * "Types in use" has one bit per display type that held a slot in the last few minutes. The phone
 * is display type 2, so its bit is `1 shl (2 - 1)`.
 *
 * Where this comes from: the layout and the phone bit are the ones nightscout/AndroidAPS PR 5397
 * reads (`G7Advertisement`, ported there from LoopKit G7SensorKit). Only the facts are used here,
 * the code is our own.
 *
 * Android `ScanRecord.getManufacturerSpecificData(id)` gives the bytes **without** the company id,
 * which is what [serialCrc16] and [phoneSlotHeld] read.
 *
 * Null always means "the advertisement did not say". It is never read as free or as held.
 */
object OnePlusAdvSlot {

    /** Bluetooth SIG company id of Dexcom. */
    const val DEXCOM_COMPANY_ID: Int = 0x00D0

    /** Bit of the phone display type in the "types in use" byte. */
    const val PHONE_SLOT_MASK: Int = 0x02

    private const val SERIAL_CRC_LENGTH = 2
    private const val TYPES_IN_USE_OFFSET = 2

    /**
     * Unsigned 16-bit `CRC16(serial)` from bytes `0..1` (little-endian) of the Dexcom
     * manufacturer data **without** the company id, or null when missing / too short.
     */
    fun serialCrc16(manufacturerData: ByteArray?): Int? {
        if (manufacturerData == null || manufacturerData.size < SERIAL_CRC_LENGTH) return null
        val low = manufacturerData[0].toInt() and 0xff
        val high = manufacturerData[1].toInt() and 0xff
        return low or (high shl 8)
    }

    /**
     * Unsigned 16-bit `CRC16(serial)` expected in the advertisement for an applicator GS1 AI `21`
     * [serial], or null when [serial] is null or blank.
     */
    fun expectedSerialCrc16(serial: String?): Int? {
        val trimmed = serial?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val crc = OnePlusFastCrc16.calculate(trimmed.encodeToByteArray())
        val low = crc[0].toInt() and 0xff
        val high = crc[1].toInt() and 0xff
        return low or (high shl 8)
    }

    /**
     * Whether an advertisement's [advCrc16] matches the applicator [serial].
     * Returns false whenever either side is missing.
     */
    fun matchesSerialCrc(advCrc16: Int?, serial: String?): Boolean {
        if (advCrc16 == null) return false
        val expected = expectedSerialCrc16(serial) ?: return false
        return advCrc16 == expected
    }

    /**
     * @param manufacturerData the Dexcom manufacturer data **without** the company id, as Android
     *   gives it. Null or too short gives null.
     */
    fun phoneSlotHeld(manufacturerData: ByteArray?): Boolean? {
        if (manufacturerData == null || manufacturerData.size <= TYPES_IN_USE_OFFSET) return null
        val typesInUse = manufacturerData[TYPES_IN_USE_OFFSET].toInt() and 0xff
        return typesInUse and PHONE_SLOT_MASK != 0
    }
}
