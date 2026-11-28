package app.aaps.plugins.dexcomoneplus.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "phone slot held" bit of the advertisement, and what it does to the scan order.
 */
class OnePlusAdvSlotTest {

    /** CRC (2 bytes) | types in use | 0x04, as Android gives it (no company id). */
    private fun android(typesInUse: Int) = byteArrayOf(0x34, 0x12, typesInUse.toByte(), 0x04)

    @Test
    fun `the phone bit set means the slot is held`() {
        assertEquals(true, OnePlusAdvSlot.phoneSlotHeld(android(0x02)))
        assertEquals(true, OnePlusAdvSlot.phoneSlotHeld(android(0x03)))
    }

    @Test
    fun `other display types do not hold the phone slot`() {
        assertEquals(false, OnePlusAdvSlot.phoneSlotHeld(android(0x00)))
        // Watch (type 3) and medical (type 1) have their own slots.
        assertEquals(false, OnePlusAdvSlot.phoneSlotHeld(android(0x04)))
        assertEquals(false, OnePlusAdvSlot.phoneSlotHeld(android(0x01)))
    }

    @Test
    fun `missing or short data says nothing`() {
        assertNull(OnePlusAdvSlot.phoneSlotHeld(null))
        assertNull(OnePlusAdvSlot.phoneSlotHeld(byteArrayOf(0x34, 0x12)))
    }

    @Test
    fun `serialCrc16 reads the first two bytes little endian`() {
        assertEquals(0x1234, OnePlusAdvSlot.serialCrc16(byteArrayOf(0x34, 0x12, 0x02, 0x04)))
        assertEquals(0xC392, OnePlusAdvSlot.serialCrc16(byteArrayOf(0x92.toByte(), 0xC3.toByte())))
        assertNull(OnePlusAdvSlot.serialCrc16(null))
        assertNull(OnePlusAdvSlot.serialCrc16(byteArrayOf(0x34)))
    }

    @Test
    fun `expectedSerialCrc16 matches Dexcom CRC16 over ASCII serial bytes`() {
        // CRC-16/XMODEM("123456789") is the standard check value 0x31C3 (LE bytes: C3 31).
        assertEquals(0x31C3, OnePlusAdvSlot.expectedSerialCrc16("123456789"))
        assertEquals(0x31C3, OnePlusAdvSlot.expectedSerialCrc16("  123456789  "))
        assertNull(OnePlusAdvSlot.expectedSerialCrc16(null))
        assertNull(OnePlusAdvSlot.expectedSerialCrc16("   "))

        val advBytes = byteArrayOf(0xC3.toByte(), 0x31, 0x02, 0x04)
        val advCrc = OnePlusAdvSlot.serialCrc16(advBytes)
        assertTrue(OnePlusAdvSlot.matchesSerialCrc(advCrc, "123456789"))
        assertFalse(OnePlusAdvSlot.matchesSerialCrc(advCrc, "987654321"))
        assertFalse(OnePlusAdvSlot.matchesSerialCrc(null, "123456789"))
        assertFalse(OnePlusAdvSlot.matchesSerialCrc(advCrc, null))
    }

    @Test
    fun `a free sensor ranks above a held one even with a weaker signal`() {
        val free = OnePlusAdvCandidate.rankScore("DX02aa", "11:11:11:11:11:11", -95, null, phoneSlotHeld = false)
        val held = OnePlusAdvCandidate.rankScore("DX02bb", "22:22:22:22:22:22", -35, null, phoneSlotHeld = true)
        assertTrue(free > held)
    }

    @Test
    fun `an unknown slot ranks like a free one`() {
        val unknown = OnePlusAdvCandidate.rankScore("DX02aa", "11:11:11:11:11:11", -60, null, phoneSlotHeld = null)
        val free = OnePlusAdvCandidate.rankScore("DX02aa", "11:11:11:11:11:11", -60, null, phoneSlotHeld = false)
        assertEquals(free, unknown)
    }

    @Test
    fun `the stored sensor keeps its place when its slot is held`() {
        // Our own phone holds the slot of the sensor we already use.
        val stored = OnePlusStoredSession(
            identity = OnePlusSensorIdentity(pin = "1234"),
            lastMac = "AA:AA:AA:AA:AA:AA",
            lastDeviceName = "DX02st",
        )
        val sticky = OnePlusAdvCandidate.rankScore("DX02st", "AA:AA:AA:AA:AA:AA", -90, stored, phoneSlotHeld = true)
        val otherFree = OnePlusAdvCandidate.rankScore("DX02zz", "BB:BB:BB:BB:BB:BB", -40, stored, phoneSlotHeld = false)
        assertTrue(sticky > otherFree)
        val stickyFree = OnePlusAdvCandidate.rankScore("DX02st", "AA:AA:AA:AA:AA:AA", -90, stored, phoneSlotHeld = false)
        assertEquals(stickyFree, sticky)
    }

    @Test
    fun `the serial hint still wins over a held slot`() {
        val hint = OnePlusStoredSession(identity = OnePlusSensorIdentity(pin = "1234", serial = "ab12"))
        val serialHeld = OnePlusAdvCandidate.rankScore("DX02ab12", "11:11:11:11:11:11", -90, hint, phoneSlotHeld = true)
        val plainFree = OnePlusAdvCandidate.rankScore("DX02zz", "22:22:22:22:22:22", -40, hint, phoneSlotHeld = false)
        assertTrue(serialHeld > plainFree)
        assertFalse(OnePlusAdvCandidate.SLOT_HELD_PENALTY >= 1_000)
    }
}
