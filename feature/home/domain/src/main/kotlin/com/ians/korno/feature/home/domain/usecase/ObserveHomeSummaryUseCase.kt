package com.ians.korno.feature.home.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.CalendarPeriodUnit
import com.ians.korno.core.domain.model.dominantKind
import com.ians.korno.core.domain.model.startOfCalendarPeriodMillis
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.feature.home.domain.model.HomeSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

private const val RECENT_ACTIVITIES_CAP = 10

class ObserveHomeSummaryUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(): Flow<Result<HomeSummary, DataError.Local>> =
        combine(
            trackRepository.observeActivities(),
            trackRepository.observeAllSegments(),
        ) { result, segmentsResult ->
            when (result) {
                is Result.Error -> Result.Error(result.error)
                is Result.Success -> {
                    val weekStart = startOfCalendarPeriodMillis(CalendarPeriodUnit.WEEK)
                    val thisWeek = result.data.filter { it.startTimeEpochMillis >= weekStart }
                    val recent = thisWeek.take(RECENT_ACTIVITIES_CAP)
                    val byActivity = (segmentsResult as? Result.Success)?.data.orEmpty()
                        .groupBy(ActivitySegment::activityId)
                    Result.Success(
                        HomeSummary(
                            weekDistanceMeters = thisWeek.sumOf { it.distanceMeters },
                            weekMovingTimeMillis = thisWeek.sumOf { it.movingTimeMillis },
                            recentActivities = recent,
                            dominantKinds = recent.mapNotNull { activity ->
                                byActivity[activity.id]?.dominantKind()?.let { activity.id to it }
                            }.toMap(),
                            hasMoreActivitiesThisWeek = thisWeek.size > RECENT_ACTIVITIES_CAP,
                        ),
                    )
                }
            }
        }
}
