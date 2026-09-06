package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.trackprocessing.ManualCorrectionUseCase

class SaveTrackCorrectionUseCase(
    private val trackRepository: TrackRepository,
    private val manualCorrection: ManualCorrectionUseCase,
) {
    suspend operator fun invoke(
        activity: Activity,
        points: List<GeoPoint>,
        removedPointIds: Set<Long>,
    ): EmptyResult<DataError.Local> {
        val processed = manualCorrection(points, removedPointIds, activity.activityType)
        val statistics = processed.statistics
        return trackRepository.replaceTrack(
            activity = activity.copy(
                distanceMeters = statistics.distanceMeters,
                movingTimeMillis = statistics.movingTimeMillis,
                averageSpeedMetersPerSecond = statistics.averageSpeedMetersPerSecond,
                maxSpeedMetersPerSecond = statistics.maxSpeedMetersPerSecond,
                elevationGainMeters = statistics.elevationGainMeters,
                isManuallyEdited = true,
            ),
            points = processed.points,
        )
    }
}
