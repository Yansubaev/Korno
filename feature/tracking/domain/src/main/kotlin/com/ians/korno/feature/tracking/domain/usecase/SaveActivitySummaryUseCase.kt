package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.repository.TrackRepository

/**
 * Подтверждение записи на экране сохранения: название и заметка.
 *
 * Вида движения здесь больше нет — он живёт в сегментах, и правится на экране активности,
 * где видно, какой участок правишь (`docs/adr/0004`).
 */
class SaveActivitySummaryUseCase(
    private val trackRepository: TrackRepository,
) {
    suspend operator fun invoke(
        activity: Activity,
        title: String,
        note: String,
    ): EmptyResult<DataError.Local> =
        trackRepository.updateActivity(
            activity.copy(
                title = title.ifBlank { null },
                note = note.ifBlank { null },
                // Пользователь дошёл до экрана сохранения и подтвердил запись —
                // напоминать о ней больше не нужно.
                isPendingSummary = false,
            ),
        )
}
