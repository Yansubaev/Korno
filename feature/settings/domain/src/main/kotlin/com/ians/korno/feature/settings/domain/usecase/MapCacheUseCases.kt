package com.ians.korno.feature.settings.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.repository.MapCacheRepository

class GetMapCacheSizeUseCase(
    private val mapCacheRepository: MapCacheRepository,
) {
    suspend operator fun invoke(): Long? = mapCacheRepository.cacheSizeBytes()
}

class ClearMapCacheUseCase(
    private val mapCacheRepository: MapCacheRepository,
) {
    suspend operator fun invoke(): EmptyResult<DataError.Local> = mapCacheRepository.clear()
}
