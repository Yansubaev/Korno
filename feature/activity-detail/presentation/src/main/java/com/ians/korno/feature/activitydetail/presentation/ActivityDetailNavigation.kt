package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ians.korno.core.designsystem.animation.DISINTEGRATING_ENTRY_KEY
import kotlinx.serialization.Serializable

/** [source] — [com.ians.korno.core.designsystem.animation.ActivityBoundsSource] экрана, чья строка раскрылась. */
@Serializable
data class ActivityDetailRoute(val activityId: Long, val source: String = "")

@Serializable
data class TrackCorrectionRoute(val activityId: Long)

fun NavGraphBuilder.activityDetailGraph(
    navController: NavController,
) {
    composable<ActivityDetailRoute>(
        // Назад экран сворачивается в строку списка или рассыпается (disintegrateOnExit) —
        // fade поверх этого не нужен; вперёд, на правку трека, он гаснет сам.
        exitTransition = { fadeOut(tween(700)) },
        popExitTransition = { ExitTransition.None },
    ) { backStackEntry ->
        val route: ActivityDetailRoute = backStackEntry.toRoute()
        ActivityDetailScreen(
            animatedVisibilityScope = this,
            activityId = route.activityId,
            source = route.source,
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
