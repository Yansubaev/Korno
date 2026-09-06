package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint

class SmoothingUseCase {
    operator fun invoke(points: List<GeoPoint>, radius: Int): List<GeoPoint> {
        if (radius <= 0 || points.size < 3) return points

        return points.mapIndexed { index, point ->
            if (index == 0 || index == points.lastIndex || point.isOutlier || point.isManuallyEdited) {
                point
            } else {
                val neighbors = points.subList(
                    (index - radius).coerceAtLeast(0),
                    (index + radius + 1).coerceAtMost(points.size),
                ).filterNot(GeoPoint::isOutlier)
                if (neighbors.size < 2) {
                    point
                } else {
                    point.copy(
                        latitude = neighbors.map(GeoPoint::latitude).average(),
                        longitude = neighbors.map(GeoPoint::longitude).average(),
                        altitudeMeters = neighbors.mapNotNull(GeoPoint::altitudeMeters)
                            .takeIf(List<Double>::isNotEmpty)
                            ?.average(),
                    )
                }
            }
        }
    }
}
