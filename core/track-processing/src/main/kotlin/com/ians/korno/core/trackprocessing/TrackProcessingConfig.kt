package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.TRACK_GAP_THRESHOLD_MILLIS
import kotlin.math.max

data class TrackProcessingConfig(
    val maximumSpeedMetersPerSecond: Double,
    /**
     * Погрешность, выше которой фикс не считается измерением.
     *
     * Точка с погрешностью в сотню метров не описывает положение — она описывает круг,
     * в котором оно где-то находится. Пропущенная в трек, она даёт прыжок на всю свою
     * погрешность и утаскивает за собой и дистанцию, и максимальную скорость.
     */
    val maximumAccuracyMeters: Double,
    /**
     * Сколько сигм заявленной погрешности прибавляется к порогу правдоподобного
     * перемещения. Два независимых фикса расходятся в среднем на 1.25 сигмы только
     * из-за шума, поэтому три сигмы — это уже «так шум не разойдётся».
     */
    val outlierAccuracySigmas: Double = 3.0,
    /**
     * Доля погрешности, ниже которой перемещение считается дрожанием, а не шагом.
     * Порог применяется к смещению от последней зачтённой точки, а не к каждому
     * сегменту: иначе на частом опросе он съел бы ходьбу целиком.
     */
    val noiseDeadbandFactor: Double = 0.5,
    /**
     * Отрезок длиннее этого не наблюдался и не даёт ни мгновенной, ни максимальной
     * скорости: делить прямую между двумя фиксами на время дыры бессмысленно.
     */
    val maximumSegmentDurationMillis: Long = TRACK_GAP_THRESHOLD_MILLIS,
    /**
     * Окно, по которому считается максимальная скорость.
     *
     * По одному отрезку её считать нельзя: при шаге опроса в семь секунд и погрешности
     * в двадцать метров дрожание координат само по себе даёт пять метров в секунду, и
     * прогулка получает максимум под двадцать километров в час. Шум за окно усредняется,
     * а реальный рывок — нет, поэтому окно и решает.
     */
    val maximumSpeedWindowMillis: Long = 15_000L,
    /** Короче этого окно не даёт максимальной скорости вовсе — мерить ещё нечем. */
    val minimumSpeedWindowMillis: Long = 10_000L,
    /**
     * Среднеквадратичное ускорение, которое модель движения считает обычным.
     *
     * Это единственный настоящий параметр фильтра Калмана и вся его «настройка»: он
     * говорит, насколько человеку позволено отклоняться от постоянной скорости. Слишком
     * малое значение делает трек упрямым — фильтр срезает повороты, потому что не верит,
     * что вы могли так резко свернуть; слишком большое возвращает доверие каждому шумному
     * фиксу, и дрожание проходит насквозь.
     *
     * Это единственное ускорение в конфигурации. Прежний предел
     * `maximumAccelerationMetersPerSecondSquared` убран вместе с проверкой, которая его
     * читала: она сравнивала с порогом производную от разницы шумных координат — величину,
     * которая на секундном шаге состоит из одного шума (см. [OutlierFilterUseCase]).
     */
    val processNoiseAccelerationMetersPerSecondSquared: Double,
    /**
     * Порог гейта невязки — квадрат махаланобисова расстояния при двух степенях свободы.
     *
     * 13.8 — это квантиль 99.9%: измерение отбрасывается, если при верной модели такое
     * расхождение случалось бы реже одного раза на тысячу. Порог намеренно консервативен.
     * Ошибки здесь несимметричны: пропущенный выброс фильтр переварит и следующей же
     * точкой поправится, а ложный отказ выбрасывает настоящее измерение и тем самым
     * подталкивает фильтр к расхождению.
     */
    val outlierGateChiSquare: Double = 13.816,
    /**
     * Сколько отказов подряд считать выбросами, прежде чем признать, что разошёлся сам
     * фильтр. Приёмник действительно способен переехать разом — на выходе из здания или
     * из тоннеля, — и тогда браковать он будет уже всё подряд.
     */
    val maximumConsecutiveGateRejections: Int = 3,
    /**
     * Нижняя граница погрешности фикса. Приёмник регулярно её занижает: заявленный метр
     * в городе говорит о хорошей геометрии спутников, а не об отсутствии переотражений.
     */
    val positionNoiseFloorMeters: Double = 3.0,
    /**
     * Чем считать погрешность доплеровской скорости, когда провайдер её не сообщил.
     *
     * Намеренно большая. Приёмник, который сообщает скорость вместе с её точностью, обычно
     * считает её по сдвигу несущей и знает с точностью до десятых. Тот, который точность не
     * сообщает, зачастую скорость не меряет, а синтезирует, — и на слабом сигнале
     * (и на эмуляторе) уверенно отдаёт ноль, пока приёмник движется. Двух метров в секунду
     * хватает, чтобы такой «ноль» не перевесил координаты, но и чтобы он не пропал даром:
     * настоящая стоянка подтверждается им же, просто медленнее.
     */
    val defaultSpeedAccuracyMetersPerSecond: Double = 2.0,
    /** То же для курса; сорок пять градусов — это «направление скорее известно, чем нет». */
    val defaultBearingAccuracyDegrees: Double = 45.0,
    val stopWindowMillis: Long = 15_000L,
    val minimumStopDurationMillis: Long = 10_000L,
    val stopRadiusMeters: Double = 12.0,
    val stopSpeedMetersPerSecond: Double = 0.8,
    val resumeSpeedMetersPerSecond: Double = 1.2,
    val elevationNoiseMeters: Double = 3.0,
) {
    init {
        require(maximumSpeedMetersPerSecond > 0.0)
        require(maximumAccuracyMeters > 0.0)
        require(outlierAccuracySigmas >= 0.0)
        require(noiseDeadbandFactor >= 0.0)
        require(maximumSegmentDurationMillis > 0L)
        require(minimumSpeedWindowMillis in 1L..maximumSpeedWindowMillis)
        require(processNoiseAccelerationMetersPerSecondSquared > 0.0)
        require(outlierGateChiSquare > 0.0)
        require(maximumConsecutiveGateRejections > 0)
        require(positionNoiseFloorMeters >= 0.0)
        require(defaultSpeedAccuracyMetersPerSecond > 0.0)
        require(defaultBearingAccuracyDegrees > 0.0)
        require(stopWindowMillis >= minimumStopDurationMillis)
        require(minimumStopDurationMillis > 0L)
        require(stopRadiusMeters >= 0.0)
        require(stopSpeedMetersPerSecond >= 0.0)
        require(resumeSpeedMetersPerSecond >= stopSpeedMetersPerSecond)
        require(elevationNoiseMeters >= 0.0)
    }

    companion object {
        fun forActivityType(activityType: ActivityType): TrackProcessingConfig = when (activityType) {
            // Порог остановки для пешехода долго стоял на 0.25 м/с — это 0.9 км/ч,
            // ниже шумового пола GPS на любой реальной погрешности, и автопауза
            // не срабатывала вовсе.
            ActivityType.WALKING, ActivityType.HIKING -> TrackProcessingConfig(
                maximumSpeedMetersPerSecond = 5.5,
                maximumAccuracyMeters = 40.0,
                processNoiseAccelerationMetersPerSecondSquared = 0.25,
                stopSpeedMetersPerSecond = 0.5,
                resumeSpeedMetersPerSecond = 0.9,
            )
            ActivityType.RUNNING -> TrackProcessingConfig(
                maximumSpeedMetersPerSecond = 10.0,
                maximumAccuracyMeters = 40.0,
                processNoiseAccelerationMetersPerSecondSquared = 0.7,
                stopSpeedMetersPerSecond = 0.45,
                resumeSpeedMetersPerSecond = 1.0,
            )
            ActivityType.CYCLING -> TrackProcessingConfig(
                maximumSpeedMetersPerSecond = 25.0,
                maximumAccuracyMeters = 50.0,
                processNoiseAccelerationMetersPerSecondSquared = 0.9,
                stopSpeedMetersPerSecond = 0.6,
                resumeSpeedMetersPerSecond = 1.5,
            )
            // В машине сигнал хуже (крыша, тоннели), а цена ошибки ниже: сотня метров
            // погрешности на скорости 60 км/ч — это меньше секунды пути.
            ActivityType.DRIVING -> TrackProcessingConfig(
                maximumSpeedMetersPerSecond = 70.0,
                maximumAccuracyMeters = 100.0,
                processNoiseAccelerationMetersPerSecondSquared = 1.5,
                stopSpeedMetersPerSecond = 0.8,
                resumeSpeedMetersPerSecond = 2.0,
            )
            ActivityType.OTHER -> TrackProcessingConfig(
                maximumSpeedMetersPerSecond = 55.0,
                maximumAccuracyMeters = 75.0,
                processNoiseAccelerationMetersPerSecondSquared = 1.0,
                stopSpeedMetersPerSecond = 0.45,
                resumeSpeedMetersPerSecond = 1.0,
            )
        }
    }
}

fun TrackProcessingConfig.withAutoPauseSensitivity(
    sensitivity: AutoPauseSensitivity,
): TrackProcessingConfig = when (sensitivity) {
    AutoPauseSensitivity.LOW -> copy(
        stopWindowMillis = 25_000L,
        minimumStopDurationMillis = 20_000L,
        stopRadiusMeters = 8.0,
        stopSpeedMetersPerSecond = stopSpeedMetersPerSecond * 0.7,
        resumeSpeedMetersPerSecond = resumeSpeedMetersPerSecond * 1.2,
    )
    AutoPauseSensitivity.MEDIUM -> this
    AutoPauseSensitivity.HIGH -> copy(
        stopWindowMillis = 10_000L,
        minimumStopDurationMillis = 5_000L,
        stopRadiusMeters = 16.0,
        stopSpeedMetersPerSecond = stopSpeedMetersPerSecond * 1.3,
        resumeSpeedMetersPerSecond = max(
            stopSpeedMetersPerSecond * 1.3,
            resumeSpeedMetersPerSecond * 0.85,
        ),
    )
}
