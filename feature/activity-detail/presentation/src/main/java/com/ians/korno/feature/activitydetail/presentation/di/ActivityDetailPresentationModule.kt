package com.ians.korno.feature.activitydetail.presentation.di

import com.ians.korno.core.trackanalytics.AnalyzeTrackUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.AnalyzeActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ChangeActivitySegmentKindUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveActivitySegmentsUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.DeleteActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ExportActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveGeoPointsUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveTrackExportUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.PrepareActivityExportUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.PrepareActivityShareUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.PreviewTrackCorrectionUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.RedecodeActivitySegmentsUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.SaveTrackCorrectionUseCase
import com.ians.korno.feature.activitydetail.presentation.ActivityDetailViewModel
import com.ians.korno.feature.activitydetail.presentation.TrackCorrectionViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val activityDetailPresentationModule = module {
    factoryOf(::ObserveActivityUseCase)
    factoryOf(::AnalyzeTrackUseCase)
    factoryOf(::AnalyzeActivityUseCase)
    factoryOf(::ObserveGeoPointsUseCase)
    factoryOf(::DeleteActivityUseCase)
    factoryOf(::ExportActivityUseCase)
    factoryOf(::PrepareActivityExportUseCase)
    factoryOf(::PrepareActivityShareUseCase)
    factoryOf(::ObserveTrackExportUseCase)
    factoryOf(::PreviewTrackCorrectionUseCase)
    factoryOf(::SaveTrackCorrectionUseCase)
    factoryOf(::ObserveActivitySegmentsUseCase)
    factory { ChangeActivitySegmentKindUseCase(get(), get()) }
    factory { RedecodeActivitySegmentsUseCase(get(), get()) }
    viewModelOf(::ActivityDetailViewModel)
    viewModelOf(::TrackCorrectionViewModel)
}
