package com.ians.korno.feature.activitylist.domain.usecase

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.RoutePoint
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Силуэты маршрутов для миниатюр списка по id активности. Готовые, из базы: список не
 * читает ради картинки треки целиком. Ошибка чтения — просто миниатюры не будет.
 */
class ObserveRoutePreviewsUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(): Flow<Map<Long, List<RoutePoint>>> =
        trackRepository.observeRoutePreviews().map { (it as? Result.Success)?.data.orEmpty() }
}
