package app.aaps.plugins.dexcomoneplus.scan

import app.aaps.plugins.dexcomoneplus.identity.OnePlusAdvSlot
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession

/**
 * Which sensor a setup screen may select on its own, out of what the scan heard.
 *
 * This is a rule, not a platform job, so it lives in shared code and is the same on every
 * platform.
 */
object OnePlusScanSelection {

    /**
     * The sensor a screen may select on its own, out of what the scan heard.
     *
     * Exactly one hit, and only then. The 4-digit code is the KEKS password and is not in the
     * advertisement, so with two ONE+ in range and no barcode serial, no score can tell which one
     * the user just applied: the strongest signal may be the neighbour's, and the best ranked one
     * may be the sensor being replaced. Picking either would send the user into a five minute
     * connect with the wrong transmitter. Two or more is a question, and the user answers it by
     * tapping a row.
     *
     * Nothing heard means nothing to connect to, so that answer is null as well.
     */
    fun autoSelectSingle(devices: List<OnePlusScanResult>): OnePlusScanResult? = devices.singleOrNull()

    /**
     * The unique live advertisement whose `CRC16(serial)` matches the applicator [serial], or null
     * when none match, [serial] is absent, or more than one sensor collides on the same 16-bit CRC.
     */
    fun findSerialMatch(devices: List<OnePlusScanResult>, serial: String?): OnePlusScanResult? {
        val expectedCrc = OnePlusAdvSlot.expectedSerialCrc16(serial) ?: return null
        return devices.filter { it.serialCrc16 == expectedCrc }.singleOrNull()
    }

    /**
     * The transmitter the start screen may select on its own, given what the scan heard so far.
     *
     * Three cases, in order:
     * - The code on screen carries a scanned applicator serial and a **unique** live sighting
     *   matches its `CRC16(serial)` in the Dexcom `0x00D0` advertisement: that sensor is the
     *   answer, even among several nearby transmitters. If two sensors ever collide on the same
     *   16-bit CRC, return null and let the user pick.
     * - The code belongs to the stored sensor (reconnect / repair): that stored sensor is the
     *   answer, preferring a live sighting because it carries the fresh ADV for in-window connect.
     * - Otherwise only a single hit may be selected — see [autoSelectSingle]. If a barcode serial
     *   is known and the lone sensor carries a non-matching CRC, refuse it rather than selecting
     *   the wrong transmitter.
     *
     * Feed it the session from `OnePlusAdvCandidate.scanHintFor`, which is what drops the stored
     * MAC as soon as the code on screen is another sensor.
     */
    fun autoSelect(devices: List<OnePlusScanResult>, hint: OnePlusStoredSession?): OnePlusScanResult? {
        val expectedCrc = OnePlusAdvSlot.expectedSerialCrc16(hint?.identity?.serial)
        if (expectedCrc != null) {
            val matches = devices.filter { it.serialCrc16 == expectedCrc }
            if (matches.size == 1) return matches.first()
            if (matches.size > 1) return null
        }
        val storedMac = hint?.lastMac?.takeIf { it.isNotBlank() }
        if (storedMac != null) {
            return devices.firstOrNull { it.address.equals(storedMac, ignoreCase = true) }
                ?: OnePlusScanResult(address = storedMac, name = hint.lastDeviceName, rssi = 0)
        }
        val single = autoSelectSingle(devices) ?: return null
        if (expectedCrc != null && single.serialCrc16 != null) return null
        return single
    }
}
