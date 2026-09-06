package com.ians.korno.core.data.di

import com.ians.korno.core.data.local.RoomTrackLocalDataSource
import com.ians.korno.core.data.local.TrackLocalDataSource
import com.ians.korno.core.data.repository.DefaultTrackRepository
import com.ians.korno.core.data.preferences.DataStoreUserPreferencesRepository
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val coreDataModule = module {
    singleOf(::RoomTrackLocalDataSource) bind TrackLocalDataSource::class
    singleOf(::DefaultTrackRepository) bind TrackRepository::class
    singleOf(::DataStoreUserPreferencesRepository) bind UserPreferencesRepository::class
}
