package com.ians.korno.feature.home.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.error.map
import com.ians.korno.core.domain.model.CalendarPeriodUnit
import com.ians.korno.core.domain.model.startOfCalendarPeriodMillis
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.feature.home.domain.model.HomeSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val RECENT_ACTIVITIES_CAP = 10

class ObserveHomeSummaryUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(): Flow<Result<HomeSummary, DataError.Local>> =
        trackRepository.observeActivities().map { result ->
            result.map { activities ->
                val weekStart = startOfCalendarPeriodMillis(CalendarPeriodUnit.WEEK)
                val thisWeek = activities.filter { it.startTimeEpochMillis >= weekStart }
                HomeSummary(
                    weekDistanceMeters = thisWeek.sumOf { it.distanceMeters },
                    weekMovingTimeMillis = thisWeek.sumOf { it.movingTimeMillis },
                    recentActivities = thisWeek.take(RECENT_ACTIVITIES_CAP),
                    hasMoreActivitiesThisWeek = thisWeek.size > RECENT_ACTIVITIES_CAP,
                )
            }
        }
}
