package com.ians.korno.feature.onboarding.data.di

import com.ians.korno.feature.onboarding.data.repository.DefaultPermissionRepository
import com.ians.korno.feature.onboarding.domain.repository.PermissionRepository
import org.koin.dsl.bind
import org.koin.dsl.module

val onboardingDataModule = module {
    single { DefaultPermissionRepository(get(), get()) } bind PermissionRepository::class
}
