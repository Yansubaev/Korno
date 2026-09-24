package com.ians.korno.core.data.mapper

import com.ians.korno.core.database.entity.StepSampleEntity
import com.ians.korno.core.domain.model.StepSample

internal fun StepSampleEntity.toDomain(): StepSample =
    StepSample(activityId = activityId, timestampEpochMillis = timestamp, stepCount = stepCount)

internal fun StepSample.toEntity(): StepSampleEntity =
    StepSampleEntity(activityId = activityId, timestamp = timestampEpochMillis, stepCount = stepCount)
