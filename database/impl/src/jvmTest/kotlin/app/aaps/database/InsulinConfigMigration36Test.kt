package app.aaps.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.aaps.database.di.JvmAppDatabaseBuilder
import app.aaps.database.di.databaseMigrations
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.EffectiveProfileSwitch
import app.aaps.database.entities.ProfileSwitch
import app.aaps.database.entities.TABLE_BOLUSES
import app.aaps.database.entities.TABLE_EFFECTIVE_PROFILE_SWITCHES
import app.aaps.database.entities.TABLE_PROFILE_SWITCHES
import app.aaps.database.entities.data.Block
import app.aaps.database.entities.data.GlucoseUnit
import app.aaps.database.entities.data.TargetBlock
import app.aaps.database.entities.embedments.InsulinConfiguration
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Verifies the Wavez DB 36 -> 37 migration adding `insulinModel` to the three tables
 * that embed [InsulinConfiguration]: `boluses`, `profileSwitches`, and `effectiveProfileSwitches`.
 */
class InsulinConfigMigration36Test {

    private val directory = createTempDirectory("aaps-insulin-migration-36-test")
    private val dbPath = "$directory/aaps.db"
    private var connection: SQLiteConnection? = null

    @AfterTest
    fun tearDown() {
        connection?.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `database version and head migration match fork target 36 to 37`() {
        // Fails immediately if upstream bumps DATABASE_VERSION during a rebase while our
        // fork migration still targets 36 -> 37. Rebase procedure: adopt upstream's new head,
        // move the ALTER TABLE statements into the new head migration, bump DATABASE_VERSION to
        // upstream + 1, regenerate the schema JSON, and update this test.
        assertThat(DATABASE_VERSION).isEqualTo(37)
        val head = databaseMigrations.last()
        assertThat(head.startVersion).isEqualTo(36)
        assertThat(head.endVersion).isEqualTo(37)
    }

    @Test
    fun `migration 36 to 37 adds default bilinear insulinModel across all three tables`() {
        val conn = BundledSQLiteDriver().open(dbPath).also { connection = it }
        createV36Tables(conn)
        insertV36Bolus(conn, amount = 2.5, label = "Rapid", endTime = DIA_6H, peak = PEAK_75M)
        insertV36ProfileSwitch(conn, label = "Rapid", endTime = DIA_6H, peak = PEAK_75M)
        insertV36EffectiveProfileSwitch(conn, label = "Lyumjev", endTime = DIA_5H, peak = PEAK_45M)

        databaseMigrations.single { it.startVersion == 36 && it.endVersion == 37 }.migrate(conn)

        assertThat(insulinModelOf(conn, TABLE_BOLUSES)).isEqualTo(BILINEAR_JSON)
        assertThat(insulinModelOf(conn, TABLE_PROFILE_SWITCHES)).isEqualTo(BILINEAR_JSON)
        assertThat(insulinModelOf(conn, TABLE_EFFECTIVE_PROFILE_SWITCHES)).isEqualTo(BILINEAR_JSON)
        assertThat(labelOf(conn, TABLE_BOLUSES)).isEqualTo("Rapid")
        assertThat(labelOf(conn, TABLE_PROFILE_SWITCHES)).isEqualTo("Rapid")
        assertThat(labelOf(conn, TABLE_EFFECTIVE_PROFILE_SWITCHES)).isEqualTo("Lyumjev")
    }

    @Test
    fun `pd rows survive insert and database reopen at version 37`() = runTest {
        val pdConfig = InsulinConfiguration(
            insulinLabel = "Custom PD 51m 8h U100",
            insulinEndTime = DIA_8H,
            insulinPeakTime = PEAK_51M,
            concentration = 1.0,
            insulinModel = PD_JSON
        )
        val dayMs = 24L * 3600 * 1000
        val blocks = listOf(Block(dayMs, 1.0))
        val targets = listOf(TargetBlock(dayMs, 90.0, 90.0))

        val db1 = JvmAppDatabaseBuilder().provideAppDatabase(dbPath)
        try {
            db1.bolusDao.insert(
                Bolus(
                    timestamp = 1_700_000_000_000L,
                    utcOffset = 0L,
                    amount = 1.5,
                    type = Bolus.Type.NORMAL,
                    insulinConfiguration = pdConfig
                )
            )
            db1.profileSwitchDao.insert(
                ProfileSwitch(
                    timestamp = 1_700_000_000_000L,
                    utcOffset = 0L,
                    basalBlocks = blocks,
                    isfBlocks = blocks,
                    icBlocks = blocks,
                    targetBlocks = targets,
                    glucoseUnit = GlucoseUnit.MGDL,
                    profileName = "Default",
                    timeshift = 0L,
                    percentage = 100,
                    duration = 0L,
                    insulinConfiguration = pdConfig
                )
            )
            db1.effectiveProfileSwitchDao.insert(
                EffectiveProfileSwitch(
                    timestamp = 1_700_000_000_000L,
                    utcOffset = 0L,
                    basalBlocks = blocks,
                    isfBlocks = blocks,
                    icBlocks = blocks,
                    targetBlocks = targets,
                    glucoseUnit = GlucoseUnit.MGDL,
                    originalProfileName = "Default",
                    originalCustomizedName = "Default",
                    originalTimeshift = 0L,
                    originalPercentage = 100,
                    originalDuration = 0L,
                    originalEnd = 0L,
                    insulinConfiguration = pdConfig
                )
            )
        } finally {
            db1.close()
        }

        val db2 = JvmAppDatabaseBuilder().provideAppDatabase(dbPath)
        try {
            val bolus = db2.bolusDao.getLastBolusRecord()
            val ps = db2.profileSwitchDao.getAllProfileSwitches().single()
            val eps = db2.effectiveProfileSwitchDao.getAllEffectiveProfileSwitches().single()

            assertThat(bolus?.insulinConfiguration).isEqualTo(pdConfig)
            assertThat(ps.insulinConfiguration).isEqualTo(pdConfig)
            assertThat(eps.insulinConfiguration).isEqualTo(pdConfig)
        } finally {
            db2.close()
        }
    }

    private fun insulinModelOf(conn: SQLiteConnection, table: String): String =
        conn.prepare("SELECT insulinModel FROM `$table`").use { st ->
            check(st.step()) { "no row in $table" }
            st.getText(0)
        }

    private fun labelOf(conn: SQLiteConnection, table: String): String =
        conn.prepare("SELECT insulinLabel FROM `$table`").use { st ->
            check(st.step()) { "no row in $table" }
            st.getText(0)
        }

    private fun insertV36Bolus(conn: SQLiteConnection, amount: Double, label: String, endTime: Long, peak: Long) =
        conn.execSQL(
            "INSERT INTO `$TABLE_BOLUSES` (version, dateCreated, isValid, timestamp, utcOffset, amount, type, isBasalInsulin, insulinLabel, insulinEndTime, insulinPeakTime, concentration) " +
                "VALUES (1, 0, 1, 1700000000000, 0, $amount, 'NORMAL', 0, '$label', $endTime, $peak, 1.0)"
        )

    private fun insertV36ProfileSwitch(conn: SQLiteConnection, label: String, endTime: Long, peak: Long) =
        conn.execSQL(
            "INSERT INTO `$TABLE_PROFILE_SWITCHES` (version, dateCreated, isValid, timestamp, utcOffset, basalBlocks, isfBlocks, icBlocks, targetBlocks, glucoseUnit, profileName, timeshift, percentage, duration, insulinLabel, insulinEndTime, insulinPeakTime, concentration) " +
                "VALUES (1, 0, 1, 1700000000000, 0, '[]', '[]', '[]', '[]', 'MGDL', 'Adult', 0, 100, 0, '$label', $endTime, $peak, 1.0)"
        )

    private fun insertV36EffectiveProfileSwitch(conn: SQLiteConnection, label: String, endTime: Long, peak: Long) =
        conn.execSQL(
            "INSERT INTO `$TABLE_EFFECTIVE_PROFILE_SWITCHES` (version, dateCreated, isValid, timestamp, utcOffset, basalBlocks, isfBlocks, icBlocks, targetBlocks, glucoseUnit, originalProfileName, originalCustomizedName, originalTimeshift, originalPercentage, originalDuration, originalEnd, insulinLabel, insulinEndTime, insulinPeakTime, concentration) " +
                "VALUES (1, 0, 1, 1700000000000, 0, '[]', '[]', '[]', '[]', 'MGDL', 'Adult', 'Adult', 0, 100, 0, 0, '$label', $endTime, $peak, 1.0)"
        )

    /** Exact table declarations from `36.json`. */
    private fun createV36Tables(conn: SQLiteConnection) {
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `$TABLE_BOLUSES` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `version` INTEGER NOT NULL, " +
                "`dateCreated` INTEGER NOT NULL, `isValid` INTEGER NOT NULL, `referenceId` INTEGER, `timestamp` INTEGER NOT NULL, " +
                "`utcOffset` INTEGER NOT NULL, `amount` REAL NOT NULL, `type` TEXT NOT NULL, `notes` TEXT, `isBasalInsulin` INTEGER NOT NULL, " +
                "`nightscoutSystemId` TEXT, `nightscoutId` TEXT, `pumpType` TEXT, `pumpSerial` TEXT, `temporaryId` INTEGER, `pumpId` INTEGER, " +
                "`startId` INTEGER, `endId` INTEGER, `insulinLabel` TEXT NOT NULL, `insulinEndTime` INTEGER NOT NULL, `insulinPeakTime` INTEGER NOT NULL, " +
                "`concentration` REAL NOT NULL, FOREIGN KEY(`referenceId`) REFERENCES `boluses`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `$TABLE_EFFECTIVE_PROFILE_SWITCHES` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `version` INTEGER NOT NULL, " +
                "`dateCreated` INTEGER NOT NULL, `isValid` INTEGER NOT NULL, `referenceId` INTEGER, `timestamp` INTEGER NOT NULL, `utcOffset` INTEGER NOT NULL, " +
                "`basalBlocks` TEXT NOT NULL, `isfBlocks` TEXT NOT NULL, `icBlocks` TEXT NOT NULL, `targetBlocks` TEXT NOT NULL, `glucoseUnit` TEXT NOT NULL, " +
                "`originalProfileName` TEXT NOT NULL, `originalCustomizedName` TEXT NOT NULL, `originalTimeshift` INTEGER NOT NULL, `originalPercentage` INTEGER NOT NULL, " +
                "`originalDuration` INTEGER NOT NULL, `originalEnd` INTEGER NOT NULL, `originalPsId` INTEGER, `nightscoutSystemId` TEXT, `nightscoutId` TEXT, " +
                "`pumpType` TEXT, `pumpSerial` TEXT, `temporaryId` INTEGER, `pumpId` INTEGER, `startId` INTEGER, `endId` INTEGER, " +
                "`insulinLabel` TEXT NOT NULL, `insulinEndTime` INTEGER NOT NULL, `insulinPeakTime` INTEGER NOT NULL, `concentration` REAL NOT NULL, " +
                "FOREIGN KEY(`referenceId`) REFERENCES `effectiveProfileSwitches`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `$TABLE_PROFILE_SWITCHES` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `version` INTEGER NOT NULL, " +
                "`dateCreated` INTEGER NOT NULL, `isValid` INTEGER NOT NULL, `referenceId` INTEGER, `timestamp` INTEGER NOT NULL, `utcOffset` INTEGER NOT NULL, " +
                "`basalBlocks` TEXT NOT NULL, `isfBlocks` TEXT NOT NULL, `icBlocks` TEXT NOT NULL, `targetBlocks` TEXT NOT NULL, `glucoseUnit` TEXT NOT NULL, " +
                "`profileName` TEXT NOT NULL, `timeshift` INTEGER NOT NULL, `percentage` INTEGER NOT NULL, `duration` INTEGER NOT NULL, " +
                "`nightscoutSystemId` TEXT, `nightscoutId` TEXT, `pumpType` TEXT, `pumpSerial` TEXT, `temporaryId` INTEGER, `pumpId` INTEGER, " +
                "`startId` INTEGER, `endId` INTEGER, `insulinLabel` TEXT NOT NULL, `insulinEndTime` INTEGER NOT NULL, `insulinPeakTime` INTEGER NOT NULL, " +
                "`concentration` REAL NOT NULL, FOREIGN KEY(`referenceId`) REFERENCES `profileSwitches`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )"
        )
    }

    private companion object {
        const val BILINEAR_JSON = """{"type":"BILINEAR"}"""
        const val PD_JSON = """{"type":"PD","a0":51.0,"a1":12.27,"b1":0.05185,"concentrationScalesPeak":false,"durationMinutes":480}"""
        const val DIA_5H = 5L * 3600 * 1000
        const val DIA_6H = 6L * 3600 * 1000
        const val DIA_8H = 8L * 3600 * 1000
        const val PEAK_45M = 45L * 60 * 1000
        const val PEAK_51M = 51L * 60 * 1000
        const val PEAK_75M = 75L * 60 * 1000
    }
}
