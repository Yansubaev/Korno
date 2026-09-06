package com.ians.korno.feature.activitydetail.presentation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

@Serializable
data class ActivityDetailRoute(val activityId: Long)

@Serializable
data class TrackCorrectionRoute(val activityId: Long)

@Serializable
data class ActivityMapRoute(val activityId: Long)

fun NavGraphBuilder.activityDetailGraph(
    navController: NavController,
) {
    composable<ActivityDetailRoute> { backStackEntry ->
        val route: ActivityDetailRoute = backStackEntry.toRoute()
        ActivityDetailScreen(
            activityId = route.activityId,
            onBack = { navController.popBackStack() },
            onEditTrack = { activityId -> navController.navigate(TrackCorrectionRoute(activityId)) },
            onOpenMap = { activityId -> navController.navigate(ActivityMapRoute(activityId)) },
        )
    }
    composable<ActivityMapRoute> { backStackEntry ->
        val route: ActivityMapRoute = backStackEntry.toRoute()
        ActivityMapScreen(
            activityId = route.activityId,
            onBack = { navController.popBackStack() },
        )
    }
    composable<TrackCorrectionRoute> { backStackEntry ->
        val route: TrackCorrectionRoute = backStackEntry.toRoute()
        TrackCorrectionScreen(
            activityId = route.activityId,
            onBack = { navController.popBackStack() },
        )
    }
}
