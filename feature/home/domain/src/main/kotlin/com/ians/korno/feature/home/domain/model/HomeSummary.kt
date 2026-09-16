package com.ians.korno.feature.home.domain.model

import com.ians.korno.core.domain.model.Activity

data class HomeSummary(
    val weekDistanceMeters: Double,
    val weekMovingTimeMillis: Long,
    val recentActivities: List<Activity>,
    val hasMoreActivitiesThisWeek: Boolean,
)
