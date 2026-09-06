package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

internal fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
    val firstLatitude = Math.toRadians(first.latitude)
    val secondLatitude = Math.toRadians(second.latitude)
    val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
    val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
    val haversine = sin(latitudeDelta / 2).pow(2) +
        cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

internal fun accuracyAdjustedDistanceMeters(first: GeoPoint, second: GeoPoint): Double {
    val uncertainty = (first.accuracyMeters ?: 0f) + (second.accuracyMeters ?: 0f)
    return (distanceMeters(first, second) - uncertainty).coerceAtLeast(0.0)
}
