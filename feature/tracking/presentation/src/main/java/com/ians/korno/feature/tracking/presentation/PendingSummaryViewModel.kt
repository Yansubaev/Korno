package com.ians.korno.feature.tracking.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.feature.tracking.domain.usecase.ObservePendingSummaryActivitiesUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveTrackingSessionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Живёт на уровне всего приложения, а не отдельного экрана: запись можно остановить кнопкой
 * в уведомлении, и тогда экран сохранения нужно открыть поверх любого текущего экрана —
 * при старте приложения или при возврате к нему.
 */
class PendingSummaryViewModel(
    observePendingSummaryActivities: ObservePendingSummaryActivitiesUseCase,
    observeTrackingSession: ObserveTrackingSessionUseCase,
) : ViewModel() {

    private val handledActivityId = MutableStateFlow<Long?>(null)

    val pendingActivityId: StateFlow<Long?> = combine(
        observePendingSummaryActivities(),
        observeTrackingSession(),
        handledActivityId,
    ) { pending, session, handled ->
        // Идёт новая запись — снимать пользователя с экрана записи ради старой нельзя.
        if (session.isActive) null else pending.firstOrNull { it.id != handled }?.id
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = null,
    )

    /**
     * Экран сохранения уже открывался в этом заходе в приложение. Если пользователь ушёл
     * с него назад, повторно навязывать экран не нужно — напоминание останется в уведомлении.
     */
    fun onSummaryOpened(activityId: Long) {
        handledActivityId.value = activityId
    }

    /** Возврат в приложение — повод снова предложить сохранить незавершённую запись. */
    fun onAppForegrounded() {
        handledActivityId.value = null
    }
}
