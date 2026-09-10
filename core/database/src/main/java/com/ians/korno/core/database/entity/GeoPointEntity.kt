package com.ians.korno.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "geo_points",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["activity_id", "timestamp"])],
)
data class GeoPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "activity_id")
    val activityId: Long,
    @ColumnInfo(name = "lat")
    val latitude: Double,
    @ColumnInfo(name = "lon")
    val longitude: Double,
    val altitude: Double? = null,
    val timestamp: Long,
    val accuracy: Float? = null,
    val speed: Float? = null,
    @ColumnInfo(name = "speed_accuracy")
    val speedAccuracy: Float? = null,
    val bearing: Float? = null,
    @ColumnInfo(name = "bearing_accuracy")
    val bearingAccuracy: Float? = null,
    @ColumnInfo(name = "recording_status", defaultValue = "'RECORDING'")
    val recordingStatus: String = "RECORDING",
    @ColumnInfo(name = "is_stop")
    val isStop: Boolean = false,
    @ColumnInfo(name = "is_outlier")
    val isOutlier: Boolean = false,
    @ColumnInfo(name = "is_manually_edited")
    val isManuallyEdited: Boolean = false,
)
