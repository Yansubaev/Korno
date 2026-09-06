package com.ians.korno.core.data.mapper

import com.ians.korno.core.database.entity.ActivityEntity
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType

internal fun ActivityEntity.toDomain(): Activity =
    Activity(
        id = id,
        startTimeEpochMillis = startTimeEpochMillis,
        endTimeEpochMillis = endTimeEpochMillis,
        activityType = activityType.toActivityType(),
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
    )

internal fun Activity.toEntity(): ActivityEntity =
    ActivityEntity(
        id = id,
        startTimeEpochMillis = startTimeEpochMillis,
        endTimeEpochMillis = endTimeEpochMillis,
        activityType = activityType.name,
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
    )

private fun String.toActivityType(): ActivityType =
    ActivityType.entries.firstOrNull { it.name == this } ?: ActivityType.OTHER
