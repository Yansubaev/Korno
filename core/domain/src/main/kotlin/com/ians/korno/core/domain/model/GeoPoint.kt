package com.ians.korno.core.domain.model

/**
 * Только координаты — для превью маршрута.
 *
 * Отдельно от [GeoPoint] намеренно: превью строится по нескольким десяткам точек из
 * трека в тысячи, и тянуть ради него полные строки таблицы было бы расточительно.
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
)

data class GeoPoint(
    val id: Long = 0L,
    val activityId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val timestampEpochMillis: Long,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    /** Состояние записи в момент получения точки; паузы сохраняются как часть маршрута. */
    val recordingStatus: RecordingStatus = RecordingStatus.RECORDING,
    val isStop: Boolean = false,
    val isOutlier: Boolean = false,
    val isManuallyEdited: Boolean = false,
)
