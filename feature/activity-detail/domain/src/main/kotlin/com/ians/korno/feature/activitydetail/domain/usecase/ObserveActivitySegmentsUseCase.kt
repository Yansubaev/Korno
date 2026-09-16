package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Сегменты активности. Отдельной подписки на таблицу нет: сегменты меняются только вместе
 * со строкой активности (запись, смена вида с пересчётом), поэтому они перечитываются на
 * каждое её обновление.
 */
class ObserveActivitySegmentsUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(activityId: Long): Flow<List<ActivitySegment>> =
        trackRepository.observeActivity(activityId)
            .map { (trackRepository.getSegments(activityId) as? Result.Success)?.data.orEmpty() }
            .distinctUntilChanged()
}
