package com.ians.korno.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recognition_samples",
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
data class RecognitionSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "activity_id")
    val activityId: Long,
    val timestamp: Long,
    /** Уверенности строкой `WALKING=80,ON_FOOT=80,IN_VEHICLE=6`: читаются только целиком. */
    val confidences: String,
)
