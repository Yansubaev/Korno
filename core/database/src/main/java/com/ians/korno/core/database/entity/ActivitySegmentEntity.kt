package com.ians.korno.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "activity_segments",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["activity_id", "start_ts"])],
)
data class ActivitySegmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "activity_id")
    val activityId: Long,
    val kind: String,
    @ColumnInfo(name = "start_ts")
    val startTimeEpochMillis: Long,
    @ColumnInfo(name = "end_ts")
    val endTimeEpochMillis: Long? = null,
    val steps: Int? = null,
    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double = 0.0,
    @ColumnInfo(name = "is_manually_edited")
    val isManuallyEdited: Boolean = false,
)
