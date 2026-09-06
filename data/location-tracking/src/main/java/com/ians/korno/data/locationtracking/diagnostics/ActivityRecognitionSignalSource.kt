package com.ians.korno.data.locationtracking.diagnostics

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.ians.korno.data.locationtracking.permission.LocationPermissionChecker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow

/**
 * Подписка на распознавание активности. У Play Services нет колбэка: результат приходит
 * broadcast-интентом, поэтому приёмник регистрируется динамически и живёт ровно столько,
 * сколько собирается поток — в манифесте ему делать нечего.
 *
 * Подписка стоит батареи, поэтому включается только под запись трека и только когда
 * диагностическое логирование действительно ведётся (см. вызывающий сервис).
 */
internal class ActivityRecognitionSignalSource(
    private val context: Context,
    private val permissionChecker: LocationPermissionChecker,
) : TrackingSignalSource {

    override fun observeSignals(): Flow<TrackingSignal> {
        if (!permissionChecker.hasActivityRecognitionPermission()) return emptyFlow()
        return callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!ActivityRecognitionResult.hasResult(intent)) return
                    val result = ActivityRecognitionResult.extractResult(intent) ?: return
                    trySend(result.toSignal())
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_ACTIVITY_UPDATE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(ACTION_ACTIVITY_UPDATE).setPackage(context.packageName),
                pendingIntentFlags(),
            )
            val client = ActivityRecognition.getClient(context)
            // Запрос может отвалиться на устройстве без Play Services — тогда поток просто
            // не отдаст ни одного сигнала, и запись трека от этого не пострадает.
            val request = runCatching {
                client.requestActivityUpdates(UPDATE_INTERVAL_MILLIS, pendingIntent)
            }
            if (request.isFailure) {
                runCatching { context.unregisterReceiver(receiver) }
                close()
                return@callbackFlow
            }

            awaitClose {
                runCatching { client.removeActivityUpdates(pendingIntent) }
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
            // Распознавание повторяет один и тот же вердикт каждые несколько секунд;
            // в логе нужен момент смены, а не подтверждение прежнего состояния.
            .distinctUntilChanged()
    }

    private fun ActivityRecognitionResult.toSignal(): TrackingSignal.ActivityRecognized {
        val ranked = probableActivities.sortedByDescending(DetectedActivity::getConfidence)
        val mostProbable = ranked.firstOrNull()
        return TrackingSignal.ActivityRecognized(
            activityType = mostProbable?.type?.activityName() ?: "UNKNOWN",
            confidencePercent = mostProbable?.confidence ?: 0,
            alternatives = ranked.drop(1)
                .take(MAX_ALTERNATIVES)
                .associate { it.type.activityName() to it.confidence },
        )
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Play Services дописывает результат в интент, поэтому MUTABLE обязателен.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun Int.activityName(): String = when (this) {
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.WALKING -> "WALKING"
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.TILTING -> "TILTING"
        else -> "UNKNOWN"
    }

    private companion object {
        const val ACTION_ACTIVITY_UPDATE = "com.ians.korno.ACTION_ACTIVITY_RECOGNITION_UPDATE"
        const val REQUEST_CODE = 4201
        const val UPDATE_INTERVAL_MILLIS = 15_000L
        const val MAX_ALTERNATIVES = 3
    }
}
