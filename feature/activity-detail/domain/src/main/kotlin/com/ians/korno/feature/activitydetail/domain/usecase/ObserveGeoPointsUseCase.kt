package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow

class ObserveGeoPointsUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>> =
        trackRepository.observeGeoPoints(activityId)
}
