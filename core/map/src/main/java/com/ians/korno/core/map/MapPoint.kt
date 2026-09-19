package com.ians.korno.core.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Точка на карте в WGS84. Отдельный от SDK тип: presentation-слой не зависит
 * ни от MapLibre, ни от доменной модели [com.ians.korno.core.domain.model.GeoPoint].
 */
@Immutable
data class MapPoint(
    val latitude: Double,
    val longitude: Double,
    /** Точка получена во время ручной или автоматической паузы. */
    val isPaused: Boolean = false,
    /**
     * Отрезок, приходящий в эту точку, не наблюдался: между фиксами прошло больше
     * [com.ians.korno.core.domain.model.TRACK_GAP_THRESHOLD_MILLIS].
     *
     * Рисуется пунктиром по той же причине, что и пауза: сплошная линия здесь утверждала
     * бы, что человек шёл именно так, — а на деле она проходит сквозь дома. Цвет при этом
     * свой: пауза — то, что сделали, разрыв — то, чего запись не видела.
     */
    val isGap: Boolean = false,
    /**
     * Фикс отбракован фильтром: в дистанцию и разбор не попал, но место на маршруте
     * показывает. Отрезки к такой точке рисуются бледным отдельным слоем.
     *
     * Выкинуть её с карты совсем нельзя: на месте, скажем, поездки на автобусе с одними
     * сетевыми фиксами остаётся прямая через весь город, и неясно, что там вообще было.
     */
    val isRejected: Boolean = false,
    /**
     * Цвет отрезка, начинающегося в этой точке, — линза карты.
     * `null` — обычная линия трека.
     *
     * Цвет приходит готовым, а не выводится из числа: шкалу и палитру выбирает экран,
     * который знает и тему, и единицы измерения.
     */
    val shade: Color? = null,
)

/**
 * Цвета линзы, посчитанные по принятым точкам, переносятся на полную линию, где есть и
 * отбракованные. Разбор трека выбросов не видит, а линия без них соединяет прямой то,
 * что было между.
 *
 * Сопоставление по координатам, по порядку: [shaded] — подпоследовательность принятых
 * точек линии. Точка, которой в [shaded] нет (другой вид в смешанной записи), получает
 * [unmatched].
 */
fun List<MapPoint>.withShadesFrom(shaded: List<MapPoint>, unmatched: Color?): List<MapPoint> {
    var next = 0
    return map { point ->
        val candidate = shaded.getOrNull(next)
        when {
            point.isRejected -> point
            candidate != null &&
                candidate.latitude == point.latitude &&
                candidate.longitude == point.longitude -> {
                next++
                point.copy(shade = candidate.shade)
            }
            else -> point.copy(shade = unmatched)
        }
    }
}

/** Точка трека в режиме ручной коррекции: её можно выделить и удалить. */
@Immutable
data class EditableMapPoint(
    val id: Long,
    val point: MapPoint,
    val isOutlier: Boolean = false,
    val isSelected: Boolean = false,
)

/**
 * Остановка на маршруте. Радиус кружка растёт с длительностью, поэтому светофор и
 * получасовой привал видно как разные события, не читая подписей.
 */
@Immutable
data class MapStopMarker(
    val point: MapPoint,
    val durationMillis: Long,
    val isManualPause: Boolean,
)

/** Километровая или мильная отметка с номером. */
@Immutable
data class MapDistanceMarker(
    val point: MapPoint,
    val label: String,
)

/**
 * Всё, что рисуется поверх линии трека. Собрано в один объект: параметров у карты
 * иначе становится столько, что вызов перестаёт читаться.
 */
@Immutable
data class TrackOverlay(
    val stops: List<MapStopMarker> = emptyList(),
    val distanceMarkers: List<MapDistanceMarker> = emptyList(),
    /** Точка, выбранная на графике или в списке сплитов. */
    val highlight: MapPoint? = null,
    /** Выделенный отрезок маршрута — например, выбранный сплит. */
    val highlightedSection: List<MapPoint> = emptyList(),
) {
    companion object {
        val None = TrackOverlay()
    }
}
