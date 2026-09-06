package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint

class ManualCorrectionUseCase(
    private val trackProcessor: TrackProcessor,
) {
    operator fun invoke(
        points: List<GeoPoint>,
        removedPointIds: Set<Long>,
        activityType: ActivityType,
    ): ProcessedTrack = trackProcessor.process(
        points = points
            .filterNot { it.id in removedPointIds }
            .map { it.copy(isManuallyEdited = true) },
        activityType = activityType,
    )
}
