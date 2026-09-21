package com.ians.korno.core.data.mapper

import com.ians.korno.core.database.entity.ActivityEntity
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.RecordingMode

internal fun ActivityEntity.toDomain(): Activity =
    Activity(
        id = id,
        startTimeEpochMillis = startTimeEpochMillis,
        endTimeEpochMillis = endTimeEpochMillis,
        title = title,
        note = note,
        distanceMeters = distanceMeters,
        movingTimeMillis = movingTimeMillis,
        elapsedTimeMillis = elapsedTimeMillis,
        averageSpeedMetersPerSecond = averageSpeedMetersPerSecond,
        maxSpeedMetersPerSecond = maxSpeedMetersPerSecond,
        elevationGainMeters = elevationGainMeters,
        isManuallyEdited = isManuallyEdited,
        isPendingSummary = isPendingSummary,
        stepCount = stepCount,
        recordingMode = RecordingMode.entries.firstOrNull { it.name == recordingMode }
            ?: RecordingMode.FIXED,
        autoPauseSensitivity = AutoPauseSensitivity.entries.firstOrNull { it.name == autoPauseSensitivity }
            ?: AutoPauseSensitivity.MEDIUM,
    )

internal fun Activity.toEntity(): ActivityEntity =
    ActivityEntity(
        id = id,
        startTimeEpochMillis = startTimeEpochMillis,
        endTimeEpochMillis = endTimeEpochMillis,
        title = title,
        note = note,
        distanceMeters = distanceMeters,
        movingTimeMillis = movingTimeMillis,
        elapsedTimeMillis = elapsedTimeMillis,
        averageSpeedMetersPerSecond = averageSpeedMetersPerSecond,
        maxSpeedMetersPerSecond = maxSpeedMetersPerSecond,
        elevationGainMeters = elevationGainMeters,
        isManuallyEdited = isManuallyEdited,
        isPendingSummary = isPendingSummary,
        stepCount = stepCount,
        recordingMode = recordingMode.name,
        autoPauseSensitivity = autoPauseSensitivity.name,
    )
