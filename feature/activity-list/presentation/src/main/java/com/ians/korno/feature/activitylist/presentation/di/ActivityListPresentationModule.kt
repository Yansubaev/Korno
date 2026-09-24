package com.ians.korno.feature.activitylist.presentation.di

import com.ians.korno.feature.activitylist.domain.usecase.ImportTracksUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ObserveActivityListUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ObserveRoutePreviewsUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ObserveTrackImportUseCase
import com.ians.korno.feature.activitylist.presentation.ActivityListViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val activityListPresentationModule = module {
    factoryOf(::ObserveActivityListUseCase)
    factoryOf(::ImportTracksUseCase)
    factoryOf(::ObserveRoutePreviewsUseCase)
    factoryOf(::ObserveTrackImportUseCase)
    viewModelOf(::ActivityListViewModel)
}
