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
    /** `null` — тип активности шаги не считает. */
    val stepsText: String? = null,
    val routePoints: List<MapPoint> = emptyList(),
    /**
     * Всё, что посчитано по треку: линзы, графики, сплиты и остановки.
     * `null` — данных для анализа не хватило, и разделы аналитики скрыты целиком.
     * Показывать их с нулями значило бы утверждать то, чего в треке нет.
     */
    val insights: ActivityInsightsUi? = null,
    val lens: MapLens = MapLens.ROUTE,
    /** Точка, выбранная касанием графика или строкой сплита. */
    val selection: TrackSelectionUi? = null,
    val selectedSplitNumber: Int? = null,
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
) {
    val speedDisplay: SpeedDisplay get() = insights?.speedDisplay ?: type.speedDisplay
}
