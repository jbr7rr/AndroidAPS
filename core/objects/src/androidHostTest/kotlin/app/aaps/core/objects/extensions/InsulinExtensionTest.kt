package app.aaps.core.objects.extensions

import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.data.model.PS
import app.aaps.core.data.model.data.Block
import app.aaps.core.data.model.data.TargetBlock
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.profile.ProfileSealed
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class InsulinExtensionTest {

    private val dayMs = T.hours(24).msecs()

    private fun dateUtil(): DateUtil = mock<DateUtil>().also {
        whenever(it.now()).thenReturn(1_700_000_000_000L)
        whenever(it.timeZoneByOffset(any())).thenReturn("UTC")
    }

    @Test
    fun `bilinear json round-trip omits insulinModel for backward compatibility`() {
        val bilinear = ICfg(
            insulinLabel = "Rapid-Acting Oref 75m 6h U100",
            insulinEndTime = 6L * 3600 * 1000,
            insulinPeakTime = 75L * 60 * 1000,
            concentration = 1.0,
            insulinModel = InsulinModel.Bilinear
        ).also { it.insulinNickname = "Rapid-Acting Oref" }

        val json = bilinear.toJsonObject()

        assertThat(json.keys).containsExactly(
            "insulinLabel",
            "insulinEndTime",
            "insulinPeakTime",
            "concentration",
            "insulinNickname"
        )
        assertThat(json.containsKey("insulinModel")).isFalse()

        val decoded = ICfg.fromJsonObject(json)
        assertThat(decoded).isEqualTo(bilinear)
        assertThat(decoded.insulinNickname).isEqualTo("Rapid-Acting Oref")
        assertThat(decoded.insulinModel).isEqualTo(InsulinModel.Bilinear)
    }

    @Test
    fun `custom pd json round-trip preserves model and parameters`() {
        val pd = InsulinModel.Pd(
            a0 = 51.0,
            a1 = 12.27,
            b1 = 0.05185,
            concentrationScalesPeak = false,
            durationMinutes = 480
        )
        val iCfg = ICfg(
            insulinLabel = "Custom PD 51m 8h U100",
            insulinEndTime = 8L * 3600 * 1000,
            insulinPeakTime = 51L * 60 * 1000,
            concentration = 1.0,
            insulinModel = pd
        ).also { it.insulinNickname = "Custom PD" }

        val json = iCfg.toJsonObject()
        val modelObj = json["insulinModel"]?.jsonObject
        assertThat(modelObj).isNotNull()
        assertThat(modelObj.toString()).contains("\"type\":\"PD\"")
        assertThat(modelObj.toString()).contains("\"a0\":51.0")
        assertThat(modelObj.toString()).contains("\"a1\":12.27")
        assertThat(modelObj.toString()).contains("\"b1\":0.05185")
        assertThat(modelObj.toString()).contains("\"concentrationScalesPeak\":false")
        assertThat(modelObj.toString()).contains("\"durationMinutes\":480")

        val decoded = ICfg.fromJsonObject(json)
        assertThat(decoded).isEqualTo(iCfg)
        assertThat(decoded.insulinNickname).isEqualTo("Custom PD")
        assertThat(decoded.insulinModel).isEqualTo(pd)
    }

    @Test
    fun `lyumjev u200 pd json round-trip preserves concentrationScalesPeak`() {
        val pd = InsulinModel.Pd(
            a0 = 61.33,
            a1 = 12.27,
            b1 = 0.05185,
            concentrationScalesPeak = true,
            durationMinutes = 480
        )
        val iCfg = ICfg("Lyumjev PD 65m 8h U200", 8L * 3600 * 1000, 65L * 60 * 1000, 2.0, pd)

        val decoded = ICfg.fromJsonObject(iCfg.toJsonObject())

        assertThat(decoded).isEqualTo(iCfg)
        assertThat(decoded.insulinModel).isEqualTo(pd)
        assertThat(decoded.concentration).isEqualTo(2.0)
    }

    @Test
    fun `missing insulinModel decodes as bilinear`() {
        val legacyJson = Json.parseToJsonElement(
            """{"insulinLabel":"Rapid","insulinEndTime":21600000,"insulinPeakTime":4500000,"concentration":1.0}"""
        ) as JsonObject

        val decoded = ICfg.fromJsonObject(legacyJson)

        assertThat(decoded.insulinModel).isEqualTo(InsulinModel.Bilinear)
        assertThat(decoded.isUsable).isTrue()
    }

    @Test
    fun `unknown insulinModel type falls back to bilinear and logs warning`() {
        val logger = mock<AAPSLogger>()
        val futureJson = Json.parseToJsonElement(
            """{"insulinLabel":"Future","insulinEndTime":28800000,"insulinPeakTime":3000000,"concentration":1.0,"insulinModel":{"type":"FUTURE_MODEL"}}"""
        ) as JsonObject

        val decoded = ICfg.fromJsonObject(futureJson, logger)

        assertThat(decoded.insulinModel).isEqualTo(InsulinModel.Bilinear)
        assertThat(decoded.isUsable).isTrue()
        verify(logger).warn(eq(LTag.CORE), any<String>())
    }

    @Test
    fun `invalid pd parameters stay pd and unusable rather than falling back to bilinear`() {
        val invalidJson = Json.parseToJsonElement(
            """{"insulinLabel":"Broken PD","insulinEndTime":28800000,"insulinPeakTime":3060000,"concentration":1.0,"insulinModel":{"type":"PD","a0":0.0,"a1":12.27,"b1":0.05185,"concentrationScalesPeak":false,"durationMinutes":480}}"""
        ) as JsonObject

        val decoded = ICfg.fromJsonObject(invalidJson)

        assertThat(decoded.insulinModel).isInstanceOf(InsulinModel.Pd::class.java)
        assertThat(decoded.isPresent).isTrue()
        assertThat(decoded.isUsable).isFalse()
    }

    @Test
    fun `non-finite invalid pd round-trips as valid json and remains unusable`() {
        val broken = ICfg(
            insulinLabel = "Broken PD",
            insulinEndTime = 8L * 3600 * 1000,
            insulinPeakTime = 51L * 60 * 1000,
            concentration = 1.0,
            insulinModel = InsulinModel.Pd(a0 = Double.NaN, a1 = 12.27, b1 = 0.05185)
        )

        val serialized = broken.toJsonObject().toString()
        val parsed = Json.parseToJsonElement(serialized) as JsonObject
        val decoded = ICfg.fromJsonObject(parsed)

        assertThat(decoded.insulinModel).isInstanceOf(InsulinModel.Pd::class.java)
        assertThat(decoded.isPresent).isTrue()
        assertThat(decoded.isUsable).isFalse()
    }

    @Test
    fun `profile switch and effective profile switch json round-trip pd model`() {
        val du = dateUtil()
        val pd = InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false, durationMinutes = 480)
        val iCfg = ICfg("Custom PD 51m 8h U100", 8L * 3600 * 1000, 51L * 60 * 1000, 1.0, pd)
        val blocks = listOf(Block(dayMs, 1.0))
        val targets = listOf(TargetBlock(dayMs, 90.0, 90.0))

        val ps = PS(
            timestamp = 1_700_000_000_000L,
            utcOffset = 0L,
            basalBlocks = blocks,
            isfBlocks = listOf(Block(dayMs, 50.0)),
            icBlocks = listOf(Block(dayMs, 10.0)),
            targetBlocks = targets,
            glucoseUnit = GlucoseUnit.MGDL,
            profileName = "Default",
            timeshift = 0L,
            percentage = 100,
            duration = 0L,
            iCfg = iCfg
        )
        val eps = EPS(
            timestamp = 1_700_000_000_000L,
            utcOffset = 0L,
            basalBlocks = blocks,
            isfBlocks = listOf(Block(dayMs, 50.0)),
            icBlocks = listOf(Block(dayMs, 10.0)),
            targetBlocks = targets,
            glucoseUnit = GlucoseUnit.MGDL,
            originalProfileName = "Default",
            originalCustomizedName = "Default",
            originalTimeshift = 0L,
            originalPercentage = 100,
            originalDuration = 0L,
            originalEnd = 0L,
            iCfg = iCfg
        )

        val psJson = ProfileSealed.PS(ps, null).toPureNsJson(du).toString()
        val epsJson = ProfileSealed.EPS(eps, null).toPureNsJson(du).toString()

        assertThat(pureProfileFromJson(psJson, du)?.iCfg?.insulinModel).isEqualTo(pd)
        assertThat(pureProfileFromJson(epsJson, du)?.iCfg?.insulinModel).isEqualTo(pd)
    }
}
