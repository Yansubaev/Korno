package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import kotlin.math.abs

class OutlierFilterUseCase {
    operator fun invoke(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
    ): List<GeoPoint> {
        var previousAccepted: GeoPoint? = null
        var previousSpeedMetersPerSecond: Double? = null

        return points.sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id)).map { point ->
            val previous = previousAccepted
            val invalidCoordinates = point.latitude !in -90.0..90.0 ||
                point.longitude !in -180.0..180.0 ||
                !point.latitude.isFinite() ||
                !point.longitude.isFinite()
            val elapsedSeconds = previous?.let {
                (point.timestampEpochMillis - it.timestampEpochMillis) / 1_000.0
            }
            val calculatedSpeed = if (previous != null && elapsedSeconds != null && elapsedSeconds > 0.0) {
                accuracyAdjustedDistanceMeters(previous, point) / elapsedSeconds
            } else {
                0.0
            }
            val acceleration = if (elapsedSeconds != null && elapsedSeconds > 0.0) {
                previousSpeedMetersPerSecond?.let { abs(calculatedSpeed - it) / elapsedSeconds }
            } else {
                null
            }
            val invalidTimestamp = previous != null && (elapsedSeconds == null || elapsedSeconds <= 0.0)
            val implausibleMovement = calculatedSpeed > config.maximumSpeedMetersPerSecond ||
                (acceleration ?: 0.0) > config.maximumAccelerationMetersPerSecondSquared
            val isOutlier = point.isOutlier || invalidCoordinates || invalidTimestamp || implausibleMovement

            if (!isOutlier) {
                previousAccepted = point
                if (previous != null) previousSpeedMetersPerSecond = calculatedSpeed
            }
            point.copy(isOutlier = isOutlier, isStop = if (isOutlier) false else point.isStop)
        }
    }
}
