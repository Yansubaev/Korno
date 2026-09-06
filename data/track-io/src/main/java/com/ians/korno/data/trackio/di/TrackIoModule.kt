package com.ians.korno.data.trackio.di

import androidx.work.WorkManager
import com.ians.korno.core.domain.repository.TrackFileRepository
import com.ians.korno.data.gpxexport.TrackFileCodec
import com.ians.korno.data.trackio.io.DocumentStore
import com.ians.korno.data.trackio.repository.DefaultTrackFileRepository
import com.ians.korno.data.trackio.work.TrackFileWriter
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val trackIoModule = module {
    single { WorkManager.getInstance(androidContext()) }
    singleOf(::DocumentStore)
    single { TrackFileCodec() }
    singleOf(::TrackFileWriter)
    singleOf(::DefaultTrackFileRepository) bind TrackFileRepository::class
}
