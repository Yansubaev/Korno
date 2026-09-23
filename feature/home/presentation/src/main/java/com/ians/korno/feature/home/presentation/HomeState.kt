package com.ians.korno.feature.home.presentation

import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.presentation.OngoingRecordingUi

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
    /** Идущая запись; в `recentActivities` и в недельных суммах её нет. */
    val ongoing: OngoingRecordingUi? = null,
)
