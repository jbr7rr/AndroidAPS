package app.aaps.plugins.source.compose

import app.aaps.plugins.dexcomoneplus.identity.OnePlusAdvCandidate
import app.aaps.plugins.dexcomoneplus.identity.OnePlusAdvSlot
import app.aaps.plugins.dexcomoneplus.identity.OnePlusGs1ApplicatorParser
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanResult
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanSelection

/**
 * The decisions the sensor start screen makes, written as plain functions.
 *
 * They used to be expressions inside the composable, where nothing could run them. They are the
 * same expressions, moved out, so a test can say what the screen shows for a given state.
 */
object DexcomOnePlusStartRules {

    /** What the code field shows, and the sensor identity that goes with it. */
    data class CodeEntry(val shown: String, val identity: OnePlusSensorIdentity?)

    /**
     * Turns what was typed, pasted or scanned into the code field into what the field shows.
     *
     * A whole applicator code is about 50 digits. The user only knows the 4-digit code printed on
     * the applicator, so the field shows those 4 digits and nothing else. The rest of the scanned
     * code (the serial) is not lost: it stays in the identity.
     *
     * @param text the new text of the field
     * @param scanned the identity of the last whole applicator code, or null. While the field still
     *   holds the same 4 digits, that identity is kept, with its serial. Other digits are another
     *   sensor, typed by hand, so they start an identity without a serial.
     */
    fun codeEntry(text: String, scanned: OnePlusSensorIdentity?): CodeEntry {
        val parsed = OnePlusGs1ApplicatorParser.parse(text)
        return when {
            parsed?.rawGs1 != null                      -> CodeEntry(shown = parsed.pin, identity = parsed)
            parsed != null && scanned?.pin == parsed.pin -> CodeEntry(shown = text, identity = scanned)
            else                                        -> CodeEntry(shown = text, identity = parsed)
        }
    }

    /**
     * The scan list in the order the screen draws it: an exact barcode CRC match first, then the
     * sensor the code on screen points at, then by how well each hit matches, then free sensors
     * before the ones another phone holds, then by signal.
     */
    fun rank(devices: List<OnePlusScanResult>, hint: OnePlusStoredSession?): List<OnePlusScanResult> =
        devices.sortedByDescending {
            OnePlusAdvCandidate.rankScore(it.name, it.address, it.rssi, hint, it.phoneSlotHeld, it.serialCrc16)
        }

    /** Whether this live advertisement's `CRC16(serial)` matches the scanned applicator [identity]. */
    fun isBarcodeMatch(device: OnePlusScanResult, identity: OnePlusSensorIdentity?): Boolean =
        OnePlusAdvSlot.matchesSerialCrc(device.serialCrc16, identity?.serial)

    /**
     * Whether this advertisement's `CRC16(serial)` proves it belongs to a different transmitter than
     * the scanned applicator [identity]. False when no barcode serial is present or the advert did
     * not carry a CRC-16.
     */
    fun isBarcodeMismatch(device: OnePlusScanResult, identity: OnePlusSensorIdentity?): Boolean {
        val expected = OnePlusAdvSlot.expectedSerialCrc16(identity?.serial) ?: return false
        val actual = device.serialCrc16 ?: return false
        return actual != expected
    }

    /**
     * The unique sensor to connect to automatically after a barcode scan, or null when auto-connect
     * is not armed, was superseded by a manual choice, the plugin is stopped, or no unique match
     * has been heard yet.
     */
    fun autoConnectTarget(
        devices: List<OnePlusScanResult>,
        identity: OnePlusSensorIdentity?,
        armed: Boolean,
        deviceChosenByUser: Boolean,
        isPluginStopped: Boolean,
    ): OnePlusScanResult? {
        if (!armed || deviceChosenByUser || isPluginStopped) return null
        return OnePlusScanSelection.findSerialMatch(devices, identity?.serial)
    }

    /**
     * Whether the row says "in use by another phone".
     *
     * Not for the stored sensor: the phone that holds its slot is normally this one.
     */
    fun showsSlotHeld(device: OnePlusScanResult, storedMac: String?): Boolean =
        device.phoneSlotHeld == true && storedMac?.equals(device.address, ignoreCase = true) != true

    /**
     * Signal strength as bars.
     *
     * "−67 dBm" is a number the user cannot act on; four bars say "close enough" or "walk nearer"
     * immediately. The dBm value stays in the driver log for anyone debugging a range problem.
     *
     * Zero bars is the stored sensor offered without a live sighting, which carries no rssi at all.
     */
    fun signalBars(rssi: Int): Int = when {
        rssi == 0   -> 0
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        else        -> 1
    }

    /**
     * True when Connect would point the slot at a different transmitter than the one it holds.
     *
     * The pairing code is per sensor, so adopting another transmitter with the code of the previous
     * one cannot work. The screen asks once before it overwrites the slot.
     */
    fun adoptsAnotherSensor(storedMac: String?, address: String): Boolean =
        storedMac != null && !storedMac.equals(address, ignoreCase = true)
}
