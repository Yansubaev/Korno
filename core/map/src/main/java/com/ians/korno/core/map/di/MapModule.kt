package com.ians.korno.core.map.di

import com.ians.korno.core.domain.repository.MapCacheRepository
import com.ians.korno.core.map.MapLibreCacheRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val mapModule = module {
    singleOf(::MapLibreCacheRepository) bind MapCacheRepository::class
}
