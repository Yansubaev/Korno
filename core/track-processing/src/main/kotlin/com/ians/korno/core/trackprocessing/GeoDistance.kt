package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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

/**
 * Насколько отрезок между точками может оказаться дрожанием, а не движением.
 *
 * Берётся худшая из двух погрешностей и ограничивается [capMeters]. Суммировать
 * погрешности обеих точек, как делалось раньше, нельзя: при типичных для города
 * 20–25 м из каждого отрезка вычиталось под полсотни метров, любой шаг пешехода
 * превращался в ноль, и фильтр выбросов переставал срабатывать вовсе. Потолок нужен
 * по той же причине: без него порог выброса раздувается ровно там, где сигнал плох.
 */
internal fun accuracyAllowanceMeters(
    first: GeoPoint,
    second: GeoPoint,
    capMeters: Double,
): Double {
    val uncertainty = max(first.accuracyMeters ?: 0f, second.accuracyMeters ?: 0f).toDouble()
    return uncertainty.coerceIn(0.0, capMeters)
}

/**
 * Шумовой пол отрезка: смещение меньше него неотличимо от дрожания приёмника.
 *
 * Берётся лучшая из двух погрешностей — порог должен отсекать заведомый шум, а не
 * съедать реальное перемещение из-за одной неудачной точки.
 */
internal fun noiseFloorMeters(first: GeoPoint, second: GeoPoint, factor: Double): Double {
    val uncertainty = min(first.accuracyMeters ?: 0f, second.accuracyMeters ?: 0f).toDouble()
    return (uncertainty * factor).coerceAtLeast(0.0)
}
