package com.ians.korno.core.domain.repository

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    fun observePreferences(): Flow<UserPreferences>

    suspend fun initialize(): EmptyResult<DataError.Local>

    suspend fun setMeasurementSystem(value: MeasurementSystem): EmptyResult<DataError.Local>

    suspend fun setAutoPauseEnabled(value: Boolean): EmptyResult<DataError.Local>

    suspend fun setGpsAccuracy(value: GpsAccuracy): EmptyResult<DataError.Local>

    suspend fun setAutoPauseSensitivity(value: AutoPauseSensitivity): EmptyResult<DataError.Local>

    suspend fun setDynamicColorEnabled(value: Boolean): EmptyResult<DataError.Local>

    suspend fun setPrivacyZone(value: PrivacyZone): EmptyResult<DataError.Local>

    suspend fun setOnboardingCompleted(value: Boolean): EmptyResult<DataError.Local>

    suspend fun setLastActivityType(value: ActivityType): EmptyResult<DataError.Local>

    suspend fun setLastRecordingAutoMode(value: Boolean): EmptyResult<DataError.Local>
}
