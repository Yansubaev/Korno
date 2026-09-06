package com.ians.korno.feature.settings.presentation.di

import com.ians.korno.feature.settings.domain.usecase.ClearMapCacheUseCase
import com.ians.korno.feature.settings.domain.usecase.ExportAllActivitiesUseCase
import com.ians.korno.feature.settings.domain.usecase.GetMapCacheSizeUseCase
import com.ians.korno.feature.settings.domain.usecase.ObserveTrackExportUseCase
import com.ians.korno.feature.settings.presentation.SettingsViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingsPresentationModule = module {
    factoryOf(::ExportAllActivitiesUseCase)
    factoryOf(::GetMapCacheSizeUseCase)
    factoryOf(::ClearMapCacheUseCase)
    factoryOf(::ObserveTrackExportUseCase)
    viewModelOf(::SettingsViewModel)
}
