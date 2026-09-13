package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.dominantKind
import com.ians.korno.core.domain.model.slicePoints
import com.ians.korno.core.trackprocessing.StatisticsCalculatorUseCase
import com.ians.korno.core.trackprocessing.TrackProcessingConfig

/** Итог по одному виду движения во всей записи. */
data class SegmentKindSummary(
    val kind: SegmentKind,
    val distanceMeters: Double,
    val movingTimeMillis: Long,
    /** `null` — шаги не считались. */
    val stepCount: Int?,
) {
    val averageSpeedMetersPerSecond: Double
        get() = if (movingTimeMillis > 0L) distanceMeters / (movingTimeMillis / 1_000.0) else 0.0
}

/**
 * Разбивка записи по видам, в порядке первого появления. Дистанция — сохранённая у
 * сегментов, время в движении — по их точкам и их же порогам: иначе темп пешего участка
 * включал бы стояние на остановке. `UNKNOWN` в разбивку не попадает.
 */
fun summarizeSegmentKinds(
    points: List<GeoPoint>,
    segments: List<ActivitySegment>,
    statistics: StatisticsCalculatorUseCase = StatisticsCalculatorUseCase(),
): List<SegmentKindSummary> =
    segments.slicePoints(points)
        .filter { (segment, _) -> segment.kind != SegmentKind.UNKNOWN }
        .groupBy({ it.first.kind }, { it })
        .map { (kind, parts) ->
            SegmentKindSummary(
                kind = kind,
                distanceMeters = parts.sumOf { (segment, _) -> segment.distanceMeters },
                movingTimeMillis = parts.sumOf { (_, slice) ->
                    statistics(slice, TrackProcessingConfig.forSegmentKind(kind)).movingTimeMillis
                },
                stepCount = parts.mapNotNull { (segment, _) -> segment.stepCount }
                    .takeIf { it.isNotEmpty() }
                    ?.sum(),
            )
        }

/**
 * Точки вида с наибольшей дистанцией — по ним считаются сплиты и графики: сплит, в
 * который попал кусок автобуса, ничего не говорит ни о ходьбе, ни об автобусе.
 * У записи из одного вида это просто все точки.
 */
fun dominantKindPoints(points: List<GeoPoint>, segments: List<ActivitySegment>): List<GeoPoint> {
    val kinds = segments.map(ActivitySegment::kind).filter { it != SegmentKind.UNKNOWN }.distinct()
    if (kinds.size < 2) return points
    val dominant = segments.dominantKind() ?: return points
    return segments.slicePoints(points).filter { (segment, _) -> segment.kind == dominant }.flatMap { it.second }
}
