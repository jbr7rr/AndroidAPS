package app.aaps.implementation.maintenance.migration

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.data.model.InsulinModelCodec
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.StringNonKey
import app.aaps.core.objects.extensions.fromJsonObject
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class InsulinMigrationPdTest {

    private val rh = generatedTextResolver()

    private fun sut(): InsulinMigration {
        val hardLimits = mock<HardLimits>()
        whenever(hardLimits.diaRange()).thenReturn(5.0..9.0)
        return InsulinMigration(mock<AAPSLogger>(), hardLimits, rh)
    }

    private fun preferenceMigrations(client: Boolean = false): PreferenceMigrations {
        val config = mock<Config>()
        whenever(config.AAPSCLIENT).thenReturn(client)
        whenever(config.APS).thenReturn(true)
        return PreferenceMigrations(mock<AAPSLogger>(), config, mock<PersistenceLayer>(), mock<DateUtil>(), mock<ProfileUtil>())
    }

    private fun storeOf(vararg pairs: Pair<String, Any?>) = FileKeyValueStore(mapOf(*pairs))

    // ── readLegacyInsulin for the three v3 PD plugins ──────────────────────────────────────────────

    @Test
    fun `reads Custom PD plugin and stored parameters`() {
        val store = storeOf(
            "ConfigBuilder_Enabled_INSULIN_InsulinCustomPDPlugin" to true,
            "insulin_pd_free_peak_a0" to "51.0",
            "insulin_pd_free_peak_a1" to "12.27",
            "insulin_pd_free_peak_b1" to "0.05185"
        )

        val legacy = sut().readLegacyInsulin(store)

        assertThat(legacy.fromExplicitKey).isTrue()
        assertThat(legacy.peakTime).isEqualTo(51L * 60 * 1000)
        assertThat(legacy.concentration).isEqualTo(1.0)
        assertThat(legacy.insulinModel).isEqualTo(
            InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false, durationMinutes = 480)
        )
    }

    @Test
    fun `Custom PD defaults missing a0 to 51 0 when keys were never customized`() {
        val store = storeOf("ConfigBuilder_INSULIN_InsulinCustomPDPlugin_Enabled" to true)

        val legacy = sut().readLegacyInsulin(store)

        assertThat(legacy.fromExplicitKey).isTrue()
        assertThat(legacy.peakTime).isEqualTo(51L * 60 * 1000)
        assertThat(legacy.insulinModel).isEqualTo(
            InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false, durationMinutes = 480)
        )
    }

    @Test
    fun `Custom PD with invalid stored parameter remains PD and unusable`() {
        val store = storeOf(
            "ConfigBuilder_Enabled_INSULIN_InsulinCustomPDPlugin" to true,
            "insulin_pd_free_peak_a0" to "-5.0",
            "insulin_pd_free_peak_a1" to "12.27",
            "insulin_pd_free_peak_b1" to "0.05185"
        )

        val legacy = sut().readLegacyInsulin(store)

        assertThat(legacy.insulinModel).isInstanceOf(InsulinModel.Pd::class.java)
        assertThat(legacy.insulinModel.isUsable).isFalse()
        assertThat(legacy.peakTime).isGreaterThan(0L)
    }

    @Test
    fun `reads Lyumjev U100 PD plugin with concentration 1 and scaled peak`() {
        val store = storeOf("ConfigBuilder_Enabled_INSULIN_InsulinLyumjevU100PDPlugin" to true)

        val legacy = sut().readLegacyInsulin(store)

        assertThat(legacy.fromExplicitKey).isTrue()
        assertThat(legacy.peakTime).isEqualTo(65L * 60 * 1000)
        assertThat(legacy.concentration).isEqualTo(1.0)
        assertThat(legacy.insulinModel).isEqualTo(
            InsulinModel.Pd(61.33, 12.27, 0.05185, concentrationScalesPeak = true, durationMinutes = 480)
        )
    }

    @Test
    fun `reads Lyumjev U200 PD plugin with concentration 2 and scaled peak`() {
        val store = storeOf("ConfigBuilder_INSULIN_InsulinLyumjevU200PDPlugin_Enabled" to true)

        val legacy = sut().readLegacyInsulin(store)

        assertThat(legacy.fromExplicitKey).isTrue()
        assertThat(legacy.peakTime).isEqualTo(65L * 60 * 1000)
        assertThat(legacy.concentration).isEqualTo(2.0)
        assertThat(legacy.insulinModel).isEqualTo(
            InsulinModel.Pd(61.33, 12.27, 0.05185, concentrationScalesPeak = true, durationMinutes = 480)
        )
    }

    // ── plan() for v3 PD records and legacy backups ────────────────────────────────────────────────

    @Test
    fun `plan enriches v3 Custom PD profile switch row by label and normalizes DIA to 8h`() {
        val v3Row = ICfg("Custom PD", 6L * 3600 * 1000, 51L * 60 * 1000, 1.0, InsulinModel.Bilinear)
        val legacy = sut().readLegacyInsulin(
            storeOf(
                "ConfigBuilder_Enabled_INSULIN_InsulinCustomPDPlugin" to true,
                "insulin_pd_free_peak_a0" to 48.0,
                "insulin_pd_free_peak_a1" to 11.5,
                "insulin_pd_free_peak_b1" to 0.05
            )
        )

        val plan = sut().plan(v3Row, legacy, ProfileDiaHarvest(emptyMap(), emptyList()), "Adult")

        assertThat(plan.source).isEqualTo(InsulinMigrationPlan.Source.RECORD)
        assertThat(plan.iCfg.dia).isEqualTo(8.0)
        assertThat(plan.iCfg.insulinModel).isEqualTo(
            InsulinModel.Pd(48.0, 11.5, 0.05, concentrationScalesPeak = false, durationMinutes = 480)
        )
        assertThat(plan.iCfg.concentration).isEqualTo(1.0)
    }

    @Test
    fun `in-place 3_4 Custom PD upgrade with 5h profile DIA matches PreferenceMigrations 8h curve without duplicating`() = runTest {
        val store = storeOf(
            "ConfigBuilder_Enabled_INSULIN_InsulinCustomPDPlugin" to true,
            "insulin_pd_free_peak_a0" to "51.0",
            "insulin_pd_free_peak_a1" to "12.27",
            "insulin_pd_free_peak_b1" to "0.05185"
        )
        val legacy = sut().readLegacyInsulin(store)
        preferenceMigrations().migrate(store)

        val rawCfg = store.getString(StringNonKey.InsulinConfiguration.key, "")
        val seededFromPrefs = ICfg.fromJsonObject(((Json.parseToJsonElement(rawCfg) as JsonObject)["insulin"] as JsonArray).single() as JsonObject)

        val v3Row = ICfg("Custom PD", 5L * 3600 * 1000, 51L * 60 * 1000, 1.0, InsulinModel.Bilinear)
        val plan = sut().plan(v3Row, legacy, ProfileDiaHarvest(emptyMap(), emptyList()), "Adult")

        assertThat(plan.iCfg.dia).isEqualTo(8.0)
        assertThat(seededFromPrefs.hasSameCurve(plan.iCfg)).isTrue()
    }

    @Test
    fun `plan enriches v3 Lyumjev U200 Tsunami row with PD model, 8h DIA, and concentration 2`() {
        val v3Row = ICfg("Lyumjev U200 (Tsunami)", 6L * 3600 * 1000, 65L * 60 * 1000, 1.0, InsulinModel.Bilinear)

        val plan = sut().plan(v3Row, null, ProfileDiaHarvest(emptyMap(), emptyList()), "Adult")

        assertThat(plan.source).isEqualTo(InsulinMigrationPlan.Source.RECORD)
        assertThat(plan.iCfg.dia).isEqualTo(8.0)
        assertThat(plan.iCfg.concentration).isEqualTo(2.0)
        assertThat(plan.iCfg.insulinModel).isEqualTo(
            InsulinModel.Pd(61.33, 12.27, 0.05185, concentrationScalesPeak = true, durationMinutes = 480)
        )
        // 4 U actual U200 bolus (2 U pump volume) matches 3.4 cU=2.0 peak without double-scaling.
        assertThat(plan.iCfg.peakMinutesForDose(4.0)).isWithin(1e-6).of((61.33 + 12.27 * 4.0) / (1.0 + 0.05185 * 4.0 / 2.0))
    }

    @Test
    fun `plan builds 8h PD ICfg from legacy keys when no record exists`() {
        val legacy = sut().readLegacyInsulin(storeOf("ConfigBuilder_Enabled_INSULIN_InsulinLyumjevU100PDPlugin" to true))

        val plan = sut().plan(null, legacy, ProfileDiaHarvest(emptyMap(), emptyList()), null)

        assertThat(plan.source).isEqualTo(InsulinMigrationPlan.Source.LEGACY_KEYS)
        assertThat(plan.isGuess).isFalse()
        assertThat(plan.iCfg.dia).isEqualTo(8.0)
        assertThat(plan.iCfg.peak).isEqualTo(65)
        assertThat(plan.iCfg.concentration).isEqualTo(1.0)
        assertThat(plan.iCfg.insulinModel).isEqualTo(
            InsulinModel.Pd(61.33, 12.27, 0.05185, concentrationScalesPeak = true, durationMinutes = 480)
        )
    }

    // ── PreferenceMigrations: cleanup + seeding InsulinConfiguration on backup import ─────────────

    @Test
    fun `preference migration seeds InsulinConfiguration for Custom PD backup and removes old keys`() = runTest {
        val store = storeOf(
            "ConfigBuilder_INSULIN_InsulinCustomPDPlugin_Enabled" to true,
            "insulin_pd_free_peak_a0" to "51.0",
            "insulin_pd_free_peak_a1" to "12.27",
            "insulin_pd_free_peak_b1" to "0.05185"
        )

        preferenceMigrations().migrate(store)

        assertThat(store.contains("ConfigBuilder_INSULIN_InsulinCustomPDPlugin_Enabled")).isFalse()
        assertThat(store.contains("ConfigBuilder_Enabled_INSULIN_InsulinCustomPDPlugin")).isFalse()
        assertThat(store.contains("insulin_pd_free_peak_a0")).isFalse()
        assertThat(store.contains("insulin_pd_free_peak_a1")).isFalse()
        assertThat(store.contains("insulin_pd_free_peak_b1")).isFalse()

        val rawCfg = store.getString(StringNonKey.InsulinConfiguration.key, "")
        assertThat(rawCfg).isNotEmpty()
        val array = (Json.parseToJsonElement(rawCfg) as JsonObject)["insulin"] as JsonArray
        val imported = ICfg.fromJsonObject(array.single() as JsonObject)
        assertThat(imported.peak).isEqualTo(51)
        assertThat(imported.dia).isEqualTo(8.0)
        assertThat(imported.concentration).isEqualTo(1.0)
        assertThat(imported.insulinModel).isEqualTo(
            InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false, durationMinutes = 480)
        )
    }

    @Test
    fun `preference migration does not overwrite existing InsulinConfiguration`() = runTest {
        val existing = """{"insulin":[{"insulinLabel":"Keep","insulinEndTime":21600000,"insulinPeakTime":4500000,"concentration":1.0}]}"""
        val store = storeOf(
            StringNonKey.InsulinConfiguration.key to existing,
            "ConfigBuilder_Enabled_INSULIN_InsulinLyumjevU100PDPlugin" to true
        )

        preferenceMigrations().migrate(store)

        assertThat(store.getString(StringNonKey.InsulinConfiguration.key, "")).isEqualTo(existing)
        assertThat(store.contains("ConfigBuilder_Enabled_INSULIN_InsulinLyumjevU100PDPlugin")).isFalse()
    }

    // ── Backfill policy: label mapping, 8h bolus cutoff, never stamp invalid PD onto boluses ──────

    @Test
    fun `backfill policy maps bolus 1h old to PD and bolus 9h old to bilinear`() {
        val now = 1_700_000_000_000L
        val pd = InsulinModel.Pd(51.0, 12.27, 0.05185, concentrationScalesPeak = false, durationMinutes = 480)
        val running = ICfg("Custom PD 51m 8h U100", 8L * 3600 * 1000, 51L * 60 * 1000, 1.0, pd)

        val bolusModelRecent = sut().bolusModelForBackfill(running, bolusTimestamp = now - 1L * 3600 * 1000, now = now)
        val bolusModelOld = sut().bolusModelForBackfill(running, bolusTimestamp = now - 9L * 3600 * 1000, now = now)

        assertThat(bolusModelRecent).isEqualTo(pd)
        assertThat(bolusModelOld).isEqualTo(InsulinModel.Bilinear)
    }

    @Test
    fun `backfill policy never stamps an invalid PD model onto a bolus`() {
        val now = 1_700_000_000_000L
        val broken = ICfg(
            "Custom PD",
            8L * 3600 * 1000,
            51L * 60 * 1000,
            1.0,
            InsulinModel.Pd(a0 = 0.0, a1 = 12.27, b1 = 0.05185)
        )

        val bolusModelRecent = sut().bolusModelForBackfill(broken, bolusTimestamp = now - 30L * 60 * 1000, now = now)
        assertThat(bolusModelRecent).isEqualTo(InsulinModel.Bilinear)

        val plan = sut().bolusBackfillPlan(broken, now = now)
        assertThat(plan.recentModelJson).isEqualTo(InsulinModelCodec.DEFAULT_BILINEAR_JSON)
        assertThat(plan.olderModelJson).isEqualTo(InsulinModelCodec.DEFAULT_BILINEAR_JSON)
    }

    @Test
    fun `legacy label rules cover all three v3 PD labels without using peak`() {
        val legacyCustom = sut().readLegacyInsulin(
            storeOf(
                "ConfigBuilder_Enabled_INSULIN_InsulinCustomPDPlugin" to true,
                "insulin_pd_free_peak_a0" to 52.0,
                "insulin_pd_free_peak_a1" to 12.0,
                "insulin_pd_free_peak_b1" to 0.05
            )
        )

        val rules = sut().legacyProfileSwitchPdRules(legacyCustom)
        val byLabel = rules.associateBy { it.legacyLabel }

        assertThat(byLabel.keys).containsExactly(
            "Lyumjev U100 (Tsunami)",
            "Lyumjev U200 (Tsunami)",
            "Custom PD"
        )
        assertThat(byLabel["Lyumjev U100 (Tsunami)"]?.concentration).isEqualTo(1.0)
        assertThat(byLabel["Lyumjev U200 (Tsunami)"]?.concentration).isEqualTo(2.0)
        assertThat(byLabel["Custom PD"]?.model).isEqualTo(
            InsulinModel.Pd(52.0, 12.0, 0.05, concentrationScalesPeak = false, durationMinutes = 480)
        )
    }
}
