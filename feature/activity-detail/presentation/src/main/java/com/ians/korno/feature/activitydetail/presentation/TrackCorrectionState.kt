package com.ians.korno.feature.activitydetail.presentation

import com.ians.korno.core.map.MapPoint

internal data class CorrectionPointUi(
    val id: Long,
    val point: MapPoint,
    val isOutlier: Boolean,
    val deleted: Boolean,
    val selected: Boolean,
)

internal data class TrackCorrectionState(
    val isLoading: Boolean = true,
    val points: List<CorrectionPointUi> = emptyList(),
    val originalDistanceText: String = "",
    val currentDistanceText: String = "",
    val removedCount: Int = 0,
    val hasSelection: Boolean = false,
    val canUndo: Boolean = false,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
)
