package app.aaps.database.di

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.aaps.database.entities.TABLE_BOLUSES
import app.aaps.database.entities.TABLE_EFFECTIVE_PROFILE_SWITCHES
import app.aaps.database.entities.TABLE_PROFILE_SWITCHES

/**
 * Wavez fork DB migration 36 -> 37: adds the JSON-encoded `insulinModel` column to the three
 * tables that embed [app.aaps.database.entities.embedments.InsulinConfiguration].
 *
 * Existing rows default to `{"type":"BILINEAR"}`. Rebase procedure if upstream bumps
 * `DATABASE_VERSION`: take upstream's new head, move these three `ALTER TABLE` statements into
 * the new head migration, bump `DATABASE_VERSION` to upstream + 1, regenerate the schema JSON,
 * and update `InsulinConfigMigration36Test`.
 *
 * Note: downgrading back to upstream DB 36 will fail under Room's `fallbackToDestructiveMigration(false)`.
 */
internal val migration36to37 = object : Migration(36, 37) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """ALTER TABLE `$TABLE_BOLUSES` ADD COLUMN `insulinModel` TEXT NOT NULL DEFAULT '{"type":"BILINEAR"}'"""
        )
        connection.execSQL(
            """ALTER TABLE `$TABLE_PROFILE_SWITCHES` ADD COLUMN `insulinModel` TEXT NOT NULL DEFAULT '{"type":"BILINEAR"}'"""
        )
        connection.execSQL(
            """ALTER TABLE `$TABLE_EFFECTIVE_PROFILE_SWITCHES` ADD COLUMN `insulinModel` TEXT NOT NULL DEFAULT '{"type":"BILINEAR"}'"""
        )
        dropCustomIndexes(connection)
    }
}
