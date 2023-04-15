package info.nightscout.pump.medtrum

import info.nightscout.core.extensions.pureProfileFromJson
import info.nightscout.core.profile.ProfileSealed
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.Assert.*

class MedtrumPumpTest : MedtrumTestBase() {

    @Test fun buildMedtrumProfileArrayGivenProfileWhenValuesSetThenReturnCorrectByteArray() {
        // Inputs
        // Basal profile with 7 elements:
        // 00:00 : 2.1
        // 04:00 : 1.9
        // 06:00 : 1.7
        // 08:00 : 1.5
        // 16:00 : 1.6
        // 21:00 : 1.7
        // 23:00 : 2
        val profileJSON = "{\"dia\":\"5\",\"carbratio\":[{\"time\":\"00:00\",\"value\":\"30\"}],\"carbs_hr\":\"20\",\"delay\":\"20\"," +
            "\"sens\":[{\"time\":\"00:00\",\"value\":\"3\"},{\"time\":\"02:00\",\"value\":\"3.4\"}],\"timezone\":\"UTC\"," +
            "\"basal\":[{\"time\":\"00:00\",\"value\":\"2.1\"},{\"time\":\"04:00\",\"value\":\"1.9\"},{\"time\":\"06:00\",\"value\":\"1.7\"}," +
            "{\"time\":\"08:00\",\"value\":\"1.5\"},{\"time\":\"16:00\",\"value\":\"1.6\"},{\"time\":\"21:00\",\"value\":\"1.7\"},{\"time\":\"23:00\",\"value\":\"2\"}]," +
            "\"target_low\":[{\"time\":\"00:00\",\"value\":\"4.5\"}],\"target_high\":[{\"time\":\"00:00\",\"value\":\"7\"}],\"startDate\":\"1970-01-01T00:00:00.000Z\",\"units\":\"mmol\"}"
        val profile = ProfileSealed.Pure(pureProfileFromJson(JSONObject(profileJSON), dateUtil)!!)

        // Call
        val result = medtrumPump.buildMedtrumProfileArray(profile)

        // Expected values
        val expectedByteArray = byteArrayOf(7, 0, -96, 2, -16, 96, 2, 104, 33, 2, -32, -31, 1, -64, 3, 2, -20, 36, 2, 100, -123, 2)
        assertEquals(expectedByteArray.contentToString(), result?.contentToString())
    }

    @Test fun buildMedtrumProfileArrayGiveProfileWhenValuesTooHighThenReturnNull() {
        // Inputs
        // Basal profile with 1 element:
        // 00:00 : 600
        val profileJSON = "{\"dia\":\"5\",\"carbratio\":[{\"time\":\"00:00\",\"value\":\"30\"}],\"carbs_hr\":\"20\",\"delay\":\"20\"," +
            "\"sens\":[{\"time\":\"00:00\",\"value\":\"3\"},{\"time\":\"02:00\",\"value\":\"3.4\"}],\"timezone\":\"UTC\"," +
            "\"basal\":[{\"time\":\"00:00\",\"value\":\"600\"}]," +
            "\"target_low\":[{\"time\":\"00:00\",\"value\":\"4.5\"}],\"target_high\":[{\"time\":\"00:00\",\"value\":\"7\"}],\"startDate\":\"1970-01-01T00:00:00.000Z\",\"units\":\"mmol\"}"
        val profile = ProfileSealed.Pure(pureProfileFromJson(JSONObject(profileJSON), dateUtil)!!)

        // Call
        val result = medtrumPump.buildMedtrumProfileArray(profile)

        // Expected values
        assertNull(result)
    }
}