package com.ians.korno.feature.home.domain.model

import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.SegmentKind

data class HomeSummary(
    val weekDistanceMeters: Double,
    val weekMovingTimeMillis: Long,
    val recentActivities: List<Activity>,
    /** Лицо каждой записи — её доминирующий вид; без сегментов вида нет. */
    val dominantKinds: Map<Long, SegmentKind>,
    val hasMoreActivitiesThisWeek: Boolean,
)
