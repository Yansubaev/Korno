package com.ians.korno.feature.tracking.domain.repository

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import kotlinx.coroutines.flow.Flow

interface TrackingController {
    fun observeSession(): Flow<RecordingSession>

    fun prepare()

    fun cancelPreparation()

    /** @param autoMode вид движения определяет детектор, [kind] не учитывается. */
    suspend fun start(kind: SegmentKind, autoMode: Boolean)

    /** Авторежим возможен: есть разрешение на распознавание активности и Play Services. */
    fun isAutoModeAvailable(): Boolean

    /** Ручная поправка вида текущего сегмента в авторежиме; граница — «сейчас». */
    suspend fun changeSegmentKind(kind: SegmentKind)

    fun pause()

    fun resume()

    suspend fun stop(): Result<Long, DataError.Local>

    suspend fun recoverActiveSession()
}
