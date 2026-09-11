package com.ians.korno.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "start_time")
    val startTimeEpochMillis: Long,
    @ColumnInfo(name = "end_time")
    val endTimeEpochMillis: Long? = null,
    @ColumnInfo(name = "activity_type")
    val activityType: String,
    val title: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "distance")
    val distanceMeters: Double = 0.0,
    @ColumnInfo(name = "moving_time")
    val movingTimeMillis: Long = 0L,
    @ColumnInfo(name = "elapsed_time")
    val elapsedTimeMillis: Long = 0L,
    @ColumnInfo(name = "avg_speed")
    val averageSpeedMetersPerSecond: Double = 0.0,
    @ColumnInfo(name = "max_speed")
    val maxSpeedMetersPerSecond: Double = 0.0,
    @ColumnInfo(name = "elevation_gain")
    val elevationGainMeters: Double = 0.0,
    @ColumnInfo(name = "is_manually_edited")
    val isManuallyEdited: Boolean = false,
    @ColumnInfo(name = "is_pending_summary", defaultValue = "0")
    val isPendingSummary: Boolean = false,
    @ColumnInfo(name = "step_count")
    val stepCount: Int? = null,
)
