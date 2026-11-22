package app.aaps.core.nssdk.remotemodel

import app.aaps.core.nssdk.localmodel.treatment.NSICfg
import app.aaps.core.nssdk.localmodel.treatment.NSInsulinModel
import app.aaps.core.nssdk.mapper.toNSICfg
import app.aaps.core.nssdk.mapper.toRemoteICfg
import app.aaps.core.nssdk.nsSdkJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RemoteICfgTest {

    @Test
    fun legacyPayloadWithoutInsulinModelDecodesToNull() {
        val raw = """{"insulinLabel":"Rapid","insulinEndTime":21600000,"insulinPeakTime":4500000,"concentration":1.0}"""
        val decoded = nsSdkJson.decodeFromString(RemoteICfg.serializer(), raw)

        assertEquals("Rapid", decoded.insulinLabel)
        assertNull(decoded.insulinModel)
        assertNull(decoded.toNSICfg()?.insulinModel)
    }

    @Test
    fun bilinearRemoteICfgOmitsInsulinModelOnWire() {
        val ns = NSICfg("Rapid", 21_600_000L, 4_500_000L, 1.0)
        val remote = ns.toRemoteICfg()!!
        val json = Json.parseToJsonElement(nsSdkJson.encodeToString(RemoteICfg.serializer(), remote)).jsonObject

        assertFalse(json.containsKey("insulinModel"))
    }

    @Test
    fun pdModelRoundTripsAcrossWireAndMapper() {
        val ns = NSICfg(
            insulinLabel = "Lyumjev PD 65m 8h U200",
            insulinEndTime = 28_800_000L,
            insulinPeakTime = 3_900_000L,
            concentration = 2.0,
            insulinModel = NSInsulinModel(
                type = "PD",
                a0 = 61.33,
                a1 = 12.27,
                b1 = 0.05185,
                concentrationScalesPeak = true,
                durationMinutes = 480
            )
        )

        val wire = nsSdkJson.encodeToString(RemoteICfg.serializer(), ns.toRemoteICfg()!!)
        val decoded = nsSdkJson.decodeFromString(RemoteICfg.serializer(), wire)
        val back = decoded.toNSICfg()

        assertNotNull(back)
        assertEquals(ns, back)
    }

    @Test
    fun unknownExtraFieldsInPayloadAreIgnored() {
        val raw = """{"insulinLabel":"Custom PD","insulinEndTime":28800000,"insulinPeakTime":3060000,"concentration":1.0,"insulinModel":{"type":"PD","a0":51.0,"a1":12.27,"b1":0.05185,"futureField":42},"extraTopLevel":"ignored"}"""
        val decoded = nsSdkJson.decodeFromString(RemoteICfg.serializer(), raw)

        assertEquals("PD", decoded.insulinModel?.type)
        assertEquals(51.0, decoded.insulinModel?.a0)
    }
}
