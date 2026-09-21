package com.ians.korno.feature.home.presentation

import com.ians.korno.core.domain.model.SegmentKind

internal data class RecentActivityUi(
    val id: Long,
    val title: String,
    val dateLabel: String,
    val durationText: String,
    val distanceText: String,
    /** Лицо записи — её доминирующий вид. */
    val kind: SegmentKind,
)

internal data class HomeState(
    val isLoading: Boolean = true,
    val weekDistanceText: String = "",
    val weekTimeText: String = "",
    val recentActivities: List<RecentActivityUi> = emptyList(),
    val hasMoreActivities: Boolean = false,
    val selectedKind: SegmentKind = SegmentKind.RUNNING,
)
