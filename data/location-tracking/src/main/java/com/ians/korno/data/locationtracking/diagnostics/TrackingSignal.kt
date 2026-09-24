package com.ians.korno.data.locationtracking.diagnostics

import com.ians.korno.core.logging.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
     * Ключ, по которому сигнал считается повторным.
     *
     * Общий `distinctUntilChanged` по потоку давит только подряд идущие дубли, а сигналы
     * разных типов чередуются: `ACTION_BATTERY_CHANGED` прилетает несколько раз в минуту
     * и разбивает подряд идущие одинаковые состояния питания. В логе разобранной прогулки
     * из-за этого оказалось четыре одинаковых «энергосбережение выключено» и семь
     * одинаковых «заряд 40%».
     */
    val dedupKey: Any get() = this

    /** Уровень записи в логе: большинство сигналов редки и важны, поэтому `INFO`. */
    val logLevel: LogLevel get() = LogLevel.INFO

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

    /**
     * Чем кончилась подписка на распознавание: `SUBSCRIBED` — Play Services её принял,
     * `NO_PERMISSION` — подписываться не стали. Без этой строки «распознавание молчит» не
     * отличить от «подписки нет»: в записях 4 и 5 не было ни одного вердикта и ни одной
     * ошибки. Отказ самих Play Services приходит ошибкой потока.
     *
     * [subscription] — номер подписки в процессе: по нему снятие и тишина в логе
     * сопоставляются с конкретной подпиской.
     */
    data class ActivityRecognitionSubscription(
        val status: String,
        val subscription: Int? = null,
    ) : TrackingSignal {
        override val name = "Activity recognition subscription"
        override val fields = buildMap {
            put("status", status)
            subscription?.let { put("subscription", it.toString()) }
        }
    }

    /**
     * Каждый broadcast распознавания, в том числе без результата. Раньше интент без
     * результата отбрасывался молча, и в записях 18 и 19 было не отличить «Play Services
     * ничего не шлёт» от «шлёт, но не в том виде».
     *
     * [resultAgeMillis] — сколько вердикт шёл до приложения. [receivedAtElapsedMillis]
     * в поля не идёт (время есть у самой записи), но делает каждый приём отдельным
     * сигналом: одинаковые подряд broadcast'ы не должны схлопываться при дедупликации.
     */
    data class ActivityRecognitionBroadcast(
        val subscription: Int,
        val hasResult: Boolean,
        val action: String?,
        val extrasKeys: List<String>,
        val resultAgeMillis: Long?,
        val receivedAtElapsedMillis: Long,
    ) : TrackingSignal {
        override val name = "Activity recognition broadcast"
        override val fields = mapOf(
            "subscription" to subscription.toString(),
            "hasResult" to hasResult.toString(),
            "action" to action.toString(),
            "extras" to extrasKeys.joinToString(),
            "resultAgeMillis" to resultAgeMillis.toString(),
        )

        /** Приходит раз в несколько секунд; broadcast без результата — аномалия. */
        override val logLevel = if (hasResult) LogLevel.DEBUG else LogLevel.WARN
    }

    /**
     * Разрешения на момент старта записи. Отозванное распознавание или «приблизительная»
     * геолокация объясняют пустые сегменты и рыхлый трек раньше любых алгоритмов.
     */
    data class PermissionsSnapshot(
        val preciseLocation: Boolean,
        val approximateLocation: Boolean,
        val backgroundLocation: Boolean,
        val activityRecognition: Boolean,
        val notifications: Boolean,
    ) : TrackingSignal {
        override val name = "Permissions snapshot"
        override val fields = mapOf(
            "preciseLocation" to preciseLocation.toString(),
            "approximateLocation" to approximateLocation.toString(),
            "backgroundLocation" to backgroundLocation.toString(),
            "activityRecognition" to activityRecognition.toString(),
            "notifications" to notifications.toString(),
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

    /**
     * Doze — единственное состояние, которое режет и геолокацию, и распознавание
     * активности одновременно, и до сих пор его в логе не было видно совсем: одновременный
     * провал обоих источников приходилось только предполагать.
     *
     * Рядом идёт `ignoringBatteryOptimizations`: приложение из белого списка Doze
     * переносит куда мягче, и без этого флага непонятно, чего вообще ждать от записи.
     */
    data class DeviceIdleModeChanged(
        val isIdle: Boolean,
        val isIgnoringBatteryOptimizations: Boolean,
    ) : TrackingSignal {
        override val name = "Device idle mode changed"
        override val fields = mapOf(
            "deviceIdleMode" to isIdle.toString(),
            "ignoringBatteryOptimizations" to isIgnoringBatteryOptimizations.toString(),
        )
    }

    /**
     * Ограничения фоновой работы, наложенные системой на само приложение (Android 9+).
     * Снимок пишется при старте записи: он объясняет, почему сервис получает точки реже,
     * чем просит, ещё до того, как устройство успело уйти в Doze.
     */
    data class BackgroundRestrictionChanged(
        val isBackgroundRestricted: Boolean,
        val standbyBucket: String,
    ) : TrackingSignal {
        override val name = "Background restriction changed"
        override val fields = mapOf(
            "backgroundRestricted" to isBackgroundRestricted.toString(),
            "standbyBucket" to standbyBucket,
        )
    }
}

/** Источник диагностических сигналов. Подписка живёт ровно столько, сколько идёт запись. */
interface TrackingSignalSource {
    fun observeSignals(): Flow<TrackingSignal>
}

/**
 * Пропускает только смену состояния внутри каждого типа сигнала.
 *
 * Нужен именно поэтапный ключ, а не `distinctUntilChanged` по всему потоку: типы сигналов
 * чередуются и взаимно «размыкают» подряд идущие дубли друг друга.
 */
fun Flow<TrackingSignal>.distinctPerSignalType(): Flow<TrackingSignal> = flow {
    val lastByType = mutableMapOf<String, Any>()
    collect { signal ->
        val type = signal::class.qualifiedName ?: signal.name
        if (lastByType.put(type, signal.dedupKey) != signal.dedupKey) emit(signal)
    }
}
