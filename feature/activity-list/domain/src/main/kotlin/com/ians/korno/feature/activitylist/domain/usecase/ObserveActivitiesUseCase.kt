package com.ians.korno.feature.activitylist.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow

class ObserveActivitiesUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(): Flow<Result<List<Activity>, DataError.Local>> = trackRepository.observeActivities()
}
