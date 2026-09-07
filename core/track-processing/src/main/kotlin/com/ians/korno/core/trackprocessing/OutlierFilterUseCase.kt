package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import kotlin.math.abs

/**
 * Отсев точек, которые не могут быть результатом движения.
 *
 * Проверок три, и они намеренно смотрят на разное:
 *
 * 1. **Погрешность.** Фикс грубее [TrackProcessingConfig.maximumAccuracyMeters] выбрасывается
 *    сразу, без сравнения с соседями. Такой точке нечего сказать о положении, и хуже всего
 *    то, что она портит и предыдущий отрезок, и следующий.
 * 2. **Скорость.** Сравнивается сырое расстояние, а не уменьшенное на погрешность. Раньше из
 *    каждого отрезка вычиталась сумма погрешностей обеих точек, и при типичных 20–25 м порог
 *    отъезжал за 40 км/ч — на прогулке фильтр не срабатывал ни разу. Погрешность теперь
 *    смягчает порог справа и ограничена [TrackProcessingConfig.outlierAccuracyAllowanceMeters].
 * 3. **Ускорение.** Считается по скорости, из которой погрешность уже вычтена: этот признак
 *    вторичен, и ложное срабатывание на дрожании координат хуже пропуска.
 */
class OutlierFilterUseCase {
    operator fun invoke(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
    ): List<GeoPoint> {
        var previousAccepted: GeoPoint? = null
        var previousAdjustedSpeed: Double? = null

        return points.sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id)).map { point ->
            val previous = previousAccepted
            val invalidCoordinates = point.latitude !in -90.0..90.0 ||
                point.longitude !in -180.0..180.0 ||
                !point.latitude.isFinite() ||
                !point.longitude.isFinite()
            val unreliableAccuracy = point.accuracyMeters
                ?.let { !it.isFinite() || it > config.maximumAccuracyMeters } == true
            val elapsedSeconds = previous
                ?.let { (point.timestampEpochMillis - it.timestampEpochMillis) / 1_000.0 }
                ?.takeIf { it > 0.0 }
            val rawDistance = previous?.let { distanceMeters(it, point) } ?: 0.0
            val allowance = previous
                ?.let { accuracyAllowanceMeters(it, point, config.outlierAccuracyAllowanceMeters) }
                ?: 0.0
            val adjustedSpeed = elapsedSeconds
                ?.let { (rawDistance - allowance).coerceAtLeast(0.0) / it }
                ?: 0.0
            val acceleration = elapsedSeconds?.let { seconds ->
                previousAdjustedSpeed?.let { abs(adjustedSpeed - it) / seconds }
            }
            val invalidTimestamp = previous != null && elapsedSeconds == null
            val implausibleMovement = elapsedSeconds != null &&
                (
                    rawDistance > allowance + config.maximumSpeedMetersPerSecond * elapsedSeconds ||
                        (acceleration ?: 0.0) > config.maximumAccelerationMetersPerSecondSquared
                    )
            val isOutlier = point.isOutlier ||
                invalidCoordinates ||
                unreliableAccuracy ||
                invalidTimestamp ||
                implausibleMovement

            if (!isOutlier) {
                previousAccepted = point
                if (previous != null) previousAdjustedSpeed = adjustedSpeed
            }
            point.copy(isOutlier = isOutlier, isStop = if (isOutlier) false else point.isStop)
        }
    }
}
