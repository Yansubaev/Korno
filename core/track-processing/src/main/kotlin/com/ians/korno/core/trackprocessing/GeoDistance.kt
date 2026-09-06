package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * Расстояние по дуге большого круга.
 *
 * Формула публичная и одна на весь проект: аналитика считает сплиты по тем же
 * рёбрам, что и статистика активности, и разные реализации гарантированно
 * разъехались бы — сумма сплитов перестала бы сходиться с дистанцией.
 */
fun haversineMeters(
    firstLatitude: Double,
    firstLongitude: Double,
    secondLatitude: Double,
    secondLongitude: Double,
): Double {
    val firstLatitudeRadians = Math.toRadians(firstLatitude)
    val secondLatitudeRadians = Math.toRadians(secondLatitude)
    val latitudeDelta = Math.toRadians(secondLatitude - firstLatitude)
    val longitudeDelta = Math.toRadians(secondLongitude - firstLongitude)
    val haversine = sin(latitudeDelta / 2).pow(2) +
        cos(firstLatitudeRadians) * cos(secondLatitudeRadians) * sin(longitudeDelta / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

internal fun distanceMeters(first: GeoPoint, second: GeoPoint): Double =
    haversineMeters(first.latitude, first.longitude, second.latitude, second.longitude)

internal fun accuracyAdjustedDistanceMeters(first: GeoPoint, second: GeoPoint): Double {
    val uncertainty = (first.accuracyMeters ?: 0f) + (second.accuracyMeters ?: 0f)
    return (distanceMeters(first, second) - uncertainty).coerceAtLeast(0.0)
}
