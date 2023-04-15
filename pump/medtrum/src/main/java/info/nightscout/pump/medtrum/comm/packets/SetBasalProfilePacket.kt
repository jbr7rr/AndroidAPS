package info.nightscout.pump.medtrum.comm.packets

import dagger.android.HasAndroidInjector
import info.nightscout.pump.medtrum.MedtrumPump
import info.nightscout.pump.medtrum.comm.enums.CommandType.SET_BASAL_PROFILE
import info.nightscout.pump.medtrum.extension.toInt
import info.nightscout.pump.medtrum.extension.toLong
import info.nightscout.pump.medtrum.util.MedtrumTimeUtil
import javax.inject.Inject

class SetBasalProfilePacket(injector: HasAndroidInjector, private val basalProfile: ByteArray) : MedtrumPacket(injector) {

    @Inject lateinit var medtrumPump: MedtrumPump

    companion object {

        private const val RESP_BASAL_TYPE_START = 6
        private const val RESP_BASAL_TYPE_END = RESP_BASAL_TYPE_START + 1
        private const val RESP_BASAL_VALUE_START = 7
        private const val RESP_BASAL_VALUE_END = RESP_BASAL_VALUE_START + 2
        private const val RESP_BASAL_SEQUENCE_START = 9
        private const val RESP_BASAL_SEQUENCE_END = RESP_BASAL_SEQUENCE_START + 2
        private const val RESP_BASAL_PATCH_ID_START = 11
        private const val RESP_BASAL_PATCH_ID_END = RESP_BASAL_PATCH_ID_START + 2
        private const val RESP_BASAL_START_TIME_START = 13
        private const val RESP_BASAL_START_TIME_END = RESP_BASAL_START_TIME_START + 4
    }

    init {
        opCode = SET_BASAL_PROFILE.code
        expectedMinRespLength = RESP_BASAL_START_TIME_END

    }

    override fun getRequest(): ByteArray {
        val basalType: Byte = 1 // Fixed to normal basal
        return byteArrayOf(opCode) + basalType + basalProfile
    }

    override fun handleResponse(data: ByteArray): Boolean {
        val success = super.handleResponse(data)
        if (success) {
            val medtrumTimeUtil = MedtrumTimeUtil()
            val basalType = data.copyOfRange(RESP_BASAL_TYPE_START, RESP_BASAL_TYPE_END).toInt()
            val basalValue = data.copyOfRange(RESP_BASAL_VALUE_START, RESP_BASAL_VALUE_END).toInt() * 0.05
            val basalSequence = data.copyOfRange(RESP_BASAL_SEQUENCE_START, RESP_BASAL_SEQUENCE_END).toInt()
            val basalPatchId = data.copyOfRange(RESP_BASAL_PATCH_ID_START, RESP_BASAL_PATCH_ID_END).toInt()
            val basalStartTime = medtrumTimeUtil.convertPumpTimeToSystemTimeSeconds(data.copyOfRange(RESP_BASAL_START_TIME_START, RESP_BASAL_START_TIME_END).toLong())

            // Update the actual basal profile
            medtrumPump.actualBasalProfile = basalProfile
            // TODO: Do we need to let AAPS know? Maybe depends on where we cancel TBR if we need to
            // TODO: Handle history entry
            medtrumPump.handleBasalStatusUpdate(basalType, basalValue, basalSequence, basalPatchId, basalStartTime)
        }
        return success
    }
}