package com.ians.korno.feature.tracking.data.di

import com.ians.korno.core.trackprocessing.ChangeActivitySegmentKindUseCase
import com.ians.korno.feature.tracking.data.controller.DefaultTrackingController
import com.ians.korno.feature.tracking.data.notification.DefaultPendingSummaryNotifier
import com.ians.korno.feature.tracking.data.session.TrackingSessionCoordinator
import com.ians.korno.feature.tracking.domain.repository.PendingSummaryNotifier
import com.ians.korno.feature.tracking.domain.repository.TrackingController
import com.ians.korno.feature.tracking.domain.usecase.DiscardActivityUseCase
import com.ians.korno.feature.tracking.domain.usecase.CancelTrackingPreparationUseCase
import com.ians.korno.feature.tracking.domain.usecase.ChangeSegmentKindUseCase
import com.ians.korno.feature.tracking.domain.usecase.IsAutoModeAvailableUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveActivitySegmentsUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveActivityUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObservePendingSummaryActivitiesUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveSessionTrackUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveTrackPointsUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveOngoingRecordingUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveTrackingSessionUseCase
import com.ians.korno.feature.tracking.domain.usecase.PauseTrackingUseCase
import com.ians.korno.feature.tracking.domain.usecase.PrepareTrackingUseCase
import com.ians.korno.feature.tracking.domain.usecase.RecoverActiveSessionUseCase
import com.ians.korno.feature.tracking.domain.usecase.ResumeTrackingUseCase
import com.ians.korno.feature.tracking.domain.usecase.SaveActivitySummaryUseCase
import com.ians.korno.feature.tracking.domain.usecase.StartTrackingUseCase
import com.ians.korno.feature.tracking.domain.usecase.StopTrackingUseCase
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.bind
import org.koin.dsl.module

val trackingDataModule = module {
    single { TrackingSessionCoordinator(get(), get(), get(), get(), get(), get()) }
    single { DefaultTrackingController(get(), get(), get()) } bind TrackingController::class
    single { DefaultPendingSummaryNotifier(get(), get()) } bind PendingSummaryNotifier::class

    factoryOf(::ObserveTrackingSessionUseCase)
    factoryOf(::ObserveOngoingRecordingUseCase)
    factoryOf(::StartTrackingUseCase)
    factoryOf(::IsAutoModeAvailableUseCase)
    factoryOf(::ChangeSegmentKindUseCase)
    factoryOf(::PrepareTrackingUseCase)
    factoryOf(::CancelTrackingPreparationUseCase)
    factoryOf(::PauseTrackingUseCase)
    factoryOf(::ResumeTrackingUseCase)
    factoryOf(::StopTrackingUseCase)
    factoryOf(::RecoverActiveSessionUseCase)
    factoryOf(::ObserveActivityUseCase)
    factoryOf(::ObserveActivitySegmentsUseCase)
    factoryOf(::ObservePendingSummaryActivitiesUseCase)
    factoryOf(::ObserveTrackPointsUseCase)
    factoryOf(::ObserveSessionTrackUseCase)
    factoryOf(::SaveActivitySummaryUseCase)
    factoryOf(::ChangeActivitySegmentKindUseCase)
    factoryOf(::DiscardActivityUseCase)
}
