package com.ians.korno.feature.activitylist.presentation

import com.ians.korno.core.domain.model.ActivityType

internal sealed interface ActivityListAction {
    data class SetSearchQuery(val query: String) : ActivityListAction

    data class SetFilterType(val type: ActivityType?) : ActivityListAction

    data class SetFilterPeriod(val period: ActivityPeriod) : ActivityListAction

    /** Строка появилась на экране — можно готовить её миниатюру. */
    data class RowShown(val activityId: Long) : ActivityListAction

    /** Пользователь выбрал файлы в системном диалоге открытия. */
    data class ImportFilesChosen(val uris: List<String>) : ActivityListAction

    data object DismissSnackbar : ActivityListAction
}
