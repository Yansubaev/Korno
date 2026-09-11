package com.ians.korno.feature.tracking.presentation

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.map.MapPoint

internal data class TrackingSummaryState(
    val isLoading: Boolean = true,
    val activityType: ActivityType = ActivityType.RUNNING,
    val distanceText: String = "",
    val durationText: String = "",
    val paceText: String = "",
    /** `null` — тип активности шаги не считает. */
    val stepsText: String? = null,
    val title: String = "",
    val note: String = "",
    val routePoints: List<MapPoint> = emptyList(),
    val saved: Boolean = false,
    val discarded: Boolean = false,
)
