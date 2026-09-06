package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow

class ObserveActivityUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(activityId: Long): Flow<Result<Activity, DataError.Local>> =
        trackRepository.observeActivity(activityId)
}
