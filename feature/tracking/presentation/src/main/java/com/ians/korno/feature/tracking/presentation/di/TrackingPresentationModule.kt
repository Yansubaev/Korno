package com.ians.korno.feature.tracking.presentation.di

import com.ians.korno.feature.tracking.presentation.PendingSummaryViewModel
import com.ians.korno.feature.tracking.presentation.TrackingRecordingViewModel
import com.ians.korno.feature.tracking.presentation.TrackingSetupViewModel
import com.ians.korno.feature.tracking.presentation.TrackingSummaryViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val trackingPresentationModule = module {
    viewModelOf(::TrackingSetupViewModel)
    viewModelOf(::TrackingRecordingViewModel)
    viewModelOf(::TrackingSummaryViewModel)
    viewModelOf(::PendingSummaryViewModel)
}
