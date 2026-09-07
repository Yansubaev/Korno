package com.ians.korno.data.locationtracking.location

import android.annotation.SuppressLint
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.ians.korno.core.domain.model.GpsAccuracy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Ключ, под которым Play Services кладёт число спутников в extras фикса. */
private const val EXTRA_SATELLITES = "satellites"

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
            // Без явной гранулярности Fused Location Provider выдаёт координаты по уровню
            // выданного разрешения. Приложение просит ACCESS_FINE_LOCATION, но при
            // умолчании система вправе огрубить фикс до сетевого — в логах это стометровая
            // погрешность без скорости, и трек из таких точек состоит из прыжков.
            .setGranularity(Granularity.GRANULARITY_FINE)
            // Батчинг фиксов ради экономии батареи ломает и живую карту, и автопаузу:
            // пачка точек приходит разом, шаг между сэмплами скачет, окно детектора
            // остановок схлопывается.
            .setMaxUpdateDelayMillis(0L)
            // Первый фикс нужен сразу, даже грубый: до него запись стоит на месте, а
            // «догнать» точность приёмник успеет за следующие несколько секунд.
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location -> trySend(location.toSample()) }
            }
        }

        fusedLocationProviderClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose { fusedLocationProviderClient.removeLocationUpdates(callback) }
    }
}

private fun Location.toSample(): LocationSample = LocationSample(
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = altitude.takeIf { hasAltitude() },
    timestampEpochMillis = time,
    accuracyMeters = accuracy.takeIf { hasAccuracy() },
    speedMetersPerSecond = speed.takeIf { hasSpeed() },
    provider = provider,
    ageMillis = ageMillis(),
    verticalAccuracyMeters = verticalAccuracyOrNull(),
    speedAccuracyMetersPerSecond = speedAccuracyOrNull(),
    bearingDegrees = bearing.takeIf { hasBearing() },
    bearingAccuracyDegrees = bearingAccuracyOrNull(),
    satelliteCount = extras?.getInt(EXTRA_SATELLITES, -1)?.takeIf { it >= 0 },
)

/** Точности по вертикали и по скорости появились только в Android 8. */
private fun Location.verticalAccuracyOrNull(): Float? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasVerticalAccuracy()) return null
    return verticalAccuracyMeters
}

private fun Location.speedAccuracyOrNull(): Float? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasSpeedAccuracy()) return null
    return speedAccuracyMetersPerSecond
}

private fun Location.bearingAccuracyOrNull(): Float? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasBearingAccuracy()) return null
    return bearingAccuracyDegrees
}

/**
 * Возраст фикса считается по монотонным часам: [Location.getTime] берётся из системного
 * времени и переезжает вместе с ним, а `elapsedRealtimeNanos` — нет.
 */
private fun Location.ageMillis(): Long? {
    if (elapsedRealtimeNanos <= 0L) return null
    val age = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
    return (age / 1_000_000L).coerceAtLeast(0L)
}
