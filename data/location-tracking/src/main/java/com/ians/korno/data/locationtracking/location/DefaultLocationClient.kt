package com.ians.korno.data.locationtracking.location

import android.annotation.SuppressLint
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.ians.korno.core.domain.model.GpsAccuracy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal class DefaultLocationClient(
    private val fusedLocationProviderClient: FusedLocationProviderClient,
) : LocationClient {

    @SuppressLint("MissingPermission")
    override fun locationUpdates(config: LocationUpdateConfig): Flow<LocationSample> = callbackFlow {
        val priority = when (config.accuracy) {
            GpsAccuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
            GpsAccuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            GpsAccuracy.BATTERY_SAVER -> Priority.PRIORITY_LOW_POWER
        }
        val request = LocationRequest.Builder(priority, config.intervalMillis)
            .setMinUpdateIntervalMillis(config.minIntervalMillis)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(
                        LocationSample(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
                            timestampEpochMillis = location.time,
                            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
                        ),
                    )
                }
            }
        }

        fusedLocationProviderClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose { fusedLocationProviderClient.removeLocationUpdates(callback) }
    }
}
