package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.splitLengthMeters
import com.ians.korno.core.trackanalytics.AnalyzeTrackUseCase
import com.ians.korno.core.trackanalytics.TrackAnalysis

/**
 * Разбор записанного трека для экрана активности.
 *
 * Система мер приходит сюда, а не в аналитику: делить маршрут на километры для того, кто
 * меряет всё в милях, бессмысленно, а самому расчёту всё равно, какой длины отрезок.
 */
class AnalyzeActivityUseCase(
    private val analyzeTrack: AnalyzeTrackUseCase,
) {
    /** @param segments в смешанной записи сплиты и графики строятся по точкам доминирующего вида. */
    operator fun invoke(
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
        measurementSystem: MeasurementSystem,
    ): TrackAnalysis = analyzeTrack(
        points = dominantKindPoints(points, segments),
        splitLengthMeters = measurementSystem.splitLengthMeters,
    )
}
