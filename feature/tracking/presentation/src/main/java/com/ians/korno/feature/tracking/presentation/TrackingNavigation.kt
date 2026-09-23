package com.ians.korno.feature.tracking.presentation

import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.ians.korno.core.designsystem.animation.DISINTEGRATING_ENTRY_KEY
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.feature.tracking.domain.navigation.TrackingDeepLinks
import kotlinx.serialization.Serializable

@Serializable
data class TrackingSetupRoute(val initialSegmentKind: SegmentKind = SegmentKind.RUNNING)

/** [finish] — открыть экран сразу с диалогом завершения, см. [TrackingDeepLinks.FINISH_PARAM]. */
@Serializable
data class TrackingRecordingRoute(val finish: Boolean = false)

@Serializable
data class TrackingSummaryRoute(val activityId: Long)

fun NavGraphBuilder.trackingGraph(
    navController: NavController,
    onFinished: (activityId: Long) -> Unit,
    onDiscarded: () -> Unit,
) {
    composable<TrackingSetupRoute> { backStackEntry ->
        val route: TrackingSetupRoute = backStackEntry.toRoute()
        TrackingSetupScreen(
            initialSegmentKind = route.initialSegmentKind,
            onStartRecording = {
                // Настройка отработала — возвращаться к ней уже некуда, а экран записи
                // должен лежать сразу над Home: тем же местом, куда приводит диплинк
                // из уведомления, где экрана настройки в стеке нет вовсе.
                navController.navigate(TrackingRecordingRoute()) {
                    popUpTo<TrackingSetupRoute> { inclusive = true }
                }
            },
            onBack = onDiscarded,
        )
    }
    composable<TrackingRecordingRoute>(
        // Тап по уведомлению записи ведёт сюда. URI объявлен в `:domain`, потому что его же
        // подставляет в PendingIntent сервис, который про этот граф ничего не знает.
        deepLinks = listOf(navDeepLink<TrackingRecordingRoute>(basePath = TrackingDeepLinks.RECORDING)),
    ) { backStackEntry ->
        // Аргумент маршрута лежит в savedStateHandle: оттуда его и гасим после показа диалога,
        // чтобы он не открылся снова после поворота. Туда же флаг кладёт KornoApp, если
        // «Стоп» нажат, когда экран записи уже открыт.
        val handle = backStackEntry.savedStateHandle
        val finishRequested by handle.getStateFlow(TrackingDeepLinks.FINISH_PARAM, false)
            .collectAsStateWithLifecycle()
        TrackingRecordingScreen(
            finishRequested = finishRequested,
            onFinishRequestHandled = { handle[TrackingDeepLinks.FINISH_PARAM] = false },
            onFinished = { activityId ->
                navController.navigate(TrackingSummaryRoute(activityId)) {
                    popUpTo<TrackingRecordingRoute> { inclusive = true }
                }
            },
        )
    }
    composable<TrackingSummaryRoute>(
        // Уход экран анимирует сам (disintegrateOnExit), чужой fade его бы перекрыл.
        exitTransition = { ExitTransition.None },
        popExitTransition = { ExitTransition.None },
    ) { backStackEntry ->
        val route: TrackingSummaryRoute = backStackEntry.toRoute()
        TrackingSummaryScreen(
            animatedVisibilityScope = this,
            activityId = route.activityId,
            onSaved = onFinished,
            onDiscarded = {
                backStackEntry.savedStateHandle[DISINTEGRATING_ENTRY_KEY] = true
                onDiscarded()
            },
        )
    }
}
