package com.ians.korno.feature.activitylist.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object ActivityListRoute

fun NavGraphBuilder.activityListGraph(
    onOpenActivity: (activityId: Long) -> Unit,
) {
    composable<ActivityListRoute> {
        ActivityListScreen(onOpenActivity = onOpenActivity)
    }
}
