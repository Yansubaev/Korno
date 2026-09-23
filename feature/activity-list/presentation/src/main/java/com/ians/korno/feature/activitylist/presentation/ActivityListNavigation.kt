package com.ians.korno.feature.activitylist.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.ians.korno.core.designsystem.layout.LocalBottomBarPadding
import kotlinx.serialization.Serializable

@Serializable
data object ActivityListRoute

fun NavGraphBuilder.activityListGraph(
    onOpenActivity: (activityId: Long) -> Unit,
    onOpenRecording: () -> Unit,
) {
    composable<ActivityListRoute> {
        ActivityListScreen(
            onOpenActivity = onOpenActivity,
            onOpenRecording = onOpenRecording,
            modifier = Modifier.padding(bottom = LocalBottomBarPadding.current),
        )
    }
}
