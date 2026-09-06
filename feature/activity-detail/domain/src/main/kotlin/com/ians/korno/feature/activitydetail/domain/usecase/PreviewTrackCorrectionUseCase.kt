package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.trackprocessing.ManualCorrectionUseCase

class PreviewTrackCorrectionUseCase(
    private val manualCorrection: ManualCorrectionUseCase,
) {
    operator fun invoke(
        points: List<GeoPoint>,
        removedPointIds: Set<Long>,
        activityType: ActivityType,
    ): Double = manualCorrection(points, removedPointIds, activityType).statistics.distanceMeters
}
