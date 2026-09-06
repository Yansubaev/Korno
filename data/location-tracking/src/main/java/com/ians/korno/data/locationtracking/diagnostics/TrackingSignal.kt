package com.ians.korno.data.locationtracking.diagnostics

import kotlinx.coroutines.flow.Flow

/**
 * Событие, пришедшее не от приложения, а от системы, и способное объяснить поведение трека:
 * пропавшие точки, залипшую автопаузу, оборванную запись.
 *
 * Сигналы существуют только ради диагностики, поэтому каждый несёт готовый набор полей
 * для лога — разбирать их в типизированные модели незачем.
 */
sealed interface TrackingSignal {
    /** Короткое имя события; попадает в сообщение лога. */
    val name: String

    /** Поля события; попадают в `fields` записи лога. */
    val fields: Map<String, String>

    /**
     * Что система думает о происходящем: ходьба, бег, велосипед, автомобиль или покой.
     * Расхождение с выбранным типом активности объясняет и «слишком быстрый» трек,
     * и остановки, которых пользователь не делал.
     */
    data class ActivityRecognized(
        val activityType: String,
        val confidencePercent: Int,
        val alternatives: Map<String, Int>,
    ) : TrackingSignal {
        override val name = "Activity recognized"
        override val fields = mapOf(
            "detectedActivity" to activityType,
            "confidencePercent" to confidencePercent.toString(),
            "alternatives" to alternatives.entries.joinToString { "${it.key}=${it.value}" },
        )
    }

    /** Пользователь выключил геолокацию посреди записи — самая частая причина дыры в треке. */
    data class LocationProvidersChanged(
        val gpsEnabled: Boolean,
        val networkEnabled: Boolean,
    ) : TrackingSignal {
        override val name = "Location providers changed"
        override val fields = mapOf(
            "gpsEnabled" to gpsEnabled.toString(),
            "networkEnabled" to networkEnabled.toString(),
        )
    }

    /** Режим энергосбережения режет частоту обновлений независимо от настроек приложения. */
    data class PowerSaveModeChanged(val enabled: Boolean) : TrackingSignal {
        override val name = "Power save mode changed"
        override val fields = mapOf("powerSaveMode" to enabled.toString())
    }

    data class BatteryChanged(
        val levelPercent: Int,
        val isCharging: Boolean,
    ) : TrackingSignal {
        override val name = "Battery state changed"
        override val fields = mapOf(
            "batteryPercent" to levelPercent.toString(),
            "charging" to isCharging.toString(),
        )
    }

    /**
     * Гашение экрана — момент, с которого начинают действовать ограничения на фоновую
     * работу; по нему видно, связан ли пропуск точек с уходом приложения в фон.
     */
    data class ScreenStateChanged(val isInteractive: Boolean) : TrackingSignal {
        override val name = "Screen state changed"
        override val fields = mapOf("screenInteractive" to isInteractive.toString())
    }
}

/** Источник диагностических сигналов. Подписка живёт ровно столько, сколько идёт запись. */
interface TrackingSignalSource {
    fun observeSignals(): Flow<TrackingSignal>
}
