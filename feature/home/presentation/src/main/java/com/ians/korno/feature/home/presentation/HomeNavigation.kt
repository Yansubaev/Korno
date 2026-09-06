package com.ians.korno.feature.home.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.ians.korno.core.domain.model.ActivityType
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

fun NavGraphBuilder.homeGraph(
    onStartSetup: (ActivityType) -> Unit,
    onSeeAllActivities: () -> Unit,
    onOpenActivity: (activityId: Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    composable<HomeRoute> {
        HomeScreen(
            onStartSetup = onStartSetup,
            onSeeAllActivities = onSeeAllActivities,
            onOpenActivity = onOpenActivity,
            onOpenSettings = onOpenSettings,
        )
    }
}
