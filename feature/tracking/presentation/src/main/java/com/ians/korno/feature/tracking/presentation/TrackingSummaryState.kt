package com.ians.korno.feature.tracking.presentation

import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.map.MapPoint

internal data class TrackingSummaryState(
    val isLoading: Boolean = true,
    /**
     * Вид единственного участка фиксированной записи — его и правит ряд чипов.
     * `null` — ряда нет: у авто-записи и импорта участков несколько, и правятся они
     * на экране активности, где видно, какой участок правишь (`docs/adr/0004`).
     */
    val editableKind: SegmentKind? = null,
    val distanceText: String = "",
    val durationText: String = "",
    val paceText: String = "",
    /** `null` — вид движения шаги не считает. */
    val stepsText: String? = null,
    val title: String = "",
    val note: String = "",
    val routePoints: List<MapPoint> = emptyList(),
    val saved: Boolean = false,
    val discarded: Boolean = false,
)
