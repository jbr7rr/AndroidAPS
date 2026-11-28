package app.aaps.plugins.source.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriver
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverStub
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseWatcher
import app.aaps.plugins.dexcomoneplus.OnePlusInstalledApps
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.identity.OnePlusAdvSlot
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorPrefs
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScanner
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerStub
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanListener
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanResult
import app.aaps.plugins.source.DexcomOnePlusHost
import app.aaps.plugins.source.DexcomOnePlusPlugin
import app.aaps.plugins.source.SourceStringIds
import app.aaps.plugins.source.SourceStrings
import app.aaps.plugins.source.SourceStringsValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config as RobolectricConfig

/**
 * The sensor start screen, rendered.
 *
 * Two things this proves that no unit test can. The strings really resolve - every one of them is
 * a `TextRef.Named`, so a name that no longer matched would draw as the raw name here. And the
 * pre-soak slot is **absent** when no second slot is offered, rather than present and broken.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RobolectricConfig(sdk = [35])
class DexcomOnePlusStartScreenTest {

    @get:Rule val compose = createComposeRule()

    /** The English text of a string, which is what the screen must draw. */
    private fun text(name: String): String =
        requireNotNull(SourceStringsValues.textOf(name, null)) { "no English text for $name" }

    private fun render(
        twoSlots: Boolean,
        host: DexcomOnePlusHost = FakeHost(),
        initialSlot: SensorSlot = SensorSlot.PRODUCTION,
        driver: OnePlusCgmDriver = OnePlusCgmDriverStub.instance,
        applicatorScanner: OnePlusApplicatorScanner? = null,
        onStarted: (SensorSlot) -> Unit = {},
        onIsStagingSensor: (String) -> Boolean = { false },
    ) {
        // MainApp does this in production. Without it every TextRef.Named draws as its raw name and
        // the assertions below read "the node is not displayed" without saying why.
        TextRefIdRegistry.register("source") { name -> SourceStringIds.idOf(name) }
        compose.setContent {
            DexcomOnePlusStartScreen(
                host = host,
                initialSlot = initialSlot,
                permissions = GrantedPermissions,
                onBack = {},
                onEnsureDriver = { driver },
                onActivatePlugin = {},
                isPluginStopped = { false },
                onStarted = onStarted,
                onBeginStaging = { true },
                onIsStagingSensor = onIsStagingSensor,
                onCancelStaging = {},
                onOpenPromote = {},
                onStagingDriver = if (twoSlots) ({ error("not reached in this test") }) else null,
                onSensorSessionStarted = { _, _ -> },
                applicatorScanner = applicatorScanner,
            )
        }
    }

    @Test
    fun `a phone with two slots offers the pre-soak choice`() {
        render(twoSlots = true)
        compose.onNodeWithText(text("dexcom_oneplus_slot_staging_short")).assertIsDisplayed()
        compose.onNodeWithText(text("dexcom_oneplus_slot_production_short")).assertIsDisplayed()
    }

    /** "Start a pre-soak sensor" must not be able to start a production sensor by accident. */
    @Test
    fun `opened for a pre-soak the production choice is disabled`() {
        render(twoSlots = true, initialSlot = SensorSlot.STAGING)
        compose.onNodeWithText(text("dexcom_oneplus_slot_staging_short")).assertIsSelected().assertIsEnabled()
        compose.onNodeWithText(text("dexcom_oneplus_slot_production_short")).assertIsNotEnabled()
    }

    @Test
    fun `opened for a new sensor the pre-soak choice is disabled`() {
        render(twoSlots = true, initialSlot = SensorSlot.PRODUCTION)
        compose.onNodeWithText(text("dexcom_oneplus_slot_production_short")).assertIsSelected().assertIsEnabled()
        compose.onNodeWithText(text("dexcom_oneplus_slot_staging_short")).assertIsNotEnabled()
    }

    @Test
    fun `a phone with one slot does not show the pre-soak choice at all`() {
        render(twoSlots = false)
        compose.onNodeWithText(text("dexcom_oneplus_slot_staging_short")).assertDoesNotExist()
        compose.onNodeWithText(text("dexcom_oneplus_slot_production_short")).assertDoesNotExist()
    }

    @Test
    fun `the converted strings are drawn as their text and not as their names`() {
        render(twoSlots = true)
        compose.onNodeWithText(text("dexcom_oneplus_start_title")).assertIsDisplayed()
        compose.onNodeWithText(text("dexcom_oneplus_section_pairing_code")).assertIsDisplayed()
        compose.onNodeWithText(text("dexcom_oneplus_pairing_code_label")).assertIsDisplayed()
        // The bottom bar is pinned, so this one is on screen whatever the list is doing.
        compose.onNodeWithText(text("dexcom_oneplus_connect_follow")).assertIsDisplayed()
    }

    @Test
    fun `the scan part of the screen reads correctly once it is scrolled to`() {
        render(twoSlots = true)
        // The screen is one long list and the test window is a phone sized one, so the scan rows
        // are not composed until the list is asked for them.
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("scanEmpty")
        compose.onNodeWithText(text("dexcom_oneplus_section_sensors_nearby")).assertExists()
        compose.onNodeWithText(text("dexcom_oneplus_scan_pick_hint")).assertExists()
        compose.onNodeWithText(text("dexcom_oneplus_scan_empty")).assertExists()
        // dexcom_oneplus_scan_idle is one of the six lines that take an argument. Nothing has been
        // heard yet so it must read zero - and a wrong argument count would leave a placeholder.
        val idle = SourceStringsValues.textOf("dexcom_oneplus_scan_idle", null)!!.replace("%1\$d", "0")
        compose.onNodeWithText(idle, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an installed sensor app is named in a warning`() {
        render(twoSlots = false, host = FakeHost(installedApps = { it == "com.eveningoutpost.dexdrip" }))
        // The warning is with the other folded cards, below the scan list.
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("help")
        // Folded: the title shows, and a tap opens the text with the app names.
        compose.onNodeWithText(text("dexcom_oneplus_competing_apps_title")).assertExists()
        compose.onNodeWithText("xDrip+").assertDoesNotExist()
        compose.onNodeWithText(text("dexcom_oneplus_competing_apps_title")).performClick()
        compose.onNodeWithText("xDrip+").assertExists()
    }

    @Test
    fun `with an installed sensor app the general warning is not shown a second time`() {
        render(twoSlots = false, host = FakeHost(installedApps = { it == "com.eveningoutpost.dexdrip" }))
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("help")
        compose.onNodeWithText(text("dexcom_oneplus_help_how_to_start")).assertExists()
        compose.onNodeWithText(text("dexcom_oneplus_help_before_pairing")).assertDoesNotExist()
    }

    @Test
    fun `without an installed sensor app the general warning is in the help`() {
        render(twoSlots = false, host = FakeHost(installedApps = { false }))
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("help")
        compose.onNodeWithText(text("dexcom_oneplus_help_before_pairing")).assertExists()
    }

    @Test
    fun `no warning when no sensor app is installed`() {
        render(twoSlots = false, host = FakeHost(installedApps = { false }))
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("help")
        compose.onNodeWithText(text("dexcom_oneplus_competing_apps_title")).assertDoesNotExist()
    }

    @Test
    fun `no warning at all on a phone that cannot list its apps`() {
        // No answer is not the same as "none installed", so nothing is drawn.
        render(twoSlots = false, host = FakeHost(installedApps = null))
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("help")
        compose.onNodeWithText(text("dexcom_oneplus_competing_apps_title")).assertDoesNotExist()
    }

    @Test
    fun `scanning a barcode starts BLE scan and connects automatically on CRC match`() {
        val serial = "123456789012"
        val gs1 = "\u001d010038627000488821$serial\u001d2408408"
        val expectedCrc = OnePlusAdvSlot.expectedSerialCrc16(serial)!!
        val scanner = RecordingBleScanner()
        val driver = RecordingDriver()
        var startedSlot: SensorSlot? = null

        render(
            twoSlots = false,
            host = FakeHost(scanner = scanner),
            driver = driver,
            applicatorScanner = ImmediateApplicatorScanner(gs1),
            onStarted = { startedSlot = it },
        )

        compose.onNodeWithText(text("dexcom_oneplus_applicator_scan")).performClick()
        compose.waitForIdle()

        // Barcode scan automatically started BLE scanning.
        assertEquals(1, scanner.startCount)
        assertTrue(scanner.isScanning())

        // A nearby sensor with a different CRC does not trigger connect.
        scanner.emit(OnePlusScanResult("AA:AA:AA:AA:AA:AA", "DX02AA", rssi = -40, serialCrc16 = expectedCrc xor 0xFF))
        compose.waitForIdle()
        assertNull(driver.connectedAddress)
        assertNull(startedSlot)

        // The sensor matching the scanned serial's CRC-16 auto-selects and connects.
        scanner.emit(OnePlusScanResult("BB:BB:BB:BB:BB:BB", "DX02BB", rssi = -80, serialCrc16 = expectedCrc))
        compose.waitForIdle()
        assertEquals("BB:BB:BB:BB:BB:BB", driver.connectedAddress)
        assertEquals("8408", driver.connectedCode)
        assertEquals(SensorSlot.PRODUCTION, startedSlot)
    }

    @Test
    fun `scanning a barcode while BLE scan is already active connects immediately without restarting scan`() {
        val serial = "123456789012"
        val gs1 = "\u001d010038627000488821$serial\u001d2408408"
        val expectedCrc = OnePlusAdvSlot.expectedSerialCrc16(serial)!!
        val scanner = RecordingBleScanner()
        val driver = RecordingDriver()

        render(
            twoSlots = false,
            host = FakeHost(scanner = scanner),
            driver = driver,
            applicatorScanner = ImmediateApplicatorScanner(gs1),
        )

        // Start scanning first and hear both a neighbour and the target sensor.
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("scanHeader")
        compose.onNodeWithText(text("dexcom_oneplus_scan_start")).performClick()
        compose.waitForIdle()
        assertEquals(1, scanner.startCount)
        scanner.emit(OnePlusScanResult("AA:AA:AA:AA:AA:AA", "DX02AA", rssi = -40, serialCrc16 = expectedCrc xor 0xFF))
        scanner.emit(OnePlusScanResult("BB:BB:BB:BB:BB:BB", "DX02BB", rssi = -75, serialCrc16 = expectedCrc))
        compose.waitForIdle()
        assertNull(driver.connectedAddress)

        // Now scan the barcode — scan must NOT be restarted and target connects at once.
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("form")
        compose.onNodeWithText(text("dexcom_oneplus_applicator_scan")).performClick()
        compose.waitForIdle()
        assertEquals(1, scanner.startCount)
        assertEquals("BB:BB:BB:BB:BB:BB", driver.connectedAddress)
        assertEquals("8408", driver.connectedCode)
    }

    @Test
    fun `stopping BLE scan disarms barcode auto-connect`() {
        val serial = "123456789012"
        val gs1 = "\u001d010038627000488821$serial\u001d2408408"
        val expectedCrc = OnePlusAdvSlot.expectedSerialCrc16(serial)!!
        val scanner = RecordingBleScanner()
        val driver = RecordingDriver()

        render(
            twoSlots = false,
            host = FakeHost(scanner = scanner),
            driver = driver,
            applicatorScanner = ImmediateApplicatorScanner(gs1),
        )

        compose.onNodeWithText(text("dexcom_oneplus_applicator_scan")).performClick()
        compose.waitForIdle()
        assertTrue(scanner.isScanning())

        // While waiting for the scanned sensor, the waiting hint replaces the manual pick hint.
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("scanHeader")
        compose.onNodeWithText(text("dexcom_oneplus_scan_barcode_waiting_hint")).assertExists()
        compose.onNodeWithText(text("dexcom_oneplus_scan_pick_hint")).assertDoesNotExist()

        // A nearby sensor with a different CRC is marked as a different sensor and cannot be picked.
        scanner.emit(OnePlusScanResult("AA:AA:AA:AA:AA:AA", "DX02AA", rssi = -40, serialCrc16 = expectedCrc xor 0xFF))
        compose.waitForIdle()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("AA:AA:AA:AA:AA:AA")
        compose.onNodeWithText(text("dexcom_oneplus_scan_barcode_mismatch")).assertExists()
        compose.onNodeWithText("DX02AA").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text("dexcom_oneplus_connect_follow")).assertIsNotEnabled()

        compose.onNode(hasScrollToKeyAction()).performScrollToKey("scanHeader")
        compose.onNodeWithText(text("dexcom_oneplus_scan_stop")).performClick()
        compose.waitForIdle()

        // Stopping scan disarms auto-connect and restores the manual pick hint.
        compose.onNodeWithText(text("dexcom_oneplus_scan_pick_hint")).assertExists()
        compose.onNodeWithText(text("dexcom_oneplus_scan_barcode_waiting_hint")).assertDoesNotExist()

        // A late callback after Stop Scan must not auto-connect.
        scanner.emit(OnePlusScanResult("BB:BB:BB:BB:BB:BB", "DX02BB", rssi = -60, serialCrc16 = expectedCrc))
        compose.waitForIdle()
        assertNull(driver.connectedAddress)
    }

    @Test
    fun `barcode auto-connect still asks before overwriting a pre-soaking sensor`() {
        val serial = "123456789012"
        val gs1 = "\u001d010038627000488821$serial\u001d2408408"
        val expectedCrc = OnePlusAdvSlot.expectedSerialCrc16(serial)!!
        val scanner = RecordingBleScanner()
        val driver = RecordingDriver()

        render(
            twoSlots = true,
            host = FakeHost(scanner = scanner),
            driver = driver,
            applicatorScanner = ImmediateApplicatorScanner(gs1),
            onIsStagingSensor = { it == "BB:BB:BB:BB:BB:BB" },
        )

        compose.onNodeWithText(text("dexcom_oneplus_applicator_scan")).performClick()
        compose.waitForIdle()
        scanner.emit(OnePlusScanResult("BB:BB:BB:BB:BB:BB", "DX02BB", rssi = -60, serialCrc16 = expectedCrc))
        compose.waitForIdle()

        assertNull(driver.connectedAddress)
        compose.onNodeWithText(text("dexcom_oneplus_slot_conflict_title")).assertIsDisplayed()
    }

    /** A phone where the Bluetooth permission is already granted. */
    private object GrantedPermissions : OnePlusBlePermissionGate {
        override fun granted(): Boolean = true
        override fun request() = Unit
    }

    /** Emits [code] as soon as the camera dialog opens. */
    private class ImmediateApplicatorScanner(private val code: String) : OnePlusApplicatorScanner {
        @Composable
        override fun CameraView(onScanned: (String) -> Boolean, modifier: Modifier) {
            LaunchedEffect(code) { onScanned(code) }
        }
    }

    /** Captures scan lifecycle and lets tests deliver synthetic BLE hits. */
    private class RecordingBleScanner : OnePlusBleScanner {
        var startCount = 0
        private var scanning = false
        private var listener: OnePlusScanListener? = null

        override fun startScan(listener: OnePlusScanListener) {
            startCount++
            scanning = true
            this.listener = listener
        }

        override fun stopScan() {
            scanning = false
        }

        override fun isScanning(): Boolean = scanning

        fun emit(result: OnePlusScanResult) {
            listener?.onDevice(result)
        }
    }

    /** Records the address and code passed to [connect]. */
    private class RecordingDriver : OnePlusCgmDriver by OnePlusCgmDriverStub.instance {
        var connectedAddress: String? = null
        var connectedCode: String? = null

        override fun connect(deviceAddress: String, pairingCode: String) {
            connectedAddress = deviceAddress
            connectedCode = pairingCode
        }
    }

    /** Everything the screen asks of the phone, answered without Bluetooth or a file. */
    private class FakeHost(
        override val installedApps: OnePlusInstalledApps? = null,
        private val scanner: OnePlusBleScanner = OnePlusBleScannerStub(),
    ) : DexcomOnePlusHost {

        override fun attachPlatform(driver: OnePlusCgmDriver) = Unit
        override fun slotStore(namespace: String?): OnePlusSensorStore = OnePlusSensorStore(MemoryPrefs())
        override val stagingNamespace: String = "staging"
        override fun productionDriver(): OnePlusCgmDriver = OnePlusCgmDriverStub.instance
        override fun stagingDriver(): OnePlusCgmDriver = OnePlusCgmDriverStub.instance
        override fun existingProductionDriver(): OnePlusCgmDriver? = OnePlusCgmDriverStub.instance
        override fun existingStagingDriver(): OnePlusCgmDriver? = OnePlusCgmDriverStub.instance
        override fun attachProductionDriver(watcher: OnePlusGlucoseWatcher): OnePlusCgmDriver =
            OnePlusCgmDriverStub.instance

        override fun promoteStagingInstance(): OnePlusCgmDriverReal? = null
        override fun setSessionServiceWanted(wanted: Boolean) = Unit
        override fun showWarmup(state: OnePlusWarmupState) = Unit
        override fun cancelWarmupNotification() = Unit
        override fun alert(message: String) = Unit
        override fun screenScanner(): OnePlusBleScanner = scanner
        override fun openScreen(screen: DexcomOnePlusHost.Screen) = Unit
        override suspend fun driverLog(): DexcomOnePlusHost.DriverLog =
            DexcomOnePlusHost.DriverLog(emptyList(), SourceStrings.dexcom_oneplus_log_note_file)

        override fun shareLog(text: String) = Unit
    }

    /** An empty slot store in memory, so the screen starts with no sensor remembered. */
    private class MemoryPrefs : OnePlusSensorPrefs {

        private val values = mutableMapOf<String, Any>()

        override fun getString(key: String): String? = values[key] as? String
        override fun getLong(key: String, defaultValue: Long): Long = values[key] as? Long ?: defaultValue
        override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            values[key] as? Boolean ?: defaultValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(block: OnePlusSensorPrefs.Editor.() -> Unit) {
            object : OnePlusSensorPrefs.Editor {
                override fun putString(key: String, value: String?) {
                    if (value == null) values.remove(key) else values[key] = value
                }

                override fun putLong(key: String, value: Long) {
                    values[key] = value
                }

                override fun putInt(key: String, value: Int) {
                    values[key] = value
                }

                override fun putBoolean(key: String, value: Boolean) {
                    values[key] = value
                }

                override fun remove(key: String) {
                    values.remove(key)
                }

                override fun clear() {
                    values.clear()
                }
            }.block()
        }
    }
}
