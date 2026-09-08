package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint

/**
 * Разметка стоянок и, через неё, решение об автопаузе.
 *
 * Вход в стоянку и выход из неё намеренно устроены по-разному.
 *
 * Объявить стоянку можно только по окну длиной не меньше
 * [TrackProcessingConfig.minimumStopDurationMillis]: одна точка ничего не доказывает, а
 * ложная автопауза стирает реальный путь. Снимать же стоянку по окну нельзя: на автопаузе
 * частота опроса GPS снижается, и окно нередко схлопывается до единственной точки —
 * сравнивать становится не с чем, и запись залипает в автопаузе до ручного вмешательства.
 * Поэтому выход считается по последнему доступному отрезку, каким бы он ни был.
 *
 * ## Откуда берётся скорость
 *
 * Вход в стоянку решается по смещению за окно — но по смещению **оценок фильтра**, а не
 * сырых фиксов. Разница принципиальная: сырые координаты при погрешности 20 м и шаге 5 с
 * состоят из шума на две трети, и вся прежняя конструкция с медианами доплера, допусками
 * по погрешности и вычитанием этих допусков была попыткой этот шум обойти вручную. По
 * сглаженным координатам он мал сам по себе, и обходить нечего.
 *
 * Мгновенная оценка скорости из фильтра ([EstimatedSpeed]) для входа намеренно не берётся,
 * хотя и доступна. Она сглажена, а значит отстаёт: на прогулке при погрешности 12 м и шаге
 * 4 с она подбирается к истинным 1.15 м/с почти минуту и всё это время читается как покой.
 * Смещение за окно такого недостатка не имеет — это несмещённая оценка средней скорости на
 * окне, ровно то, о чём и спрашивает детектор. А вот для выхода из стоянки она полезна:
 * там нужна отзывчивость, и отставание компенсируется двумя другими признаками.
 *
 * ## Чего здесь больше нет
 *
 * Расчёта необходимой длины окна — того, за сколько времени пешеход со скоростью порога
 * успел бы выйти за круг неопределённости. Идея была верной, а арифметика не сходилась:
 * при погрешности 15 м и пороге 0.5 м/с требовалось 30 секунд наблюдений, тогда как само
 * окно ограничено [TrackProcessingConfig.stopWindowMillis] и до 30 секунд не дорастает
 * никогда. Условие оказывалось невыполнимым, и на треке без доплеровской скорости стоянка
 * не объявлялась вовсе — ни одной за всю запись. Спасал этот случай только доплер, когда
 * он был. По сглаженным координатам нужды в этом расчёте нет.
 */
class StopDetectionUseCase {
    operator fun invoke(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
        speeds: List<EstimatedSpeed?> = emptyList(),
    ): List<GeoPoint> {
        val result = points.map { it.copy(isStop = false) }.toMutableList()
        val validIndices = result.indices.filterNot { result[it].isOutlier }
        var windowStartPosition = 0
        var isStopped = false
        // Точка, с которой началась текущая стоянка: уход от неё — самое устойчивое
        // к шуму доказательство возобновления движения.
        var stopAnchorIndex: Int? = null

        validIndices.forEachIndexed { endPosition, endIndex ->
            val end = result[endIndex]
            while (
                windowStartPosition < endPosition &&
                end.timestampEpochMillis - result[validIndices[windowStartPosition]].timestampEpochMillis >
                config.stopWindowMillis
            ) {
                windowStartPosition++
            }

            val speed = speeds.getOrNull(endIndex)

            if (isStopped) {
                val anchor = stopAnchorIndex?.let(result::get)
                // Допуск берётся по последнему отрезку: он и решает, ушла ли точка из круга.
                val resumeAllowance = accuracyAllowance(
                    result = result,
                    indices = validIndices.subList(
                        (endPosition - 1).coerceAtLeast(windowStartPosition),
                        endPosition + 1,
                    ),
                    config = config,
                )
                if (hasResumeEvidence(end, anchor, speed, resumeAllowance, config)) {
                    isStopped = false
                    stopAnchorIndex = null
                    // Окно начинается заново с точки возобновления. Иначе оставшиеся в нём
                    // точки стоянки на следующем же сэмпле снова дали бы доказательство
                    // покоя, и статус дёргался бы туда-обратно в пределах пары сотен
                    // миллисекунд.
                    windowStartPosition = endPosition
                } else {
                    result[endIndex] = end.copy(isStop = true)
                }
                return@forEachIndexed
            }

            // Окно расширяется на одну точку назад, если схлопнулось: длина окна в
            // миллисекундах — способ набрать наблюдений, а не самоцель, и при разрежённом
            // опросе двух точек достаточно.
            val firstPosition = if (windowStartPosition < endPosition) {
                windowStartPosition
            } else {
                (endPosition - 1).coerceAtLeast(0)
            }
            val windowIndices = validIndices.subList(firstPosition, endPosition + 1)
            val firstIndex = windowIndices.firstOrNull() ?: return@forEachIndexed
            val durationMillis = end.timestampEpochMillis - result[firstIndex].timestampEpochMillis
            // Слишком старая опорная точка ничего не доказывает: за полчаса можно уйти
            // и вернуться, и трек снаружи стоянки будет неотличим от покоя.
            if (durationMillis > config.maximumStopEvidenceGapMillis) return@forEachIndexed
            if (durationMillis < config.minimumStopDurationMillis) return@forEachIndexed

            val windowSpeed = distanceMeters(result[firstIndex], end) / (durationMillis / 1_000.0)
            if (windowSpeed > config.stopSpeedMetersPerSecond) return@forEachIndexed

            // Смещения от начала окна мало: по нему круг, пройденный за окно, неотличим от
            // покоя. Максимальный уход за окно закрывает и этот случай.
            val accuracyAllowance = accuracyAllowance(result, windowIndices, config)
            val maximumDisplacement = windowIndices.maxOf { index ->
                distanceMeters(result[firstIndex], result[index])
            }
            if (maximumDisplacement > config.stopRadiusMeters + accuracyAllowance) return@forEachIndexed

            isStopped = true
            stopAnchorIndex = firstIndex
            windowIndices.forEach { index -> result[index] = result[index].copy(isStop = true) }
        }

        return result
    }

    /**
     * Ошибки здесь несимметричны: ложный выход из паузы добавляет к треку немного дрожания,
     * тогда как ложный вход стирает реальный путь. Поэтому доказательств возобновления
     * два, и достаточно любого.
     */
    private fun hasResumeEvidence(
        end: GeoPoint,
        stopAnchor: GeoPoint?,
        speed: EstimatedSpeed?,
        accuracyAllowance: Double,
        config: TrackProcessingConfig,
    ): Boolean {
        // Главный признак: человек ушёл из круга, в котором была объявлена стоянка. Он тем
        // надёжнее, чем дольше длится пауза, и не зависит от того, сошёлся ли фильтр.
        // Пороговой скорости для этого мало: на разрежённом сигнале уходящий пешеход её
        // просто не набирает, и запись залипает в автопаузе до ручного вмешательства.
        if (stopAnchor != null &&
            distanceMeters(stopAnchor, end) > config.stopRadiusMeters + accuracyAllowance
        ) {
            return true
        }

        // Второй признак — сама оценка скорости. Запас от порога остановки до порога
        // возобновления и есть защита от дёрганья: между ними вердикт не меняется.
        if (speed != null && speed.metersPerSecond >= config.resumeSpeedMetersPerSecond) return true

        // Третий — модуль доплеровской скорости напрямую, в обход фильтра. Приёмник меряет
        // его по сдвигу несущей, и для «поехали» этого достаточно самого по себе, даже
        // когда положение ещё не сдвинулось и фильтр про движение не знает. Одиночный
        // выброс доплера тут не страшен: ложный выход из паузы стоит немного дрожания,
        // тогда как ложный вход стирает реальный путь.
        val reported = end.speedMetersPerSecond?.toDouble()?.takeIf { it.isFinite() }
        return reported != null && reported >= config.resumeSpeedMetersPerSecond
    }

    /**
     * Допуск ограничен той же погрешностью, выше которой точка уже считается выбросом:
     * всё, что дошло сюда, укладывается в [TrackProcessingConfig.maximumAccuracyMeters].
     */
    private fun accuracyAllowance(
        result: List<GeoPoint>,
        indices: List<Int>,
        config: TrackProcessingConfig,
    ): Double = indices
        .mapNotNull { result[it].accuracyMeters?.toDouble() }
        .maxOrNull()
        ?.coerceAtMost(config.maximumAccuracyMeters)
        ?: 0.0
}
