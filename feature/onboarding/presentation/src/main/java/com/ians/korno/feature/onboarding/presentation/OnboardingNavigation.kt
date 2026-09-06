package com.ians.korno.feature.onboarding.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object OnboardingRoute

fun NavGraphBuilder.onboardingGraph(
    onOnboardingFinished: () -> Unit,
) {
    composable<OnboardingRoute> {
        OnboardingScreen(onContinueClick = onOnboardingFinished)
    }
}
