package com.ians.korno.core.data.mapper

import com.ians.korno.core.database.entity.RecognitionSampleEntity
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RecognizedActivity

internal fun RecognitionSampleEntity.toDomain(): RecognitionSample =
    RecognitionSample(
        activityId = activityId,
        timestampEpochMillis = timestamp,
        confidences = confidences.split(",").mapNotNull { entry ->
            val (name, value) = entry.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
            val activity = RecognizedActivity.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            value.toIntOrNull()?.let { activity to it }
        }.toMap(),
    )

internal fun RecognitionSample.toEntity(): RecognitionSampleEntity =
    RecognitionSampleEntity(
        activityId = activityId,
        timestamp = timestampEpochMillis,
        confidences = confidences.entries.joinToString(",") { "${it.key.name}=${it.value}" },
    )
