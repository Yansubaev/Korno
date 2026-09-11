package com.ians.korno.data.locationtracking.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.getSystemService
import com.ians.korno.data.locationtracking.permission.LocationPermissionChecker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Кумулятивное число шагов с момента последней перезагрузки устройства.
 *
 * `TYPE_STEP_COUNTER` — не «шаги за сессию», а растущий с загрузки счётчик; шаги активности
 * считаются разницей между первым и последующими значениями (см.
 * `TrackingSessionCoordinator.applyStepCounter`). Начиная с Android 10 датчик требует
 * `ACTIVITY_RECOGNITION`, поэтому переиспользуется та же проверка, что и для распознавания
 * активности.
 */
class StepCounterClient(
    private val context: Context,
    private val permissionChecker: LocationPermissionChecker,
) {
    fun stepCounts(): Flow<Long> {
        val sensorManager = context.getSystemService<SensorManager>() ?: return emptyFlow()
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return emptyFlow()
        if (!permissionChecker.hasActivityRecognitionPermission()) return emptyFlow()

        return callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    trySend(event.values[0].toLong())
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            awaitClose { sensorManager.unregisterListener(listener) }
        }
    }
}
