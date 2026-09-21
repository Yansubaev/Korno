package com.ians.korno.feature.activitylist.presentation

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.map.MapPoint

internal enum class ActivityPeriod { ALL, WEEK, MONTH, YEAR }

internal data class ActivityListItemUi(
    val id: Long,
    val title: String,
    val supportingText: String,
    val distanceText: String,
    val type: ActivityType,
)

internal data class ActivityListState(
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val filterKind: SegmentKind? = null,
    val filterPeriod: ActivityPeriod = ActivityPeriod.ALL,
    /** Чипы: виды движения, которые вообще встречаются в базе. */
    val availableKinds: List<SegmentKind> = emptyList(),
    val items: List<ActivityListItemUi> = emptyList(),
    val statsDistanceText: String = "",
    val statsTimeText: String = "",
    /** Маршруты для миниатюр; заполняются по мере появления строк на экране. */
    val routePreviews: Map<Long, List<MapPoint>> = emptyMap(),
    val isImporting: Boolean = false,
    val snackbarMessage: ImportMessage? = null,
)

/**
 * Итог импорта. Не id ресурса, а сами счётчики: сообщение про «N из M файлов»
 * склоняется по количеству, и выбор формы остаётся за UI-слоем.
 */
internal sealed interface ImportMessage {
    data class Imported(val successCount: Int, val totalCount: Int) : ImportMessage

    data object Failed : ImportMessage
}
