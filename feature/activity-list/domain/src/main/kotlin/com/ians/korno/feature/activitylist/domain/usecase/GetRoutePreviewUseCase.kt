package com.ians.korno.feature.activitylist.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.RoutePoint
import com.ians.korno.core.domain.repository.TrackRepository

/**
 * Маршрут для миниатюры в списке.
 *
 * Точек ровно столько, чтобы силуэт трека читался на квадрате в полсотни пикселей:
 * дальше подробности всё равно не видно, а строк из базы читается меньше.
 */
class GetRoutePreviewUseCase(
    private val trackRepository: TrackRepository,
) {
    suspend operator fun invoke(activityId: Long): Result<List<RoutePoint>, DataError.Local> =
        trackRepository.getRoutePreview(activityId, maxPoints = MAX_PREVIEW_POINTS)

    private companion object {
        const val MAX_PREVIEW_POINTS = 64
    }
}
