package com.ians.korno.data.locationtracking.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.ians.korno.data.locationtracking.permission.LocationPermissionChecker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Показание шагомера: [counter] — кумулятивный счётчик датчика, [timestampEpochMillis] —
 * момент показания по сенсору. Время доставки не годится: в doze датчик без пробуждения
 * может отдавать показания пачками.
 */
data class StepCounterReading(val counter: Long, val timestampEpochMillis: Long)

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
    fun stepCounts(): Flow<StepCounterReading> {
        val sensorManager = context.getSystemService<SensorManager>() ?: return emptyFlow()
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return emptyFlow()
        if (!permissionChecker.hasActivityRecognitionPermission()) return emptyFlow()

        return callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // `SensorEvent.timestamp` — наносекунды шкалы elapsedRealtime.
                    val ageMillis = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
                    trySend(StepCounterReading(event.values[0].toLong(), System.currentTimeMillis() - ageMillis))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            awaitClose { sensorManager.unregisterListener(listener) }
        }
    }
}
