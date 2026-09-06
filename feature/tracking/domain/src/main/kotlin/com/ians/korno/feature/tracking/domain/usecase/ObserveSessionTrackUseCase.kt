package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.feature.tracking.domain.repository.TrackingController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Точки текущей сессии записи: сервис пишет их в БД, экран читает оттуда же,
 * поэтому маршрут на карте переживает и смерть процесса, и переоткрытие экрана.
 */
class ObserveSessionTrackUseCase(
    private val trackingController: TrackingController,
    private val observeTrackPoints: ObserveTrackPointsUseCase,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<List<GeoPoint>> = trackingController.observeSession()
        .map { session -> session.activityId }
        .distinctUntilChanged()
        .flatMapLatest { activityId ->
            if (activityId == null) flowOf(emptyList()) else observeTrackPoints(activityId)
        }
}
