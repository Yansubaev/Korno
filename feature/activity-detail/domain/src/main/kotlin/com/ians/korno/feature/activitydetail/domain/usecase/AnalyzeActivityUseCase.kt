package com.ians.korno.feature.activitydetail.domain.usecase

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
    operator fun invoke(
        points: List<GeoPoint>,
        measurementSystem: MeasurementSystem,
    ): TrackAnalysis = analyzeTrack(
        points = points,
        splitLengthMeters = measurementSystem.splitLengthMeters,
    )
}
