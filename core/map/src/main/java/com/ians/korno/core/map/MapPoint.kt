package com.ians.korno.core.map

import androidx.compose.runtime.Immutable

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
)

/** Точка трека в режиме ручной коррекции: её можно выделить и удалить. */
@Immutable
data class EditableMapPoint(
    val id: Long,
    val point: MapPoint,
    val isOutlier: Boolean = false,
    val isSelected: Boolean = false,
)
