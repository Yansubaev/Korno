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

/**
 * Промежуток между соседними точками, после которого отрезок перестаёт быть измеренным
 * движением и становится дырой в треке.
 *
 * Порог общий для всех типов активности и намеренно вынесен в домен: его одинаково
 * читают и расчёт статистики, и карта. Минута без единого фикса — это уже не редкий
 * сэмпл, а потерянный сигнал: прямая между двумя такими точками проходит сквозь
 * здания, а её «скорость» ничего не значит.
 */
const val TRACK_GAP_THRESHOLD_MILLIS: Long = 60_000L

/**
 * Отрезок между [previous] и [next] не наблюдался: фиксов внутри него не было.
 * Дистанция такого отрезка остаётся нижней оценкой пройденного и учитывается, а вот
 * скорость по нему не считается и на карте он рисуется разрывом.
 */
fun isTrackGap(previous: GeoPoint, next: GeoPoint): Boolean =
    next.timestampEpochMillis - previous.timestampEpochMillis > TRACK_GAP_THRESHOLD_MILLIS
