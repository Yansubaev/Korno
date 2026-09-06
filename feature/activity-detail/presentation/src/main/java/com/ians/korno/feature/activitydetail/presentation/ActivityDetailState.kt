package com.ians.korno.feature.activitydetail.presentation

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.logging.ActivityLogInfo
import com.ians.korno.core.map.MapPoint

internal data class ActivityDetailState(
    val isLoading: Boolean = true,
    val title: String = "",
    val dateLabel: String = "",
    val type: ActivityType = ActivityType.RUNNING,
    val distanceText: String = "",
    val durationText: String = "",
    val movingText: String = "",
    val avgSpeedText: String = "",
    val maxSpeedText: String = "",
    val elevationText: String = "",
    val paceBars: List<Float> = emptyList(),
    val routePoints: List<MapPoint> = emptyList(),
    val snackbarMessageRes: Int? = null,
    val deleteDialogOpen: Boolean = false,
    val deleted: Boolean = false,
    /** Открытый диалог выбора формата и то, ради чего он открыт; `null` — закрыт. */
    val formatChoice: TrackFileIntent? = null,
    val isExporting: Boolean = false,
    /**
     * Лог этой активности. `null` — логирование недоступно (release-сборка), и раздел
     * диагностики на экране не появляется вовсе.
     */
    val activityLog: ActivityLogInfo? = null,
    val isPreparingLogArchive: Boolean = false,
)
