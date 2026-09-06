package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.repository.TrackRepository

class DiscardActivityUseCase(
    private val trackRepository: TrackRepository,
) {
    suspend operator fun invoke(activityId: Long): EmptyResult<DataError.Local> =
        trackRepository.deleteActivity(activityId)
}
