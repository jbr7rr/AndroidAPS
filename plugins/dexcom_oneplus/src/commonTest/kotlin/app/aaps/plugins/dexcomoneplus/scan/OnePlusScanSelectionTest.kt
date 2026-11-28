package app.aaps.plugins.dexcomoneplus.scan

import app.aaps.plugins.dexcomoneplus.identity.OnePlusAdvSlot
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnePlusScanSelectionTest {

    private val serial = "123456789"
    private val serialCrc = OnePlusAdvSlot.expectedSerialCrc16(serial)!!
    private val otherCrc = serialCrc xor 0x00FF

    @Test
    fun `autoSelect picks the unique CRC-matched sensor among multiple nearby sensors`() {
        val hint = OnePlusStoredSession(identity = OnePlusSensorIdentity(pin = "8408", serial = serial))
        val neighbour = OnePlusScanResult("AA:AA:AA:AA:AA:AA", "DX02AA", rssi = -40, serialCrc16 = otherCrc)
        val target = OnePlusScanResult("BB:BB:BB:BB:BB:BB", "DX02BB", rssi = -85, serialCrc16 = serialCrc)
        val third = OnePlusScanResult("CC:CC:CC:CC:CC:CC", "DXCMCC", rssi = -55, serialCrc16 = null)

        val picked = OnePlusScanSelection.autoSelect(listOf(neighbour, target, third), hint)
        assertEquals(target, picked)
    }

    @Test
    fun `autoSelect returns null when two sensors collide on the same serial CRC`() {
        val hint = OnePlusStoredSession(identity = OnePlusSensorIdentity(pin = "8408", serial = serial))
        val first = OnePlusScanResult("AA:AA:AA:AA:AA:AA", "DX02AA", rssi = -50, serialCrc16 = serialCrc)
        val second = OnePlusScanResult("BB:BB:BB:BB:BB:BB", "DX02BB", rssi = -60, serialCrc16 = serialCrc)

        assertNull(OnePlusScanSelection.findSerialMatch(listOf(first, second), serial))
        assertNull(OnePlusScanSelection.autoSelect(listOf(first, second), hint))
    }

    @Test
    fun `autoSelect refuses a lone sensor whose CRC proves it is not the scanned barcode`() {
        val hint = OnePlusStoredSession(identity = OnePlusSensorIdentity(pin = "8408", serial = serial))
        val wrong = OnePlusScanResult("AA:AA:AA:AA:AA:AA", "DX02AA", rssi = -45, serialCrc16 = otherCrc)

        assertNull(OnePlusScanSelection.autoSelect(listOf(wrong), hint))
    }

    @Test
    fun `autoSelect still selects a lone sensor with no manufacturer CRC data`() {
        val hint = OnePlusStoredSession(identity = OnePlusSensorIdentity(pin = "8408", serial = serial))
        val legacy = OnePlusScanResult("AA:AA:AA:AA:AA:AA", "DX02AA", rssi = -45, serialCrc16 = null)

        assertEquals(legacy, OnePlusScanSelection.autoSelect(listOf(legacy), hint))
    }

    @Test
    fun `without a serial manual entry keeps single-hit and multi-hit rules`() {
        val hint = OnePlusStoredSession(identity = OnePlusSensorIdentity(pin = "8408"))
        val a = OnePlusScanResult("AA:AA:AA:AA:AA:AA", "DX02AA", rssi = -45, serialCrc16 = serialCrc)
        val b = OnePlusScanResult("BB:BB:BB:BB:BB:BB", "DX02BB", rssi = -60, serialCrc16 = otherCrc)

        assertEquals(a, OnePlusScanSelection.autoSelect(listOf(a), hint))
        assertNull(OnePlusScanSelection.autoSelect(listOf(a, b), hint))
    }
}
