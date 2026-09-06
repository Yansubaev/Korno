package com.ians.korno.feature.home.presentation

import com.ians.korno.core.domain.model.ActivityType

internal data class RecentActivityUi(
    val id: Long,
    val title: String,
    val dateLabel: String,
    val durationText: String,
    val distanceText: String,
    val type: ActivityType,
)

internal data class HomeState(
    val isLoading: Boolean = true,
    val weekDistanceText: String = "",
    val weekTimeText: String = "",
    val recentActivities: List<RecentActivityUi> = emptyList(),
    val selectedType: ActivityType = ActivityType.RUNNING,
)

internal val HOME_ACTIVITY_TYPES = listOf(
    ActivityType.RUNNING,
    ActivityType.CYCLING,
    ActivityType.WALKING,
    ActivityType.DRIVING,
)
