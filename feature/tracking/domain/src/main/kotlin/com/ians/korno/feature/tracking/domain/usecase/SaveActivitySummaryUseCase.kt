package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.repository.TrackRepository

class SaveActivitySummaryUseCase(
    private val trackRepository: TrackRepository,
) {
    suspend operator fun invoke(
        activity: Activity,
        title: String,
        note: String,
        activityType: ActivityType,
    ): EmptyResult<DataError.Local> =
        trackRepository.updateActivity(
            activity.copy(
                activityType = activityType,
                // Подтверждённый без изменений тип авторежима остаётся выведенным из сегментов.
                isTypeChosenByUser = activity.isTypeChosenByUser || activityType != activity.activityType,
                title = title.ifBlank { null },
                note = note.ifBlank { null },
                // Пользователь дошёл до экрана сохранения и подтвердил запись —
                // напоминать о ней больше не нужно.
                isPendingSummary = false,
            ),
        )
}
