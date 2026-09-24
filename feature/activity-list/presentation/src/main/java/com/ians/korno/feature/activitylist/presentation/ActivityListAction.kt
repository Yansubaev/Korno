package com.ians.korno.feature.activitylist.presentation

import com.ians.korno.core.domain.model.SegmentKind

internal sealed interface ActivityListAction {
    data class SetSearchQuery(val query: String) : ActivityListAction

    data class SetFilterKind(val kind: SegmentKind?) : ActivityListAction

    data class SetFilterPeriod(val period: ActivityPeriod) : ActivityListAction

    /** Пользователь выбрал файлы в системном диалоге открытия. */
    data class ImportFilesChosen(val uris: List<String>) : ActivityListAction

    data object DismissSnackbar : ActivityListAction
}
