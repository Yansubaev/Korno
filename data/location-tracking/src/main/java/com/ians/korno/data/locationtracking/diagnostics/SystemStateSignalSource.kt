package com.ians.korno.data.locationtracking.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Состояние системы вокруг записи трека. Стартовый снимок отдаётся сразу при подписке:
 * без него по логу нельзя отличить «энергосбережение включили в середине пробежки»
 * от «оно было включено с самого начала».
 */
internal class SystemStateSignalSource(
    private val context: Context,
) : TrackingSignalSource {

    override fun observeSignals(): Flow<TrackingSignal> = callbackFlow {
        val locationManager = context.getSystemService<LocationManager>()
        val powerManager = context.getSystemService<PowerManager>()

        fun currentProvidersSignal() = TrackingSignal.LocationProvidersChanged(
            gpsEnabled = locationManager
                ?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true,
            networkEnabled = locationManager
                ?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true,
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val signal = when (intent.action) {
                    LocationManager.PROVIDERS_CHANGED_ACTION -> currentProvidersSignal()
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        TrackingSignal.PowerSaveModeChanged(
                            enabled = powerManager?.isPowerSaveMode == true,
                        )
                    }
                    Intent.ACTION_SCREEN_ON -> TrackingSignal.ScreenStateChanged(true)
                    Intent.ACTION_SCREEN_OFF -> TrackingSignal.ScreenStateChanged(false)
                    Intent.ACTION_BATTERY_CHANGED -> intent.toBatterySignal()
                    else -> null
                }
                if (signal != null) trySend(signal)
            }
        }

        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        send(currentProvidersSignal())
        send(TrackingSignal.PowerSaveModeChanged(powerManager?.isPowerSaveMode == true))
        send(
            TrackingSignal.ScreenStateChanged(
                isInteractive = powerManager?.isInteractive == true,
            ),
        )

        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }
        // ACTION_BATTERY_CHANGED прилетает несколько раз в минуту, а PROVIDERS_CHANGED —
        // парами на каждый провайдер. В лог должно попадать изменение, а не поток шума.
        .distinctUntilChanged()

    /**
     * Заряд огрубляется до шага в [BATTERY_STEP_PERCENT]: точное значение здесь не нужно,
     * а каждый процент — это лишняя запись в логе.
     */
    private fun Intent.toBatterySignal(): TrackingSignal? {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val percent = level * 100 / scale
        return TrackingSignal.BatteryChanged(
            levelPercent = percent / BATTERY_STEP_PERCENT * BATTERY_STEP_PERCENT,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private companion object {
        const val BATTERY_STEP_PERCENT = 5
    }
}
