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
    /**
     * Погрешность доплеровской скорости. Приёмник меряет скорость по сдвигу несущей, а не
     * по разнице координат, и обычно знает её на порядок точнее положения. Без этого поля
     * скорость нечем взвесить, и фильтр вынужден доверять ей либо полностью, либо никак.
     */
    val speedAccuracyMetersPerSecond: Float? = null,
    /**
     * Курс в градусах от севера по часовой стрелке. Вместе со скоростью задаёт вектор
     * движения — то самое измерение, которого не хватало модели движения: раньше скорость
     * была известна только по модулю, и направление приходилось брать из шумных координат.
     */
    val bearingDegrees: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
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
