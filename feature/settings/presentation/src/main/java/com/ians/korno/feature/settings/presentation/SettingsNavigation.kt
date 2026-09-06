package com.ians.korno.feature.settings.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
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
        )
    }
}
