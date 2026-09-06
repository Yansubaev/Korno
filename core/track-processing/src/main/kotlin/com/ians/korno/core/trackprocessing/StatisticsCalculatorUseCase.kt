package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import kotlin.math.max

data class TrackStatistics(
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    val elapsedTimeMillis: Long = 0L,
    val averageSpeedMetersPerSecond: Double = 0.0,
    val maxSpeedMetersPerSecond: Double = 0.0,
    val currentSpeedMetersPerSecond: Double = 0.0,
    val elevationGainMeters: Double = 0.0,
)

class StatisticsCalculatorUseCase {
    operator fun invoke(
        points: List<GeoPoint>,
        elevationNoiseMeters: Double = 3.0,
    ): TrackStatistics {
        val ordered = points
            .filterNot(GeoPoint::isOutlier)
            .sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id))
        if (ordered.isEmpty()) return TrackStatistics()

        var distance = 0.0
        var movingTime = 0L
        var elapsedTime = 0L
        var maximumSpeed = 0.0
        var currentSpeed = 0.0
        var elevationGain = 0.0

        ordered.zipWithNext().forEach { (first, second) ->
            val elapsedMillis = second.timestampEpochMillis - first.timestampEpochMillis
            if (elapsedMillis <= 0L) return@forEach

            // Обе границы ручной паузы исключаются: скачок к первой паузной точке,
            // сам путь на паузе и скачок обратно не меняют статистику активности.
            if (
                first.recordingStatus == RecordingStatus.MANUAL_PAUSE ||
                second.recordingStatus == RecordingStatus.MANUAL_PAUSE
            ) {
                currentSpeed = 0.0
                return@forEach
            }
            elapsedTime += elapsedMillis

            val segmentDistance = distanceMeters(first, second)
            val segmentSpeed = segmentDistance / (elapsedMillis / 1_000.0)
            if (!second.isStop) {
                distance += segmentDistance
                movingTime += elapsedMillis
                maximumSpeed = max(maximumSpeed, segmentSpeed)
                currentSpeed = segmentSpeed
            } else {
                currentSpeed = 0.0
            }

            val elevationDelta = first.altitudeMeters?.let { firstAltitude ->
                second.altitudeMeters?.minus(firstAltitude)
            }
            if (!second.isStop && elevationDelta != null && elevationDelta > elevationNoiseMeters) {
                elevationGain += elevationDelta
            }
        }

        return TrackStatistics(
            distanceMeters = distance,
            movingTimeMillis = movingTime,
            elapsedTimeMillis = elapsedTime,
            averageSpeedMetersPerSecond = if (movingTime > 0L) distance / (movingTime / 1_000.0) else 0.0,
            maxSpeedMetersPerSecond = maximumSpeed,
            currentSpeedMetersPerSecond = currentSpeed,
            elevationGainMeters = elevationGain,
        )
    }
}
