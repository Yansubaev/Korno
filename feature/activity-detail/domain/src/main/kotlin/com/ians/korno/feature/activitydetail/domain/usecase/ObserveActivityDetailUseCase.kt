package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow

class ObserveActivityDetailUseCase(
    private val trackRepository: TrackRepository,
) {
    fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>> =
        trackRepository.observeActivity(activityId)

    fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>> =
        trackRepository.observeGeoPoints(activityId)
}
