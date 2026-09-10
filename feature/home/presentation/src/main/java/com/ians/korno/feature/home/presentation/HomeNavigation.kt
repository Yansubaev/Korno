package com.ians.korno.feature.home.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.designsystem.layout.LocalBottomBarPadding
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
            animatedVisibilityScope = this,
            onStartSetup = onStartSetup,
            onSeeAllActivities = onSeeAllActivities,
            onOpenActivity = onOpenActivity,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.padding(bottom = LocalBottomBarPadding.current),
        )
    }
}
