package com.ians.korno.feature.activitylist.presentation.di

import com.ians.korno.feature.activitylist.domain.usecase.GetRoutePreviewUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ImportTracksUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ObserveActivitiesUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ObserveTrackImportUseCase
import com.ians.korno.feature.activitylist.presentation.ActivityListViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val activityListPresentationModule = module {
    factoryOf(::ObserveActivitiesUseCase)
    factoryOf(::ImportTracksUseCase)
    factoryOf(::GetRoutePreviewUseCase)
    factoryOf(::ObserveTrackImportUseCase)
    viewModelOf(::ActivityListViewModel)
}
