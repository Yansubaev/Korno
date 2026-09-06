package com.ians.korno.core.trackanalytics

/**
 * Точка трека, пересчитанная в величины, по которым строятся графики и линзы карты.
 *
 * Индекс точки в [TrackAnalysis.samples] — единственный идентификатор места на маршруте:
 * карта, график темпа и профиль высоты ссылаются на одну и ту же выборку, и синхронизация
 * выделения сводится к передаче индекса.
 */
data class TrackSample(
    val latitude: Double,
    val longitude: Double,
    /** Смещение от начала активности. */
    val timeOffsetMillis: Long,
    /** Дистанция от старта. На ручной паузе и на стоянке не растёт. */
    val distanceMeters: Double,
    /** Сглаженная высота; `null` — высота на этом участке не записана. */
    val altitudeMeters: Double?,
    /**
     * Скорость, сглаженная по временному окну. `null` — ручная пауза или слишком мало
     * данных вокруг точки: сырая мгновенная скорость GPS для показа непригодна.
     */
    val speedMetersPerSecond: Double?,
    /** Точка получена во время ручной паузы. */
    val isPaused: Boolean,
    /** Точка попала в автоматически определённую стоянку. */
    val isStop: Boolean,
)

/** Отрезок маршрута длиной в километр или милю; последний может быть неполным. */
data class TrackSplit(
    /** Номер сплита, начиная с 1. */
    val number: Int,
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
    /** Время в движении: стоянки и паузы исключены. */
    val movingMillis: Long,
    /** Время от входа в сплит до выхода, включая стоянки, но без ручных пауз. */
    val elapsedMillis: Long,
    /** Средняя скорость в движении. */
    val speedMetersPerSecond: Double,
    /** Изменение высоты между границами сплита; `null` — высоты нет. */
    val elevationDeltaMeters: Double?,
    /** Сплит короче полного километра или мили — обычно последний. */
    val isPartial: Boolean,
) {
    val distanceMeters: Double get() = endDistanceMeters - startDistanceMeters
}

/** Одна остановка: автоматическая стоянка или ручная пауза. */
data class StopSegment(
    val latitude: Double,
    val longitude: Double,
    val startTimeOffsetMillis: Long,
    val durationMillis: Long,
    val distanceFromStartMeters: Double,
    val isManualPause: Boolean,
)

/**
 * Как активность делилась на движение и простой.
 *
 * Автоматические стоянки и ручные паузы разведены намеренно: первое — свойство маршрута
 * (светофор, пробка, отдых), второе — решение пользователя, и смешивать их в одном числе
 * значило бы врать про обе величины.
 */
data class StopAnalysis(
    val movingMillis: Long,
    val autoStopMillis: Long,
    val manualPauseMillis: Long,
    val segments: List<StopSegment>,
) {
    val stoppedMillis: Long get() = autoStopMillis + manualPauseMillis
    val totalMillis: Long get() = movingMillis + stoppedMillis
    val count: Int get() = segments.size
    val longestMillis: Long get() = segments.maxOfOrNull(StopSegment::durationMillis) ?: 0L
}

/** Профиль высоты, посчитанный по сглаженным значениям. */
data class ElevationAnalysis(
    val minimumMeters: Double,
    val maximumMeters: Double,
    val gainMeters: Double,
    val lossMeters: Double,
    /** Индексы точек в [TrackAnalysis.samples] — чтобы отметить их на карте. */
    val lowestSampleIndex: Int,
    val highestSampleIndex: Int,
)

/**
 * Границы цветовой шкалы линзы «Темп/скорость».
 *
 * Это 5-й и 95-й процентили, а не минимум с максимумом: один GPS-скачок растянул бы
 * шкалу так, что весь остальной маршрут стал бы одного цвета.
 */
data class SpeedScale(
    val slowestMetersPerSecond: Double,
    val fastestMetersPerSecond: Double,
) {
    /** Доля значения в шкале, 0..1. */
    fun fractionOf(metersPerSecond: Double): Float {
        val span = fastestMetersPerSecond - slowestMetersPerSecond
        if (span <= 0.0) return 0.5f
        return ((metersPerSecond - slowestMetersPerSecond) / span).coerceIn(0.0, 1.0).toFloat()
    }
}

/** Границы цветовой шкалы линзы «Высота». */
data class ElevationScale(
    val lowestMeters: Double,
    val highestMeters: Double,
) {
    fun fractionOf(meters: Double): Float {
        val span = highestMeters - lowestMeters
        if (span <= 0.0) return 0.5f
        return ((meters - lowestMeters) / span).coerceIn(0.0, 1.0).toFloat()
    }
}

/** Всё, что экран активности умеет посчитать по уже записанному треку. */
data class TrackAnalysis(
    val samples: List<TrackSample>,
    val totalDistanceMeters: Double,
    val splits: List<TrackSplit>,
    /** Самый быстрый полный сплит; `null` — полных сплитов нет. */
    val fastestSplit: TrackSplit?,
    val stops: StopAnalysis,
    /** `null` — высота в треке не записана. */
    val elevation: ElevationAnalysis?,
    /** `null` — движения в треке нет, шкалу строить не из чего. */
    val speedScale: SpeedScale?,
    /** `null` — высоты нет. */
    val elevationScale: ElevationScale?,
) {
    val isEmpty: Boolean get() = samples.isEmpty()

    companion object {
        val Empty = TrackAnalysis(
            samples = emptyList(),
            totalDistanceMeters = 0.0,
            splits = emptyList(),
            fastestSplit = null,
            stops = StopAnalysis(
                movingMillis = 0L,
                autoStopMillis = 0L,
                manualPauseMillis = 0L,
                segments = emptyList(),
            ),
            elevation = null,
            speedScale = null,
            elevationScale = null,
        )
    }
}

/**
 * Параметры сглаживания и отбора. Значения по умолчанию рассчитаны на бытовую
 * запись раз в секунду-две; менять их имеет смысл вместе с частотой опроса GPS.
 */
data class TrackAnalysisConfig(
    /** Окно усреднения скорости. Короче — виден шум, длиннее — пропадают короткие ускорения. */
    val speedWindowMillis: Long = 15_000L,
    /** Окно усреднения высоты по дистанции. */
    val elevationWindowMeters: Double = 60.0,
    /** Изменение высоты меньше этого не считается набором или сбросом. */
    val elevationNoiseMeters: Double = 3.0,
    /** Остановки короче не показываются: это дрожание GPS, а не остановка. */
    val minimumStopMillis: Long = 5_000L,
    /** Нижний процентиль цветовой шкалы скорости. */
    val speedScaleLowPercentile: Double = 0.05,
    /** Верхний процентиль цветовой шкалы скорости. */
    val speedScaleHighPercentile: Double = 0.95,
) {
    init {
        require(speedWindowMillis > 0L)
        require(elevationWindowMeters > 0.0)
        require(elevationNoiseMeters >= 0.0)
        require(minimumStopMillis >= 0L)
        require(speedScaleLowPercentile in 0.0..1.0)
        require(speedScaleHighPercentile in speedScaleLowPercentile..1.0)
    }
}
