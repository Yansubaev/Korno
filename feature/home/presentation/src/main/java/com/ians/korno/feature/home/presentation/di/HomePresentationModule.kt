package com.ians.korno.feature.home.presentation.di

import com.ians.korno.feature.home.domain.usecase.ObserveHomeSummaryUseCase
import com.ians.korno.feature.home.presentation.HomeViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val homePresentationModule = module {
    factoryOf(::ObserveHomeSummaryUseCase)
    viewModelOf(::HomeViewModel)
}
