package com.ians.korno.data.locationtracking.location

import com.ians.korno.core.domain.model.GpsAccuracy

data class LocationUpdateConfig(
    val intervalMillis: Long,
    val minIntervalMillis: Long,
    val accuracy: GpsAccuracy,
)
