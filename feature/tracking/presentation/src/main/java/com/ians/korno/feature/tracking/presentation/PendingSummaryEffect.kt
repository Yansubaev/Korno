package com.ians.korno.feature.tracking.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

/**
 * Незаметный для UI эффект: если запись остановили кнопкой «Стоп» в уведомлении,
 * активность сохранилась без подтверждения — при старте приложения и при каждом
 * возврате к нему нужно открыть по ней экран сохранения.
 *
 * @param isEnabled `false`, когда открывать экран сохранения сейчас нельзя
 * (онбординг, уже открытый экран сохранения).
 */
@Composable
fun PendingSummaryEffect(
    isEnabled: Boolean,
    onOpenSummary: (activityId: Long) -> Unit,
    viewModel: PendingSummaryViewModel = koinViewModel(),
) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.onAppForegrounded()
    }

    val pendingActivityId by viewModel.pendingActivityId.collectAsStateWithLifecycle()

    LaunchedEffect(isEnabled, pendingActivityId) {
        val activityId = pendingActivityId
        if (!isEnabled || activityId == null) return@LaunchedEffect
        viewModel.onSummaryOpened(activityId)
        onOpenSummary(activityId)
    }
}
