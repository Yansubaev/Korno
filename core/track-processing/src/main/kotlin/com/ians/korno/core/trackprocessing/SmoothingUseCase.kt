package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.TRACK_GAP_THRESHOLD_MILLIS

/**
 * Погрешность, приписываемая точке, о точности которой провайдер ничего не сказал.
 * Значение влияет только на соотношение весов, поэтому важно не само число, а то,
 * что такая точка не должна ни доминировать над хорошим фиксом, ни быть отброшенной.
 */
private const val UNKNOWN_ACCURACY_METERS = 20.0

/**
 * Скользящее среднее по соседям с весом, обратным квадрату погрешности.
 *
 * Невзвешенное среднее тянуло координату к худшему фиксу окна ровно так же, как к лучшему:
 * точка с погрешностью 100 м смещала соседей наравне с десятиметровой. Вес 1/σ² — обычная
 * для объединения независимых измерений форма, и она же делает сглаживание безопасным при
 * увеличенном радиусе: плохие фиксы почти не участвуют.
 */
class SmoothingUseCase {
    operator fun invoke(
        points: List<GeoPoint>,
        radius: Int,
        maximumNeighborGapMillis: Long = TRACK_GAP_THRESHOLD_MILLIS,
    ): List<GeoPoint> {
        if (radius <= 0 || points.size < 3) return points

        val segments = continuousSegments(points, maximumNeighborGapMillis)
        return points.mapIndexed { index, point ->
            if (index == 0 || index == points.lastIndex || point.isOutlier || point.isManuallyEdited) {
                point
            } else {
                // Окно всегда симметрично и не выходит за пределы непрерывного участка.
                //
                // Обрезанное с одной стороны окно смещает точку к середине трека: на
                // прямом участке это читалось детектором остановок как замедление. А
                // сглаживание через разрыв тянет точку к другому концу дыры — соседа за
                // двухминутным пропуском на треке просто нет, есть другое место, и в
                // логе прогулки такая соседняя точка уезжала на восемь десятков метров.
                val effectiveRadius = minOf(
                    radius,
                    index,
                    points.lastIndex - index,
                    segments.radiusWithinSegment(index),
                )
                val neighbors = points.subList(
                    index - effectiveRadius,
                    index + effectiveRadius + 1,
                ).filterNot(GeoPoint::isOutlier)
                if (neighbors.size < 2) {
                    point
                } else {
                    val weights = neighbors.map(GeoPoint::smoothingWeight)
                    point.copy(
                        latitude = neighbors.weightedAverage(weights, GeoPoint::latitude)
                            ?: point.latitude,
                        longitude = neighbors.weightedAverage(weights, GeoPoint::longitude)
                            ?: point.longitude,
                        altitudeMeters = neighbors.weightedAverage(weights, GeoPoint::altitudeMeters),
                    )
                }
            }
        }
    }
}

/**
 * Номер непрерывного участка для каждой точки: он растёт на каждом разрыве трека.
 * Сравнение номеров — самый дешёвый способ спросить «эти две точки соседи на самом деле?».
 */
private fun continuousSegments(points: List<GeoPoint>, maximumGapMillis: Long): IntArray {
    val segments = IntArray(points.size)
    var current = 0
    for (index in 1 until points.size) {
        val gap = points[index].timestampEpochMillis - points[index - 1].timestampEpochMillis
        if (gap > maximumGapMillis) current++
        segments[index] = current
    }
    return segments
}

/** Насколько далеко от [index] можно уйти в обе стороны, не покидая своего участка. */
private fun IntArray.radiusWithinSegment(index: Int): Int {
    var radius = 0
    while (
        index - radius - 1 >= 0 &&
        index + radius + 1 <= lastIndex &&
        this[index - radius - 1] == this[index] &&
        this[index + radius + 1] == this[index]
    ) {
        radius++
    }
    return radius
}

private fun GeoPoint.smoothingWeight(): Double {
    val accuracy = accuracyMeters
        ?.toDouble()
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: UNKNOWN_ACCURACY_METERS
    return 1.0 / (accuracy * accuracy)
}

/**
 * Взвешенное среднее по тем точкам, у которых значение есть. Высота известна не всегда,
 * и усреднять её нужно с весами тех же точек, а не всего окна.
 */
private fun List<GeoPoint>.weightedAverage(
    weights: List<Double>,
    selector: (GeoPoint) -> Double?,
): Double? {
    var weightedSum = 0.0
    var totalWeight = 0.0
    forEachIndexed { index, point ->
        val value = selector(point) ?: return@forEachIndexed
        weightedSum += value * weights[index]
        totalWeight += weights[index]
    }
    return if (totalWeight > 0.0) weightedSum / totalWeight else null
}
