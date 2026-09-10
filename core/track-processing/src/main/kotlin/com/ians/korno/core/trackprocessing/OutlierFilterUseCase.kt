package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint

/**
 * Отсев точек, которые не являются измерением положения.
 *
 * Это не основной фильтр выбросов — им стал гейт невязки в
 * [KalmanTrackFilterUseCase]. Здесь остались только те проверки, которые гейт сделать
 * не может, потому что до них он ещё не дошёл: пока фильтр не сошёлся, ему нечем
 * предсказывать, и первую же дикую точку он принял бы за истину и построил бы вокруг неё
 * всё состояние. Отсюда и разделение — грубая правдоподобность до фильтра,
 * статистическая после.
 *
 * Проверки ускорения здесь больше нет, и это не упрощение ради упрощения. Она считала
 * производную от скорости, которая сама считалась по разнице шумных координат. При
 * погрешности 15 м два соседних фикса расходятся в среднем на 19 м, и на секундном шаге
 * «ускорение» такой пары измеряется десятками м/с² независимо от того, шёл человек или
 * стоял. На калибровочном треке эта проверка браковала 146 точек из 400 — то есть треть
 * совершенно нормальных измерений, — и ни один порог на это не влиял. Ровно то, что она
 * должна была ловить, гейт ловит правильно: несовпадение с предсказанием модели движения
 * и есть проверка ускорения, только сделанная с учётом того, насколько измерению верят.
 */
class OutlierFilterUseCase {
    operator fun invoke(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
    ): List<GeoPoint> {
        var previousAccepted: GeoPoint? = null

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
            val invalidTimestamp = previous != null && elapsedSeconds == null
            val impossibleDisplacement = previous != null && elapsedSeconds != null &&
                distanceMeters(previous, point) >
                accuracyAllowanceMeters(previous, point, config.outlierAccuracySigmas) +
                config.maximumSpeedMetersPerSecond * elapsedSeconds

            val isOutlier = point.isOutlier ||
                invalidCoordinates ||
                unreliableAccuracy ||
                invalidTimestamp ||
                impossibleDisplacement

            if (!isOutlier) previousAccepted = point
            point.copy(isOutlier = isOutlier, isStop = if (isOutlier) false else point.isStop)
        }
    }
}
