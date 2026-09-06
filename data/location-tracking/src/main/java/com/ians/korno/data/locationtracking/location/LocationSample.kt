package com.ians.korno.data.locationtracking.location

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val timestampEpochMillis: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
)
