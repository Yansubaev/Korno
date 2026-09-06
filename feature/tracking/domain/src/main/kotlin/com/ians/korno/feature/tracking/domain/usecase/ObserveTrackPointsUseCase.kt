package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Точки трека одной активности. Ошибки чтения схлопываются в пустой список:
 * для отрисовки маршрута отсутствие точек и сбой БД неразличимы.
 */
class ObserveTrackPointsUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(activityId: Long): Flow<List<GeoPoint>> =
        trackRepository.observeGeoPoints(activityId).map { result ->
            (result as? Result.Success)?.data.orEmpty()
        }
}
