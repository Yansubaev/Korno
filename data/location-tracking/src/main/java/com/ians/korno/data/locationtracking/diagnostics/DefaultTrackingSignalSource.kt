package com.ians.korno.data.locationtracking.diagnostics

import android.content.Context
import com.ians.korno.data.locationtracking.permission.LocationPermissionChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/** Один поток на оба источника: потребителю незачем знать, откуда пришёл сигнал. */
internal class DefaultTrackingSignalSource(
    context: Context,
    permissionChecker: LocationPermissionChecker,
) : TrackingSignalSource {

    private val sources = listOf(
        SystemStateSignalSource(context),
        ActivityRecognitionSignalSource(context, permissionChecker),
    )

    override fun observeSignals(): Flow<TrackingSignal> =
        sources.map(TrackingSignalSource::observeSignals).merge()
}
