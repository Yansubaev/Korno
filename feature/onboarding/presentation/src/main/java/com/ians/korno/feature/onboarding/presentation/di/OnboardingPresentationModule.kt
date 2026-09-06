package com.ians.korno.feature.onboarding.presentation.di

import com.ians.korno.feature.onboarding.presentation.OnboardingViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val onboardingPresentationModule = module {
    viewModelOf(::OnboardingViewModel)
}
