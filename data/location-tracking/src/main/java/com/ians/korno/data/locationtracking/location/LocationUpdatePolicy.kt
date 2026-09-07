package com.ians.korno.data.locationtracking.location

import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.RecordingStatus

/**
 * Как часто и с каким приоритетом спрашивать координаты в текущем состоянии записи.
 *
 * На паузе трек не растёт, а GPS продолжал бы работать в том же режиме и есть батарею.
 * Совсем отписываться нельзя: приёмник остынет, и после возобновления первые точки
 * придут с большой погрешностью — поэтому частота снижается, а подписка остаётся.
 */
object LocationUpdatePolicy {

    /**
     * Автопауза должна замечать возобновление движения, поэтому запас невелик.
     * Коэффициент намеренно небольшой: при шаге опроса порядка окна детектора остановок
     * (`TrackProcessingConfig.stopWindowMillis`) окно схлопывается до одной точки, и выход
     * из автопаузы начинает зависеть от запасного пути в `StopDetectionUseCase`.
     */
    private const val AUTO_PAUSE_FACTOR = 2
    private const val AUTO_PAUSE_MAX_INTERVAL_MILLIS = 15_000L

    /** Ручную паузу снимает человек, и до этого момента точки не нужны вовсе. */
    private const val MANUAL_PAUSE_INTERVAL_MILLIS = 60_000L
    private const val MANUAL_PAUSE_MIN_INTERVAL_MILLIS = 30_000L

    fun configFor(
        accuracy: GpsAccuracy,
        status: RecordingStatus,
    ): LocationUpdateConfig {
        val base = baseConfig(accuracy)
        return when (status) {
            // На автопаузе приоритет тоже остаётся высоким: батарею экономит уже
            // разрежённый интервал, а вот выход из автопаузы решается по координатам и
            // доплеровской скорости — ровно тому, чего сетевой фикс не даёт.
            RecordingStatus.AUTO_PAUSE -> {
                val interval = (base.intervalMillis * AUTO_PAUSE_FACTOR)
                    .coerceAtMost(AUTO_PAUSE_MAX_INTERVAL_MILLIS)
                base.recordingQuality().copy(
                    intervalMillis = interval,
                    minIntervalMillis = interval / 2,
                )
            }

            RecordingStatus.MANUAL_PAUSE -> LocationUpdateConfig(
                intervalMillis = MANUAL_PAUSE_INTERVAL_MILLIS,
                minIntervalMillis = MANUAL_PAUSE_MIN_INTERVAL_MILLIS,
                // Приоритет понижается независимо от настройки: точность на паузе
                // ни на что не влияет, а высокий приоритет держал бы включённым сам чип GPS.
                accuracy = GpsAccuracy.BATTERY_SAVER,
            )

            // IDLE — это прогрев перед стартом, WAITING_FOR_GPS — ожидание первой точки
            // уже после нажатия «Начать»: в обоих случаях частые запросы как раз и нужны.
            RecordingStatus.IDLE,
            RecordingStatus.WAITING_FOR_GPS,
            RecordingStatus.RECORDING,
            RecordingStatus.STOPPED,
            -> base.recordingQuality()
        }
    }

    /**
     * Пока идёт запись, приоритет не опускается ниже высокого, какой бы ни была настройка
     * точности.
     *
     * Настройка обязана управлять частотой опроса — это и есть основной расход батареи, —
     * но не тем, включён ли вообще GNSS-чип. С BALANCED и LOW_POWER система вправе
     * обслуживать запрос сетевыми фиксами: в разобранном логе прогулки это дало
     * стометровую погрешность, отсутствие доплеровской скорости и провалы опроса
     * до двух-трёх минут. Трек из таких точек не спасает никакая постобработка.
     */
    private fun LocationUpdateConfig.recordingQuality(): LocationUpdateConfig =
        copy(accuracy = GpsAccuracy.HIGH)

    private fun baseConfig(accuracy: GpsAccuracy): LocationUpdateConfig = when (accuracy) {
        GpsAccuracy.HIGH -> LocationUpdateConfig(
            intervalMillis = 2_000L,
            minIntervalMillis = 1_000L,
            accuracy = accuracy,
        )
        GpsAccuracy.BALANCED -> LocationUpdateConfig(
            intervalMillis = 4_000L,
            minIntervalMillis = 2_000L,
            accuracy = accuracy,
        )
        GpsAccuracy.BATTERY_SAVER -> LocationUpdateConfig(
            intervalMillis = 10_000L,
            minIntervalMillis = 5_000L,
            accuracy = accuracy,
        )
    }
}
