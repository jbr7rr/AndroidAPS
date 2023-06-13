package info.nightscout.pump.medtrum

import info.nightscout.androidaps.TestBaseWithProfile
import info.nightscout.shared.sharedPreferences.SP
import info.nightscout.interfaces.profile.Instantiator
import info.nightscout.interfaces.pump.PumpSync
import info.nightscout.interfaces.pump.TemporaryBasalStorage
import info.nightscout.interfaces.stats.TddCalculator
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mock

open class MedtrumTestBase: TestBaseWithProfile() {

    @Mock lateinit var sp: SP
    @Mock lateinit var instantiator: Instantiator
    @Mock lateinit var tddCalculator: TddCalculator
    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var temporaryBasalStorage: TemporaryBasalStorage

    lateinit var medtrumPump: MedtrumPump

    @BeforeEach
    fun setup() {
        medtrumPump = MedtrumPump(aapsLogger, sp, dateUtil, pumpSync, temporaryBasalStorage)
    }
}