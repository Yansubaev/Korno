package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Сегменты активности: экрану сохранения нужен доминирующий вид — лицо записи.
 * Перечитываются на каждое обновление строки активности, своей подписки у них нет.
 */
class ObserveActivitySegmentsUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(activityId: Long): Flow<List<ActivitySegment>> =
        trackRepository.observeActivity(activityId)
            .map { (trackRepository.getSegments(activityId) as? Result.Success)?.data.orEmpty() }
            .distinctUntilChanged()
}
