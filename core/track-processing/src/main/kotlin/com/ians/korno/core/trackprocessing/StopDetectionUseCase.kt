package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint

/**
 * Во сколько раз схлопнутое окно вправе дотянуться назад дальше собственной длины.
 *
 * Раньше здесь стоял отдельный порог в пять минут, и он ничего не ограничивал: детектор
 * объявлял стоянку по двум фиксам, разнесённым на 34 секунды при окне в 25, — а между
 * ними человек прошёл полсотни метров, просто все точки на этом отрезке отбраковались по
 * погрешности. Прямая между двумя далёкими фиксами не отличает покой от петли, и чем
 * длиннее промежуток, тем меньше она значит.
 */
private const val COLLAPSED_WINDOW_FACTOR = 2

/**
 * Насколько дальше порога должна уйти точка, чтобы снять стоянку по смещению.
 *
 * Между порогами скорости гистерезис есть — от [TrackProcessingConfig.stopSpeedMetersPerSecond]
 * до [TrackProcessingConfig.resumeSpeedMetersPerSecond], — а у пути через смещение его не
 * было вовсе. При погрешности за десяток метров порог гуляет вместе с ней, и на настоящей
 * стоянке его пробивает обычный снос: запись выходила из паузы и через три секунды
 * возвращалась в неё, разрезая одну остановку на две.
 */
private const val RESUME_DISPLACEMENT_FACTOR = 1.5

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
 * Зато сырая доплеровская скорость участвует и во входе — но не как основание, а как
 * запрет. Отставания у неё нет: приёмник меряет модуль по сдвигу несущей, а не по разнице
 * координат. У смещения за окно есть слепое пятно, которого у доплера нет: ходьба по дуге
 * даёт короткую прямую между концами окна и читается как покой. Именно так на прогулке
 * включалась автопауза посреди движения — приёмник всё это время показывал 0.9–1.4 м/с
 * при пороге 0.5.
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

            // Окно расширяется на одну точку назад, если короче минимальной стоянки: длина
            // окна в миллисекундах — способ набрать наблюдений, а не самоцель, и при
            // разрежённом опросе двух точек достаточно. Раньше расширялось только
            // схлопнутое до одной точки окно, и при шаге между половиной окна и минимальной
            // стоянкой (7.8 с при 15 и 10) в окно влезали две точки, которых всегда было
            // мало: неподвижный телефон не уходил в автопаузу никогда.
            val windowTooShort = end.timestampEpochMillis -
                result[validIndices[windowStartPosition]].timestampEpochMillis <
                config.minimumStopDurationMillis
            val firstPosition = if (windowTooShort) {
                (windowStartPosition - 1).coerceAtLeast(0)
            } else {
                windowStartPosition
            }
            val windowIndices = validIndices.subList(firstPosition, endPosition + 1)
            val firstIndex = windowIndices.firstOrNull() ?: return@forEachIndexed
            val durationMillis = end.timestampEpochMillis - result[firstIndex].timestampEpochMillis
            // Схлопнутое окно вправе дотянуться назад чуть дальше своей длины, но не
            // насколько угодно: чем длиннее промежуток, тем меньше значит прямая через него.
            if (durationMillis > config.stopWindowMillis * COLLAPSED_WINDOW_FACTOR) return@forEachIndexed
            if (durationMillis < config.minimumStopDurationMillis) return@forEachIndexed

            val windowSpeed = distanceMeters(result[firstIndex], end) / (durationMillis / 1_000.0)
            if (windowSpeed > config.stopSpeedMetersPerSecond) return@forEachIndexed
            // Смещение за окно — не единственное, что известно о движении. Приёмник меряет
            // модуль скорости по сдвигу несущей, и это прямое измерение: оно не отстаёт,
            // как оценка фильтра, и не зависит от того, шли вы по прямой или по дуге.
            // Пока доплер уверенно показывает ходьбу, стоянки нет, каким бы малым ни
            // вышло смещение.
            if (reportedSpeedExceeds(result, windowIndices, config)) return@forEachIndexed

            // Смещения от начала окна мало: по нему круг, пройденный за окно, неотличим от
            // покоя. Максимальный уход за окно закрывает и этот случай.
            val accuracyAllowance = accuracyAllowance(result, windowIndices, config)
            val maximumDisplacement = windowIndices.maxOf { index ->
                distanceMeters(result[firstIndex], result[index])
            }
            if (maximumDisplacement > config.stopRadiusMeters + accuracyAllowance) return@forEachIndexed

            isStopped = true
            // Решение принято по всему окну, а помечается не всё окно.
            //
            // Его начало отстоит от решения на длину окна, и на подходе к остановке там
            // лежит обычная ходьба. Помеченная стоянкой, она пропадала из трека дважды:
            // время уходило из движения, а дистанция — вместе с переносом опорной точки.
            // Перед настоящей остановкой так стиралось до девятнадцати секунд пути.
            //
            // Обрезается ровно то, про что доплер прямо говорит «идёт»: точка без
            // скорости остаётся в стоянке, потому что отсутствие измерения ничего не
            // доказывает. Границы решения это не трогает — окно для всех проверок
            // остаётся прежним, иначе укороченному окну стало бы легче пройти порог по
            // уходу, и стоянки посыпались бы там, где их нет.
            val stopIndices = windowIndices.dropWhile { result[it].reportedMotion(config) }
            stopAnchorIndex = stopIndices.firstOrNull() ?: firstIndex
            stopIndices.forEach { index -> result[index] = result[index].copy(isStop = true) }
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
            distanceMeters(stopAnchor, end) >
            (config.stopRadiusMeters + accuracyAllowance) * RESUME_DISPLACEMENT_FACTOR
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
     * Уверенно ли доплер показывает движение на всём окне.
     *
     * Берётся медиана, а не среднее и не максимум: одиночные выбросы в 3–4 м/с на стоящем
     * месте провайдер выдаёт регулярно, и по ним стоянку не отменить. Точки, для которых
     * скорость не пришла вовсе, в счёт не идут — их отсутствие ничего не доказывает.
     *
     * Сравнивается с порогом не сама скорость, а её нижняя граница
     * ([reportedSpeedLowerBound]): измерение 1.39 ± 1.5 м/с не вправе отменять стоянку,
     * а измерение 1.35 ± 0.36 — вправе.
     */
    private fun reportedSpeedExceeds(
        result: List<GeoPoint>,
        indices: List<Int>,
        config: TrackProcessingConfig,
    ): Boolean {
        val bounds = indices.mapNotNull { result[it].reportedSpeedLowerBound(config) }.sorted()
        if (bounds.isEmpty()) return false
        return bounds[bounds.size / 2] > config.stopSpeedMetersPerSecond
    }

    /**
     * Показывает ли доплер движение в этой одной точке.
     *
     * Точка без скорости движущейся не считается: отсутствие измерения ничего не
     * доказывает, и обрезать по нему начало окна значило бы выбрасывать доказательства
     * покоя всякий раз, когда провайдер скорость не отдал.
     */
    private fun GeoPoint.reportedMotion(config: TrackProcessingConfig): Boolean {
        val lowerBound = reportedSpeedLowerBound(config) ?: return false
        return lowerBound > config.stopSpeedMetersPerSecond
    }

    /**
     * Доплеровская скорость за вычетом её собственной погрешности; `null` — скорости нет.
     *
     * Вычитание обязательно: на плохом сигнале приёмник ошибается в обе стороны. На
     * разобранной записи он показывал 1.39 м/с на человеке, который стоял, — но сообщал
     * при этом погрешность в 1.5 м/с, то есть честно признавался, что не знает.
     */
    private fun GeoPoint.reportedSpeedLowerBound(config: TrackProcessingConfig): Double? {
        val speed = speedMetersPerSecond?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val sigma = speedAccuracyMetersPerSecond?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
            ?: config.defaultSpeedAccuracyMetersPerSecond
        return speed - sigma
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
