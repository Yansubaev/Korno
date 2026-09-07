package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import kotlin.math.max

/**
 * Вход в стоянку и выход из неё намеренно устроены по-разному.
 *
 * Объявить стоянку можно только по окну длиной [TrackProcessingConfig.stopWindowMillis]:
 * одна точка ничего не доказывает, а ложная автопауза рвёт трек. Снимать же стоянку по
 * окну нельзя: на автопаузе частота опроса GPS снижается, и окно нередко схлопывается до
 * единственной точки — сравнивать становится не с чем, и запись залипает в автопаузе до
 * ручного вмешательства. Поэтому выход считается по последнему доступному отрезку, каким
 * бы он ни был, — см. [resumePositions].
 *
 * При этом само окно тоже умеет схлопываться — и не только на автопаузе: в Doze или при
 * потере GNSS шаг опроса вырастает до десятков секунд и на обычной записи. Раньше в этом
 * случае длина окна оказывалась нулевой, проверка не доходила даже до вычисления признаков,
 * и стоянка не объявлялась никогда. Теперь окно так же расширяется назад — см.
 * [evidencePositions]: три фикса с интервалом полминуты в одной координате доказывают
 * покой не хуже трёх фиксов с интервалом пять секунд.
 */
class StopDetectionUseCase {
    operator fun invoke(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
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

            if (isStopped) {
                val resumeIndices = evidencePositions(windowStartPosition, endPosition)
                    .map { position -> validIndices[position] }
                val anchor = stopAnchorIndex?.let(result::get)
                if (hasResumeEvidence(result, resumeIndices, anchor, config)) {
                    isStopped = false
                    stopAnchorIndex = null
                    // Окно начинается заново с точки возобновления. Иначе оставшиеся в нём
                    // точки стоянки на следующем же сэмпле снова дали бы hasStopEvidence,
                    // и статус дёргался бы туда-обратно в пределах пары сотен миллисекунд.
                    windowStartPosition = endPosition
                } else {
                    result[endIndex] = end.copy(isStop = true)
                }
                return@forEachIndexed
            }

            val windowPositions = evidencePositions(windowStartPosition, endPosition)
            val windowIndices = validIndices.subList(windowPositions.first, windowPositions.last + 1)
            val firstIndex = windowIndices.firstOrNull() ?: return@forEachIndexed
            val durationMillis = end.timestampEpochMillis - result[firstIndex].timestampEpochMillis
            // Слишком старая опорная точка ничего не доказывает: за полчаса можно уйти
            // и вернуться, и трек снаружи стоянки будет неотличим от покоя.
            if (durationMillis > config.maximumStopEvidenceGapMillis) return@forEachIndexed

            val accuracyAllowance = accuracyAllowance(result, windowIndices, config)
            val reportedSpeeds = reportedSpeeds(result, windowIndices)
            // Доплеровская скорость — независимое от координат доказательство покоя.
            // Когда она есть и мала, длинное окно не нужно.
            val hasReportedStopEvidence = reportedSpeeds.isNotEmpty() &&
                reportedSpeeds.medianOrZero() <= config.stopSpeedMetersPerSecond
            val requiredDuration = requiredStopDurationMillis(
                accuracyAllowance = accuracyAllowance,
                hasReportedStopEvidence = hasReportedStopEvidence,
                config = config,
            )
            if (durationMillis < requiredDuration) return@forEachIndexed

            val maximumDisplacement = windowIndices.maxOf { index ->
                distanceMeters(result[firstIndex], result[index])
            }
            // Скорость считается по сырому смещению, без вычитания погрешности.
            // Вычитание казалось защитой от шума, а на деле было систематическим
            // занижением: у пешехода за тринадцать секунд набирается 13 м, минус пять
            // метров допуска — и остаётся 0.64 м/с при пороге 0.5. Запаса нет, дрожание
            // регулярно опрокидывает вердикт в стоянку, а при погрешности в двадцать
            // метров от прогулки не остаётся вообще ничего. От шума защищает не
            // вычитание, а длина окна — см. [requiredStopDurationMillis].
            val movementSpeed = max(
                reportedSpeeds.medianOrZero(),
                positionalSpeed(result[firstIndex], end),
            )
            val hasStopEvidence =
                movementSpeed <= config.stopSpeedMetersPerSecond &&
                maximumDisplacement <= config.stopRadiusMeters + accuracyAllowance
            if (hasStopEvidence) {
                isStopped = true
                stopAnchorIndex = firstIndex
                windowIndices.forEach { index -> result[index] = result[index].copy(isStop = true) }
            }
        }

        return result
    }

    /**
     * Позиции точек, на которых строится вердикт — и о входе в стоянку, и о выходе из неё.
     *
     * Схлопнувшееся окно расширяется на одну точку назад, какой бы давней она ни была:
     * длина окна в миллисекундах — это способ набрать достаточно наблюдений, а не
     * самоцель, и при разрежённом опросе двух точек хватает. Для выхода это спасает от
     * автопаузы, из которой нет выхода; для входа — от стоянки, которая не объявляется.
     * Насколько давней может быть опорная точка при входе, ограничивает
     * [TrackProcessingConfig.maximumStopEvidenceGapMillis].
     */
    private fun evidencePositions(windowStartPosition: Int, endPosition: Int): IntRange {
        val startPosition = if (windowStartPosition < endPosition) windowStartPosition else endPosition - 1
        return startPosition.coerceAtLeast(0)..endPosition
    }

    /**
     * Сколько времени должно пройти, чтобы покой можно было отличить от дрожания.
     *
     * Это и есть настоящая защита от шума — вместо вычитания погрешности из смещения.
     * Шум за окно не растёт, а путь идущего растёт линейно, поэтому достаточно потребовать
     * окно, за которое [TrackProcessingConfig.stopSpeedMetersPerSecond] вывела бы за
     * пределы круга неопределённости. При погрешности 5 м это десять секунд, при 22 м —
     * три четверти минуты, и в обоих случаях прогулку от стоянки уже видно.
     *
     * Если провайдер отдаёт скорость и она мала, длинное окно не нужно: это независимое
     * от координат доказательство, и дрожание на него не влияет.
     */
    private fun requiredStopDurationMillis(
        accuracyAllowance: Double,
        hasReportedStopEvidence: Boolean,
        config: TrackProcessingConfig,
    ): Long {
        if (hasReportedStopEvidence || config.stopSpeedMetersPerSecond <= 0.0) {
            return config.minimumStopDurationMillis
        }
        val escapeMillis = (accuracyAllowance / config.stopSpeedMetersPerSecond * 1_000.0).toLong()
        return max(config.minimumStopDurationMillis, escapeMillis)
    }

    /**
     * @param stopAnchor точка, с которой началась стоянка.
     */
    private fun hasResumeEvidence(
        result: List<GeoPoint>,
        indices: List<Int>,
        stopAnchor: GeoPoint?,
        config: TrackProcessingConfig,
    ): Boolean {
        if (indices.isEmpty()) return false
        val last = result[indices.last()]
        val accuracyAllowance = accuracyAllowance(result, indices, config)

        // Главный признак: человек ушёл из круга, в котором была объявлена стоянка.
        // Он тем надёжнее, чем дольше длится пауза, и не зависит от того, отдаёт ли
        // провайдер скорость. Пороговой скорости для этого мало: на разрежённом или
        // шумном сигнале уходящий пешеход её просто не набирает, и запись залипает
        // в автопаузе до ручного вмешательства.
        if (stopAnchor != null &&
            distanceMeters(stopAnchor, last) > config.stopRadiusMeters + accuracyAllowance
        ) {
            return true
        }

        if (indices.size < 2) return false
        val reportedSpeeds = reportedSpeeds(result, indices)
        // Doppler-скорость привязана к одной точке и не размывается длиной отрезка,
        // поэтому её достаточно самой по себе.
        if (reportedSpeeds.any { it >= config.resumeSpeedMetersPerSecond }) return true
        // А вот позиционная скорость здесь считается с вычетом погрешности — в отличие
        // от входа в стоянку. Ошибки тут несимметричны: ложный выход из паузы просто
        // добавляет к треку немного дрожания, тогда как ложный вход стирает реальный путь.
        val positional = positionalSpeed(
            first = result[indices.first()],
            last = last,
            accuracyAllowance = accuracyAllowance,
        )
        return max(reportedSpeeds.medianOrZero(), positional) >= config.resumeSpeedMetersPerSecond
    }

    /** Скорость по смещению между двумя точками. */
    private fun positionalSpeed(
        first: GeoPoint,
        last: GeoPoint,
        accuracyAllowance: Double = 0.0,
    ): Double {
        val durationSeconds = (last.timestampEpochMillis - first.timestampEpochMillis) / 1_000.0
        if (durationSeconds <= 0.0) return 0.0
        return (distanceMeters(first, last) - accuracyAllowance).coerceAtLeast(0.0) / durationSeconds
    }

    private fun reportedSpeeds(result: List<GeoPoint>, indices: List<Int>): List<Double> =
        indices.mapNotNull { index ->
            result[index].speedMetersPerSecond
                ?.toDouble()
                ?.takeIf { it.isFinite() && it >= 0.0 }
        }

    /**
     * Допуск ограничен той же погрешностью, выше которой точка уже считается выбросом:
     * всё, что дошло сюда, укладывается в [TrackProcessingConfig.maximumAccuracyMeters].
     * Прежний потолок в [TrackProcessingConfig.stopRadiusMeters] был выбран неудачно — он
     * зажимал допуск ровно тем радиусом, который должен был расширять, и на погрешности
     * хуже 12 м стоянка переставала определяться совсем.
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

    /**
     * Медиана, а не среднее: провайдер регулярно отдаёт одиночные выбросы скорости
     * в 3–4 м/с на стоящем месте, и одного такого сэмпла хватало, чтобы среднее по окну
     * перевалило порог и стоянка не объявилась.
     */
    private fun List<Double>.medianOrZero(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}
