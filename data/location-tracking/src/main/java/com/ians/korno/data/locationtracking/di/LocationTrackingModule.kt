package com.ians.korno.data.locationtracking.di

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.ians.korno.data.locationtracking.diagnostics.ActivityRecognitionSignalSource
import com.ians.korno.data.locationtracking.diagnostics.SystemStateSignalSource
import com.ians.korno.data.locationtracking.diagnostics.TrackingSignalSource
import com.ians.korno.data.locationtracking.location.DefaultLocationClient
import com.ians.korno.data.locationtracking.location.LocationClient
import com.ians.korno.data.locationtracking.permission.DefaultLocationPermissionChecker
import com.ians.korno.data.locationtracking.permission.LocationPermissionChecker
import com.ians.korno.data.locationtracking.steps.StepCounterClient
import org.koin.dsl.bind
import org.koin.dsl.module

val locationTrackingModule = module {
    single<FusedLocationProviderClient> { LocationServices.getFusedLocationProviderClient(get<Context>()) }
    single { DefaultLocationClient(get()) } bind LocationClient::class
    single { DefaultLocationPermissionChecker(get()) } bind LocationPermissionChecker::class
    single { SystemStateSignalSource(get()) } bind TrackingSignalSource::class
    single { ActivityRecognitionSignalSource(get(), get(), get()) }
    single { StepCounterClient(get(), get()) }
}
