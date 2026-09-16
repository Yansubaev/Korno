package com.ians.korno.data.locationtracking.location

import com.ians.korno.core.domain.model.ActivityType
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
     * Сколько автопауза держится на частоте записи, прежде чем разредить опрос.
     *
     * Разрежать сразу — значит решать выход из паузы по самым плохим точкам записи. В
     * разобранном логе прогулки на автопаузе погрешность была вдвое хуже, чем во время
     * движения (33 м против 16), а половина пауз длилась меньше минуты: до экономии дело
     * так и не доходило, зато каждый выход из паузы решался по загрублённому сигналу.
     * Сюда же ложится и дребезг: каждая смена частоты пересоздаёт запрос к провайдеру, и
     * в двадцать секунд после этого погрешность заметно хуже обычной.
     *
     * Минута выбрана по тому, чем стоянки вообще бывают. Светофор, переход, завязать
     * шнурки — это десятки секунд, и на них частота теперь не меняется вовсе. Всё, что
     * дольше, — привал, и там разрежённый опрос уже окупается.
     */
    const val AUTO_PAUSE_SETTLE_MILLIS = 60_000L

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

    /**
     * @param autoPauseSettled продержалась ли автопауза дольше [AUTO_PAUSE_SETTLE_MILLIS].
     *   Пока нет, частота остаётся такой же, как при записи.
     * @param autoPauseMovementHint детектор уже видит возможное возобновление движения.
     *   В этот момент полный темп опроса важнее экономии: кандидат должен либо быстро
     *   подтвердиться, либо так же быстро отпасть.
     */
    fun configFor(
        accuracy: GpsAccuracy,
        status: RecordingStatus,
        activityType: ActivityType,
        autoPauseSettled: Boolean = false,
        autoPauseMovementHint: Boolean = false,
    ): LocationUpdateConfig {
        val recording = LocationUpdateConfig(
            intervalMillis = recordingIntervalMillis(accuracy, activityType),
            // Пока идёт запись, приоритет не опускается ниже высокого, какой бы ни была
            // настройка точности. Настройка обязана управлять частотой опроса — это и есть
            // основной расход батареи, — но не тем, включён ли вообще GNSS-чип. С BALANCED
            // и LOW_POWER система вправе обслуживать запрос сетевыми фиксами: в разобранном
            // логе прогулки это дало стометровую погрешность, отсутствие доплеровской
            // скорости и провалы опроса до двух-трёх минут. Трек из таких точек не спасает
            // никакая постобработка.
            accuracy = GpsAccuracy.HIGH,
        )
        return when (status) {
            // На автопаузе приоритет тоже остаётся высоким: батарею экономит уже
            // разрежённый интервал, а вот выход из автопаузы решается по координатам и
            // доплеровской скорости — ровно тому, чего сетевой фикс не даёт.
            RecordingStatus.AUTO_PAUSE -> if (autoPauseSettled && !autoPauseMovementHint) {
                recording.copy(
                    intervalMillis = (recording.intervalMillis * AUTO_PAUSE_FACTOR)
                        .coerceAtMost(AUTO_PAUSE_MAX_INTERVAL_MILLIS),
                )
            } else {
                recording
            }

            RecordingStatus.MANUAL_PAUSE -> LocationUpdateConfig(
                intervalMillis = MANUAL_PAUSE_INTERVAL_MILLIS,
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
            -> recording
        }
    }

    /**
     * Темп активности: как быстро под ногами меняется то, ради чего вообще спрашивают
     * координаты.
     *
     * Пешеход за десять секунд проходит тринадцать метров — меньше, чем городская
     * погрешность фикса, и разрежённый опрос ему обходится дорого: точек мало, каждая
     * шумная, а окну детектора остановок нужно набрать несколько штук. Машина за те же
     * десять секунд проезжает полтораста метров, её `maximumAccuracyMeters` вдвое шире,
     * и поездки длятся часами — там экономия батареи стоит больше лишней точки.
     *
     * Хайкинг стоит отдельно от ходьбы: то же тело, но открытая местность, где сигнал
     * обычно ловится увереннее, чем в городе, а сама прогулка растягивается на часы —
     * экономия батареи там окупается так же, как у машины.
     */
    private enum class TrackingTempo { ON_FOOT, TRAIL, WHEELED, VEHICLE }

    private val ActivityType.tempo: TrackingTempo
        get() = when (this) {
            ActivityType.WALKING, ActivityType.RUNNING -> TrackingTempo.ON_FOOT
            ActivityType.HIKING -> TrackingTempo.TRAIL
            ActivityType.CYCLING, ActivityType.OTHER -> TrackingTempo.WHEELED
            ActivityType.DRIVING -> TrackingTempo.VEHICLE
        }

    /**
     * Шаг опроса во время записи.
     *
     * Интервал здесь один, без отдельного «самого частого»: провайдер выдаёт фиксы именно
     * по нижней границе, а не по запрошенному интервалу, и прежняя пара 4000/2000
     * означала на деле 2000. Настройки обещали одно, а делали другое — и различались вдвое
     * меньше, чем задумывалось. Теперь запрошенное и выданное совпадают.
     *
     * Секунда для пешехода на HIGH — не опечатка: GNSS-чип всё равно считает решение раз
     * в секунду, просить реже — не выключить его, а лишь выбросить половину уже
     * посчитанных фиксов.
     */
    private fun recordingIntervalMillis(
        accuracy: GpsAccuracy,
        activityType: ActivityType,
    ): Long = when (activityType.tempo) {
        TrackingTempo.ON_FOOT -> when (accuracy) {
            GpsAccuracy.HIGH -> 1_000L
            GpsAccuracy.BALANCED -> 2_500L
            // Четыре секунды, а не десять. Прежний BATTERY_SAVER выдавал фиксы раз в пять
            // секунд с медианной погрешностью 16 м и девяностым процентилем 49 — при
            // пороге отбраковки в 40 м для ходьбы. Три точки из десяти не доживали до
            // трека вовсе, и режим переставал годиться для прогулки совсем.
            GpsAccuracy.BATTERY_SAVER -> 4_000L
        }

        TrackingTempo.TRAIL -> when (accuracy) {
            GpsAccuracy.HIGH -> 2_000L
            GpsAccuracy.BALANCED -> 5_000L
            GpsAccuracy.BATTERY_SAVER -> 8_000L
        }

        TrackingTempo.WHEELED -> when (accuracy) {
            GpsAccuracy.HIGH -> 1_500L
            GpsAccuracy.BALANCED -> 3_500L
            GpsAccuracy.BATTERY_SAVER -> 7_000L
        }

        TrackingTempo.VEHICLE -> when (accuracy) {
            GpsAccuracy.HIGH -> 2_000L
            GpsAccuracy.BALANCED -> 5_000L
            GpsAccuracy.BATTERY_SAVER -> 10_000L
        }
    }
}
