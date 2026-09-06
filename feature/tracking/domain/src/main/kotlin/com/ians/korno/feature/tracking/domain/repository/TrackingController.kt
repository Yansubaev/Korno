package com.ians.korno.feature.tracking.domain.repository

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import kotlinx.coroutines.flow.Flow

interface TrackingController {
    fun observeSession(): Flow<RecordingSession>

    fun prepare()

    fun cancelPreparation()

    suspend fun start(activityType: ActivityType)

    fun pause()

    fun resume()

    suspend fun stop(): Result<Long, DataError.Local>

    suspend fun recoverActiveSession()
}
