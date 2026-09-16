package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.animation.ExitTransition
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ians.korno.core.designsystem.animation.DISINTEGRATING_ENTRY_KEY
import kotlinx.serialization.Serializable

@Serializable
data class ActivityDetailRoute(val activityId: Long)

@Serializable
data class TrackCorrectionRoute(val activityId: Long)

fun NavGraphBuilder.activityDetailGraph(
    navController: NavController,
) {
    composable<ActivityDetailRoute>(
        // Уход экран анимирует сам (disintegrateOnExit), чужой fade его бы перекрыл.
        exitTransition = { ExitTransition.None },
        popExitTransition = { ExitTransition.None },
    ) { backStackEntry ->
        val route: ActivityDetailRoute = backStackEntry.toRoute()
        ActivityDetailScreen(
            animatedVisibilityScope = this,
            activityId = route.activityId,
            onBack = { navController.popBackStack() },
            onDeleted = {
                backStackEntry.savedStateHandle[DISINTEGRATING_ENTRY_KEY] = true
                navController.popBackStack()
            },
            onEditTrack = { activityId -> navController.navigate(TrackCorrectionRoute(activityId)) },
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
