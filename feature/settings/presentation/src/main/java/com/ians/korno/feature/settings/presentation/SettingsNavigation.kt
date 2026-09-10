package com.ians.korno.feature.settings.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.ians.korno.core.designsystem.layout.LocalBottomBarPadding
import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute

fun NavGraphBuilder.settingsGraph(
    onOpenPermissions: () -> Unit,
    onViewOnboarding: () -> Unit,
) {
    composable<SettingsRoute> {
        SettingsScreen(
            onOpenPermissions = onOpenPermissions,
            onViewOnboarding = onViewOnboarding,
            modifier = Modifier.padding(bottom = LocalBottomBarPadding.current),
        )
    }
}
