package app.aaps.database.entities.embedments

import androidx.room.ColumnInfo

data class InsulinConfiguration(
    var insulinLabel: String,
    var insulinEndTime: Long,  // DIA before [milliseconds]
    var insulinPeakTime: Long, // [milliseconds]  ** Here I propose to rename this value to be consistent with content in milliseconds (and not in min for peak everywhere else)
    var concentration: Double, // multiplication factor, 1.0 for U100, 2.0 for U200...
    @ColumnInfo(defaultValue = "'{\"type\":\"BILINEAR\"}'")
    var insulinModel: String = """{"type":"BILINEAR"}"""
)