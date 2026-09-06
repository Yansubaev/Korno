package com.ians.korno.core.data.mapper

import com.ians.korno.core.database.entity.GeoPointEntity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus

internal fun GeoPointEntity.toDomain(): GeoPoint =
    GeoPoint(
        id = id,
        activityId = activityId,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitude,
        timestampEpochMillis = timestamp,
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        recordingStatus = recordingStatus.toRecordingStatus(),
        isStop = isStop,
        isOutlier = isOutlier,
        isManuallyEdited = isManuallyEdited,
    )

internal fun GeoPoint.toEntity(): GeoPointEntity =
    GeoPointEntity(
        id = id,
        activityId = activityId,
        latitude = latitude,
        longitude = longitude,
        altitude = altitudeMeters,
        timestamp = timestampEpochMillis,
        accuracy = accuracyMeters,
        speed = speedMetersPerSecond,
        recordingStatus = recordingStatus.name,
        isStop = isStop,
        isOutlier = isOutlier,
        isManuallyEdited = isManuallyEdited,
    )

private fun String.toRecordingStatus(): RecordingStatus =
    RecordingStatus.entries.firstOrNull { it.name == this } ?: RecordingStatus.RECORDING
