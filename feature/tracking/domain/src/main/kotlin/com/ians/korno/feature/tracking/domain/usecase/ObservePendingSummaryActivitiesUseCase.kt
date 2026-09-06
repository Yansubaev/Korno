package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Записи, остановленные из уведомления и потому сохранённые «молча»:
 * приложение должно показать по ним экран сохранения при возврате.
 * Ошибка чтения БД схлопывается в пустой список — навязывать экран сохранения
 * из-за сбоя запроса хуже, чем пропустить его до следующего запуска.
 */
class ObservePendingSummaryActivitiesUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(): Flow<List<Activity>> =
        trackRepository.observePendingSummaryActivities().map { result ->
            (result as? Result.Success)?.data.orEmpty()
        }
}
