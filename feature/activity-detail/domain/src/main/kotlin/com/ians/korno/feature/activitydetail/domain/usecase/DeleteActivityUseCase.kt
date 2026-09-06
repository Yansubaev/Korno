package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.repository.TrackRepository

class DeleteActivityUseCase(
    private val trackRepository: TrackRepository,
) {
    suspend operator fun invoke(activityId: Long): EmptyResult<DataError.Local> =
        trackRepository.deleteActivity(activityId)
}
