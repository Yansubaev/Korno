package com.ians.korno.feature.tracking.presentation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.feature.tracking.domain.navigation.TrackingDeepLinks
import kotlinx.serialization.Serializable

@Serializable
data class TrackingSetupRoute(val initialActivityType: ActivityType = ActivityType.RUNNING)

@Serializable
data object TrackingRecordingRoute

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
            initialActivityType = route.initialActivityType,
            onStartRecording = {
                // Настройка отработала — возвращаться к ней уже некуда, а экран записи
                // должен лежать сразу над Home: тем же местом, куда приводит диплинк
                // из уведомления, где экрана настройки в стеке нет вовсе.
                navController.navigate(TrackingRecordingRoute) {
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
    ) {
        TrackingRecordingScreen(
            onFinished = { activityId ->
                navController.navigate(TrackingSummaryRoute(activityId)) {
                    popUpTo<TrackingRecordingRoute> { inclusive = true }
                }
            },
        )
    }
    composable<TrackingSummaryRoute> { backStackEntry ->
        val route: TrackingSummaryRoute = backStackEntry.toRoute()
        TrackingSummaryScreen(
            activityId = route.activityId,
            onSaved = onFinished,
            onDiscarded = onDiscarded,
        )
    }
}
