package com.ians.korno.core.data.mapper

import com.ians.korno.core.database.entity.ActivitySegmentEntity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.SegmentKind

internal fun ActivitySegmentEntity.toDomain(): ActivitySegment =
    ActivitySegment(
        id = id,
        activityId = activityId,
        kind = SegmentKind.entries.firstOrNull { it.name == kind } ?: SegmentKind.UNKNOWN,
        startTimeEpochMillis = startTimeEpochMillis,
        endTimeEpochMillis = endTimeEpochMillis,
        stepCount = steps,
        distanceMeters = distanceMeters,
        movingTimeMillis = movingTimeMillis,
        isManuallyEdited = isManuallyEdited,
    )

internal fun ActivitySegment.toEntity(): ActivitySegmentEntity =
    ActivitySegmentEntity(
        id = id,
        activityId = activityId,
        kind = kind.name,
        startTimeEpochMillis = startTimeEpochMillis,
        endTimeEpochMillis = endTimeEpochMillis,
        steps = stepCount,
        distanceMeters = distanceMeters,
        movingTimeMillis = movingTimeMillis,
        isManuallyEdited = isManuallyEdited,
    )
