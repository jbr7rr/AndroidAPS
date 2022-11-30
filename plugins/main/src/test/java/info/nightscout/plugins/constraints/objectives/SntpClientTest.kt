package info.nightscout.plugins.constraints.objectives

import info.nightscout.androidaps.TestBase
import info.nightscout.shared.utils.DateUtil
import org.junit.Assert
import org.junit.jupiter.api.Test
import org.mockito.Mock
import kotlin.math.abs

class SntpClientTest : TestBase() {
    @Mock lateinit var dateUtil: DateUtil

    @Test
    fun ntpTimeTest() {
        // no internet
        SntpClient(aapsLogger, dateUtil).ntpTime(object : SntpClient.Callback() {
            override fun run() {
                Assert.assertFalse(networkConnected)
                Assert.assertFalse(success)
                Assert.assertEquals(0L, time)
            }
        }, false)
        // internet
        SntpClient(aapsLogger, dateUtil).doNtpTime(object : SntpClient.Callback() {
            override fun run() {
                Assert.assertTrue(success)
                Assert.assertTrue(abs(time - System.currentTimeMillis()) < 60000)
            }
        })
    }
}