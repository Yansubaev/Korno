package com.ians.korno.feature.home.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.error.map
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.feature.home.domain.model.HomeSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
private const val RECENT_ACTIVITIES_LIMIT = 3

class ObserveHomeSummaryUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(): Flow<Result<HomeSummary, DataError.Local>> =
        trackRepository.observeActivities().map { result ->
            result.map { activities ->
                val weekStart = System.currentTimeMillis() - WEEK_MILLIS
                val thisWeek = activities.filter { it.startTimeEpochMillis >= weekStart }
                HomeSummary(
                    weekDistanceMeters = thisWeek.sumOf { it.distanceMeters },
                    weekMovingTimeMillis = thisWeek.sumOf { it.movingTimeMillis },
                    recentActivities = activities.take(RECENT_ACTIVITIES_LIMIT),
                )
            }
        }
}
